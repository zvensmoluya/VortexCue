package io.github.zvensmoluya.vortexcue.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import io.github.zvensmoluya.vortexcue.R
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.PlaybackStateRepository
import io.github.zvensmoluya.vortexcue.data.SettingsStore
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.synctext.AndroidKeystoreCredentialStore
import io.github.zvensmoluya.vortexcue.synctext.AndroidSourceTextMaterializer
import io.github.zvensmoluya.vortexcue.synctext.AndroidSyncTextNetworkState
import io.github.zvensmoluya.vortexcue.synctext.AndroidSyncTextScheduler
import io.github.zvensmoluya.vortexcue.synctext.AsrEngineRouter
import io.github.zvensmoluya.vortexcue.synctext.BoundedHttpExecutor
import io.github.zvensmoluya.vortexcue.synctext.BoundedPcmSpool
import io.github.zvensmoluya.vortexcue.synctext.EncodedAudioBudget
import io.github.zvensmoluya.vortexcue.synctext.AsrProvider
import io.github.zvensmoluya.vortexcue.synctext.OpenAiAsrEngine
import io.github.zvensmoluya.vortexcue.synctext.OpenRouterAsrEngine
import io.github.zvensmoluya.vortexcue.synctext.PlatformAudioWindowExtractor
import io.github.zvensmoluya.vortexcue.synctext.PrivateRawResponseStore
import io.github.zvensmoluya.vortexcue.synctext.SyncTextHttpClient
import io.github.zvensmoluya.vortexcue.synctext.SyncTextNetworkMonitor
import io.github.zvensmoluya.vortexcue.synctext.SyncTextRepository
import io.github.zvensmoluya.vortexcue.synctext.SyncTextTaskRunner
import io.github.zvensmoluya.vortexcue.synctext.SyncTextConnectionTester
import io.github.zvensmoluya.vortexcue.synctext.TranslationAdapterRegistry
import io.github.zvensmoluya.vortexcue.synctext.TranslationEndpointResolver
import io.github.zvensmoluya.vortexcue.synctext.TranslationModelCatalog
import io.github.zvensmoluya.vortexcue.synctext.TranslationNetworkGateway
import io.github.zvensmoluya.vortexcue.synctext.TranslationPolicyRegistry
import io.github.zvensmoluya.vortexcue.synctext.defaultTranslationAdapters
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextUiController
import io.github.zvensmoluya.vortexcue.text.PrivateTextArtifactStore
import io.github.zvensmoluya.vortexcue.text.TextImportCoordinator
import io.github.zvensmoluya.vortexcue.text.TextStorageRepository
import io.github.zvensmoluya.vortexcue.text.TextStorageMaintenance
import io.github.zvensmoluya.vortexcue.text.TimelineRevisionReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VortexCueApplication : Application(), Configuration.Provider {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setJobSchedulerJobIdRange(WorkManagerJobIdMin, WorkManagerJobIdMax)
                .build()

    override fun onCreate() {
        super.onCreate()
        container.initialize()
    }
}

