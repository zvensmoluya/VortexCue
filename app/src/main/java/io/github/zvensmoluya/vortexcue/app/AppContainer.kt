package io.github.zvensmoluya.vortexcue.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import io.github.zvensmoluya.vortexcue.R
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.PlaybackStateRepository
import io.github.zvensmoluya.vortexcue.data.SettingsStore
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleAssetStore
import io.github.zvensmoluya.vortexcue.synctext.AndroidKeystoreCredentialStore
import io.github.zvensmoluya.vortexcue.synctext.AndroidSourceTextMaterializer
import io.github.zvensmoluya.vortexcue.synctext.AndroidSyncTextNetworkState
import io.github.zvensmoluya.vortexcue.synctext.AndroidSyncTextScheduler
import io.github.zvensmoluya.vortexcue.synctext.BoundedHttpExecutor
import io.github.zvensmoluya.vortexcue.synctext.BoundedPcmSpool
import io.github.zvensmoluya.vortexcue.synctext.EncodedAudioBudget
import io.github.zvensmoluya.vortexcue.synctext.OpenRouterAsrEngine
import io.github.zvensmoluya.vortexcue.synctext.PlatformAudioWindowExtractor
import io.github.zvensmoluya.vortexcue.synctext.PrivateGeneratedTextAssetStore
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
    val subtitleAssetStore = SubtitleAssetStore(appContext)
    val generatedTextAssetStore = PrivateGeneratedTextAssetStore(appContext)
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
    private val openRouterAsrEngine = OpenRouterAsrEngine(BoundedHttpExecutor(syncTextHttpClient.client), EncodedAudioBudget())
    private val connectionTester =
        SyncTextConnectionTester(
            translation = translationNetworkGateway,
            asr = openRouterAsrEngine,
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
            assets = generatedTextAssetStore,
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
            scope = scope,
        )
    val syncTextTaskRunner =
        SyncTextTaskRunner(
            database = database,
            repository = syncTextRepository,
            policies = translationPolicyRegistry,
            assets = generatedTextAssetStore,
            sourceText =
                AndroidSourceTextMaterializer(
                    appContext,
                    database,
                    subtitleAssetStore,
                    generatedTextAssetStore,
                ),
            audio = audioWindowExtractor,
            spool = pcmSpool,
            asr = openRouterAsrEngine,
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
            val retained = database.contentDao().referencedSubtitleCacheFiles().toSet()
            withContext(Dispatchers.IO) { subtitleAssetStore.pruneExcept(retained) }
            val retainedCredentials = database.syncTextDao().credentialIds().toSet()
            credentialStore.pruneExcept(retainedCredentials)
            val nowMs = System.currentTimeMillis()
            database.syncTextDao().pruneExpiredRequestAttempts(nowMs)
            database.syncTextDao().pruneTerminalTasks(nowMs - SyncTextTerminalRetentionMs)
            val generatedRetained =
                buildSet {
                    addAll(database.syncTextDao().referencedGeneratedAssetFiles())
                    addAll(database.syncTextDao().referencedCheckpointAssetFiles())
                    addAll(database.syncTextDao().referencedRawResponseFiles())
                }
            generatedTextAssetStore.pruneExcept(generatedRetained)
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