private const val WorkManagerJobIdMin = 0x56000000
private const val WorkManagerJobIdMax = 0x5600ffff

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: VortexDatabase = VortexDatabase.create(appContext)
    val contentRepository = ContentRepository(database)
    val playbackStateRepository = PlaybackStateRepository(database, contentRepository)
    val settingsStore = SettingsStore(appContext)
    val rawResponseStore = PrivateRawResponseStore(appContext)
    val textArtifactStore = PrivateTextArtifactStore(appContext)
    val textStorageRepository = TextStorageRepository(database, textArtifactStore)
    val timelineRevisionReader = TimelineRevisionReader(textStorageRepository)
    val textImportCoordinator = TextImportCoordinator(appContext, database, textStorageRepository)
    val textStorageMaintenance =
        TextStorageMaintenance(appContext, database, textStorageRepository, textArtifactStore)
    val credentialStore = AndroidKeystoreCredentialStore(appContext)
    val translationPolicyRegistry = TranslationPolicyRegistry.fromContext(appContext)
    val syncTextScheduler = AndroidSyncTextScheduler(appContext)
    val syncTextNetworkState = AndroidSyncTextNetworkState(appContext)
    private val syncTextHttpClient = SyncTextHttpClient(appContext.appVersionName())
    private val translationEndpointResolver = TranslationEndpointResolver()
    private val translationNetworkGateway =
        TranslationNetworkGateway(
            TranslationAdapterRegistry(defaultTranslationAdapters(syncTextHttpClient, translationEndpointResolver)),
            streamResponses = true,
        )
    private val asrEngine =
        AsrEngineRouter(
            mapOf(
                AsrProvider.OpenRouter to
                    OpenRouterAsrEngine(BoundedHttpExecutor(syncTextHttpClient.client), EncodedAudioBudget()),
                AsrProvider.OpenAi to OpenAiAsrEngine(BoundedHttpExecutor(syncTextHttpClient.client)),
            ),
        )
    private val connectionTester =
        SyncTextConnectionTester(
            translation = translationNetworkGateway,
            asr = asrEngine,
            policies = translationPolicyRegistry,
            createAsrProbeFile = ::createAsrProbeFile,
        )
    private val pcmSpool = BoundedPcmSpool(appContext)
    private val audioWindowExtractor = PlatformAudioWindowExtractor(appContext)
    private val translationModelCatalog =
        TranslationModelCatalog(BoundedHttpExecutor(syncTextHttpClient.client), translationEndpointResolver)
    val syncTextRepository =
        SyncTextRepository(
            database = database,
            textStorage = textStorageRepository,
            revisionReader = timelineRevisionReader,
            credentials = credentialStore,
            policies = translationPolicyRegistry,
            scope = scope,
            scheduler = syncTextScheduler,
            modelCatalog = translationModelCatalog,
            connectionTester = connectionTester,
        )
    val syncTextUiController =
        SyncTextUiController(
            repository = syncTextRepository,
            audio = audioWindowExtractor,
            materializeCandidate = { candidateId ->
                textImportCoordinator.importCandidate(
                    candidateId = candidateId,
                    slot = TextTrackSlot.Primary,
                    selectionMode = TextSelectionMode.Manual,
                ).trackId
            },
            scope = scope,
        )
    val syncTextTaskRunner =
        SyncTextTaskRunner(
            database = database,
            repository = syncTextRepository,
            policies = translationPolicyRegistry,
            rawResponses = rawResponseStore,
            textStorage = textStorageRepository,
            revisionReader = timelineRevisionReader,
            sourceText =
                AndroidSourceTextMaterializer(
                    appContext,
                    database,
                    textStorageRepository,
                    timelineRevisionReader,
                ),
            audio = audioWindowExtractor,
            spool = pcmSpool,
            asr = asrEngine,
            translation = translationNetworkGateway,
            networkState = syncTextNetworkState,
        )
    private val syncTextNetworkMonitor =
        SyncTextNetworkMonitor(
            appContext,
            syncTextRepository,
            syncTextNetworkState,
            syncTextScheduler,
            scope,
        )

    private fun createAsrProbeFile(): File {
        val output = File.createTempFile("vortexcue-asr-probe-", ".wav", appContext.cacheDir)
        try {
            appContext.resources.openRawResource(R.raw.asr_probe_en_us).use { input ->
                output.outputStream().use(input::copyTo)
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    fun initialize() {
        syncTextNetworkMonitor.start()
        scope.launch {
            playbackStateRepository.initialize()
            settingsStore.initializeFromLegacyAndCleanDomainStores()
            textImportCoordinator.recoverPending()
            textStorageMaintenance.recoverAuditAndCollect()
            val retainedCredentials = database.syncTextDao().credentialIds().toSet()
            credentialStore.pruneExcept(retainedCredentials)
            val nowMs = System.currentTimeMillis()
            database.syncTextDao().pruneExpiredRequestAttempts(nowMs)
            database.syncTextDao().pruneTerminalTasks(nowMs - SyncTextTerminalRetentionMs)
            val generatedRetained = database.syncTextDao().referencedRawResponseFiles().toSet()
            rawResponseStore.pruneExcept(generatedRetained)
            pcmSpool.pruneUnreserved()
            if (database.syncTextDao().activeTasks().isNotEmpty()) syncTextScheduler.schedule()
        }
    }
}

@Suppress("DEPRECATION")
private fun Context.appVersionName(): String =
    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

val Context.appContainer: AppContainer
    get() = (applicationContext as VortexCueApplication).container

private const val SyncTextTerminalRetentionMs = 7L * 24L * 60L * 60L * 1_000L
