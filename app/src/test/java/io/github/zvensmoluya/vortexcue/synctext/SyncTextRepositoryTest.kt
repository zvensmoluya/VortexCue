package io.github.zvensmoluya.vortexcue.synctext

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.data.db.ActiveMediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentIdentityEntity
import io.github.zvensmoluya.vortexcue.data.db.MediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.SyncTextRunnerLeaseEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.text.CanonicalTextNormalizer
import io.github.zvensmoluya.vortexcue.text.DecodedTextArtifact
import io.github.zvensmoluya.vortexcue.text.StoredTextArtifact
import io.github.zvensmoluya.vortexcue.text.TextArtifactCodec
import io.github.zvensmoluya.vortexcue.text.TextArtifactFile
import io.github.zvensmoluya.vortexcue.text.TextArtifactKind
import io.github.zvensmoluya.vortexcue.text.TextArtifactPayload
import io.github.zvensmoluya.vortexcue.text.TextArtifactStore
import io.github.zvensmoluya.vortexcue.text.TextStorageRepository
import io.github.zvensmoluya.vortexcue.text.TimelineRevisionReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SyncTextRepositoryTest {
    private lateinit var database: VortexDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var artifactStore: MemoryTextArtifactStore
    private lateinit var textStorage: TextStorageRepository
    private lateinit var revisionReader: TimelineRevisionReader
    private lateinit var credentials: MemoryCredentialStore
    private lateinit var repository: SyncTextRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        artifactStore = MemoryTextArtifactStore()
        textStorage = TextStorageRepository(database, artifactStore, nowMs = { clock++ })
        revisionReader = TimelineRevisionReader(textStorage)
        credentials = MemoryCredentialStore()
        repository =
            SyncTextRepository(
                database = database,
                textStorage = textStorage,
                revisionReader = revisionReader,
                credentials = credentials,
                policies = TranslationPolicyRegistry.forTesting(::promptBytes),
                scope = scope,
                nowMs = { clock++ },
                newId = { "id-${nextId++}" },
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `single config validates HTTPS endpoints and keeps a stable id`() = runBlocking {
        repository.saveCredential("shared", "Shared", CredentialPurpose.Shared, "secret")
        fun config(
            asrEndpoint: String = "https://openrouter.example/v1/audio/transcriptions/",
            translationEndpoint: String = "https://translation.example/v1/responses/",
            model: String = "model-v1",
        ) =
            SyncTextConfig(
                configId = "",
                asrEndpoint = asrEndpoint,
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "shared",
                translationProtocol = TranslationProtocol.Responses,
                translationEndpoint = translationEndpoint,
                translationModel = model,
                translationCredentialId = "shared",
                targetLanguage = "zh",
                presentationMode = TextPresentationMode.SourceTarget,
            )

        assertTrue(runCatching { repository.saveConfig(config(asrEndpoint = "http://openrouter.example")) }.isFailure)
        assertTrue(runCatching { repository.saveConfig(config(translationEndpoint = "http://translation.example")) }.isFailure)

        val first = repository.saveConfig(config())
        val second = repository.saveConfig(config(model = "model-v2"))

        assertEquals(first.configId, second.configId)
        assertEquals(1, database.syncTextDao().schemes().size)
        assertEquals("https://openrouter.example/v1/audio/transcriptions", second.asrEndpoint)
        assertEquals("https://translation.example/v1/responses", second.translationEndpoint)
        assertEquals("zh-CN", second.targetLanguage)
        assertEquals("model-v2", repository.loadConfig()?.translationModel)
    }

    @Test
    fun `saving a draft with blank secrets preserves the existing credential`() = runBlocking {
        repository.saveCredential("shared", "Shared", CredentialPurpose.Shared, "old-secret")
        val config =
            SyncTextConfig(
                configId = "",
                asrEndpoint = "https://openrouter.example/api/v1",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "shared",
                translationProtocol = TranslationProtocol.Responses,
                translationEndpoint = "https://translation.example/v1/responses",
                translationModel = "model-v1",
                translationCredentialId = "shared",
                targetLanguage = "zh-CN",
                presentationMode = TextPresentationMode.TargetOnly,
            )

        val saved = repository.saveConfigWithSecrets(config, asrSecret = null, translationSecret = null)

        assertEquals("shared", saved.asrCredentialId)
        assertEquals("shared", saved.translationCredentialId)
        assertEquals("old-secret", credentials.get("shared"))
    }

    @Test
    fun `updating one side of a shared credential updates the shared secret without losing the reference`() = runBlocking {
        repository.saveCredential("shared", "Shared", CredentialPurpose.Shared, "old-secret")
        val config =
            SyncTextConfig(
                configId = "",
                asrEndpoint = "https://openrouter.example/api/v1",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "shared",
                translationProtocol = TranslationProtocol.Responses,
                translationEndpoint = "https://translation.example/v1/responses",
                translationModel = "model-v1",
                translationCredentialId = "shared",
                targetLanguage = "zh-CN",
                presentationMode = TextPresentationMode.TargetOnly,
            )

        val saved = repository.saveConfigWithSecrets(config, asrSecret = "new-secret", translationSecret = null)

        assertEquals("shared", saved.asrCredentialId)
        assertEquals("shared", saved.translationCredentialId)
        assertEquals("new-secret", credentials.get("shared"))
        assertEquals(CredentialPurpose.Shared.name, database.syncTextDao().credential("shared")?.purpose)
    }

    @Test
    fun `direct credential replacement keeps a shared credential shared`() = runBlocking {
        repository.saveCredential("shared", "Shared", CredentialPurpose.Shared, "old-secret")

        val saved = repository.saveCredential("shared", "ASR", CredentialPurpose.Asr, "new-secret")

        assertEquals(CredentialPurpose.Shared, saved.purpose)
        assertEquals(CredentialPurpose.Shared.name, database.syncTextDao().credential("shared")?.purpose)
        assertEquals("new-secret", credentials.get("shared"))
    }

    @Test
    fun `two different secrets split a shared credential into valid references`() = runBlocking {
        repository.saveCredential("shared", "Shared", CredentialPurpose.Shared, "old-secret")
        val config =
            SyncTextConfig(
                configId = "",
                asrEndpoint = "https://openrouter.example/api/v1",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "shared",
                translationProtocol = TranslationProtocol.Responses,
                translationEndpoint = "https://translation.example/v1/responses",
                translationModel = "model-v1",
                translationCredentialId = "shared",
                targetLanguage = "zh-CN",
                presentationMode = TextPresentationMode.TargetOnly,
            )

        val saved = repository.saveConfigWithSecrets(config, asrSecret = "asr-secret", translationSecret = "translation-secret")

        assertFalse(saved.asrCredentialId == saved.translationCredentialId)
        assertEquals("asr-secret", credentials.get(saved.asrCredentialId))
        assertEquals("translation-secret", credentials.get(requireNotNull(saved.translationCredentialId)))
        assertEquals(AsrProfile.OpenRouterWhisperV1, repository.loadConfig()?.asrProfile)
    }

    @Test
    fun `masked display text cannot be stored as an api key`() = runBlocking {
        assertTrue(
            runCatching {
                repository.saveCredential("masked", "Masked", CredentialPurpose.Shared, "【已保存】")
            }.isFailure,
        )
    }

    @Test
    fun `task snapshots dedupe attempts and generated slot replacement stay atomic`() = runBlocking {
        insertContentWithTextTrack()
        repository.saveCredential("asr-key", "ASR", CredentialPurpose.Asr, "asr-secret")
        repository.saveCredential("translation-key", "Translation", CredentialPurpose.Translation, "translation-secret")
        val scheme =
            repository.createScheme(
                name = "Primary",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-key",
                translationProtocol = TranslationProtocol.Responses,
                translationEndpoint = "https://example.test/v1/responses",
                translationUserAgent = "PrivateGatewayClient/4.1",
                translationModel = "model-v1",
                translationCredentialId = "translation-key",
                defaultTargetLanguage = "zh",
                isDefault = true,
            )
        val sourceRequest =
            GenerationRequest(
                contentId = ContentId,
                input = GenerationInput.ExistingTrack(TrackId),
                output = GenerationOutput.SourceOnly,
                schemeId = scheme.schemeId,
            )

        val sourceTask = repository.submit(sourceRequest)
        val duplicate = repository.submit(sourceRequest)
        assertEquals(sourceTask.taskId, duplicate.taskId)
        assertEquals(GenerationTaskKind.SourceGeneration, sourceTask.kind)
        assertNull(database.syncTextDao().taskSnapshot(sourceTask.taskId)?.targetLanguage)

        repository.saveScheme(scheme.copy(translationModel = "model-v2", translationUserAgent = "PrivateGatewayClient/5.0"))
        val sourceEntity = requireNotNull(database.syncTextDao().task(sourceTask.taskId))
        assertEquals("model-v1", database.syncTextDao().attempt(sourceEntity.currentAttemptId)?.translationModel)

        val source = CueIdNormalizer.normalize(listOf(SubtitleCue(0L, 1_000L, "source")))
        val target = source.map { it.copy(text = "target") }
        val sourceResult =
            repository.publishResult(
                taskId = sourceTask.taskId,
                sourceLanguage = "en",
                targetLanguage = null,
                presentationMode = TextPresentationMode.TargetOnly,
                sourceCues = source,
                targetCues = null,
                completeTask = true,
            )
        assertEquals(GeneratedResultCompleteness.Final, sourceResult.completeness)

        val firstTranslation = repository.submitTranslation(sourceResult.resultId, scheme.schemeId, "zh")
        val translationDuplicate = repository.submitTranslation(sourceResult.resultId, scheme.schemeId, "zh")
        assertEquals(firstTranslation.taskId, translationDuplicate.taskId)
        assertEquals(GenerationTaskKind.TranslationDerivation, firstTranslation.kind)
        assertEquals(sourceResult.resultId, database.syncTextDao().taskSnapshot(firstTranslation.taskId)?.inputSourceResultId)
        val translationTask = requireNotNull(database.syncTextDao().task(firstTranslation.taskId))
        assertEquals("model-v2", database.syncTextDao().attempt(translationTask.currentAttemptId)?.translationModel)

        val firstResult =
            repository.publishResult(
                taskId = firstTranslation.taskId,
                sourceLanguage = "en",
                targetLanguage = "zh-CN",
                presentationMode = TextPresentationMode.SourceTarget,
                sourceCues = source,
                targetCues = target,
                usage = UsageRecord(totalTokens = 12L, costUsd = "0.01"),
                completeTask = true,
            )
        val regeneration = repository.submitTranslation(sourceResult.resultId, scheme.schemeId, "zh")
        val secondResult =
            repository.publishResult(
                taskId = regeneration.taskId,
                sourceLanguage = "en",
                targetLanguage = "zh-CN",
                presentationMode = TextPresentationMode.TargetOnly,
                sourceCues = source,
                targetCues = target.map { it.copy(text = "new target") },
                completeTask = true,
            )

        assertEquals(firstResult.slotId, secondResult.slotId)
        assertEquals(secondResult.resultId, database.syncTextDao().currentGeneratedResult(secondResult.slotId)?.resultId)
        assertNull(database.syncTextDao().generatedResult(firstResult.resultId))
        assertTrue(textStorage.revision(firstResult.sourceRevisionId) != null)
        assertEquals(TextPresentationMode.TargetOnly, repository.loadPresentation(secondResult.slotId)?.result?.presentationMode)
        repository.setPresentationMode(secondResult.slotId, TextPresentationMode.SourceTarget)
        assertEquals(secondResult.resultId, database.syncTextDao().currentGeneratedResult(secondResult.slotId)?.resultId)
        assertEquals(TextPresentationMode.SourceTarget, repository.loadPresentation(secondResult.slotId)?.result?.presentationMode)
        assertEquals(TranslationPolicyRegistry.OriginRevision, secondResult.policyRevision)
        assertEquals(
            "bbf6c688ce3df8953fe876ade60a69c28e837234cb3061694bab1467f642891f",
            secondResult.systemPromptSha256,
        )
        assertEquals(
            "09de51ae2e3709c8e506a4d9a82c9ce3bc9b16a13d0f683a928aad3a2d91e07b",
            secondResult.stylePromptSha256,
        )
        val deleteFailure = runCatching { repository.deleteCredential("asr-key") }.exceptionOrNull()
        assertTrue(deleteFailure is CredentialStillReferencedException)
        val schemeDeleteFailure = runCatching { repository.deleteScheme(scheme.schemeId) }.exceptionOrNull()
        assertTrue(schemeDeleteFailure is SchemeStillReferencedException)

        database.syncTextDao().pruneTerminalTasks(Long.MAX_VALUE)
        assertNull(database.syncTextDao().task(regeneration.taskId))
        assertEquals(secondResult.resultId, database.syncTextDao().currentGeneratedResult(secondResult.slotId)?.resultId)
    }

    @Test
    fun `source generation works without translation config and partial source cannot translate`() = runBlocking {
        insertContentWithTextTrack()
        repository.saveCredential("asr-key", "ASR", CredentialPurpose.Asr, "asr-secret")
        val scheme =
            repository.createScheme(
                name = "ASR only",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-key",
                isDefault = true,
            )
        val task =
            repository.submit(
                GenerationRequest(ContentId, GenerationInput.ExistingTrack(TrackId), GenerationOutput.SourceOnly, scheme.schemeId),
            )
        val cues = CueIdNormalizer.normalize(listOf(SubtitleCue(0L, 1_000L, "source")))
        val partialArtifact =
            textStorage.publishTimelineArtifact(
                TextArtifactKind.AsrWorkUnitOutput,
                CanonicalTextNormalizer.normalizeGenerated(cues),
            ).also { textStorage.registerArtifact(it) }
        val partial =
            repository.publishResult(
                taskId = task.taskId,
                sourceLanguage = "en",
                targetLanguage = null,
                presentationMode = TextPresentationMode.TargetOnly,
                sourceCues = cues,
                targetCues = null,
                completeTask = false,
                completedWindowCount = 1,
                totalWindowCount = 2,
                partialArtifactIds = listOf(partialArtifact.artifactId),
            )

        assertEquals(GenerationTaskKind.SourceGeneration, task.kind)
        assertEquals(GeneratedResultCompleteness.Partial, partial.completeness)
        assertTrue(runCatching { repository.submitTranslation(partial.resultId) }.isFailure)

        val final =
            repository.publishResult(
                taskId = task.taskId,
                sourceLanguage = "en",
                targetLanguage = null,
                presentationMode = TextPresentationMode.TargetOnly,
                sourceCues = cues,
                targetCues = null,
                completeTask = true,
                completedWindowCount = 2,
                totalWindowCount = 2,
            )
        assertEquals(GeneratedResultCompleteness.Final, final.completeness)
        assertTrue(runCatching { repository.submitTranslation(final.resultId) }.isFailure)
    }

    @Test
    fun `runner lease excludes a second owner until expiry`() = runBlocking {
        val dao = database.syncTextDao()
        dao.insertRunnerLease(SyncTextRunnerLeaseEntity("queue", "first", expiresAtMs = 100L, generation = 0L))

        assertEquals(0, dao.tryAcquireRunnerLease("queue", "second", nowMs = 50L, expiresAtMs = 150L))
        assertEquals(1, dao.tryAcquireRunnerLease("queue", "second", nowMs = 101L, expiresAtMs = 201L))
        assertEquals(0, dao.releaseRunnerLease("queue", "first", nowMs = 102L))
        assertEquals(1, dao.releaseRunnerLease("queue", "second", nowMs = 102L))
    }

    @Test
    fun `pause resume and global pause remain durable queue state`() = runBlocking {
        insertContentWithTextTrack()
        repository.saveCredential("shared", "Shared", CredentialPurpose.Shared, "secret")
        val scheme =
            repository.createScheme(
                name = "Shared",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "shared",
                translationProtocol = TranslationProtocol.ChatCompletions,
                translationEndpoint = "https://example.test/v1/chat/completions",
                translationModel = "model",
                translationCredentialId = "shared",
                defaultTargetLanguage = "fr",
            )
        val task =
            repository.submit(
                GenerationRequest(ContentId, GenerationInput.ExistingTrack(TrackId), GenerationOutput.SourceOnly, scheme.schemeId),
            )

        repository.pauseTask(task.taskId)
        assertEquals(GenerationTaskState.Paused.name, database.syncTextDao().task(task.taskId)?.state)
        repository.markRunning(task.taskId, GenerationStage.RecognizingSpeech, 0.5f)
        assertEquals(GenerationTaskState.Paused.name, database.syncTextDao().task(task.taskId)?.state)
        repository.resumeTask(task.taskId)
        assertEquals(GenerationTaskState.Waiting.name, database.syncTextDao().task(task.taskId)?.state)
        repository.markWaitingForNetwork(task.taskId, GenerationNetworkWaitReason.MeteredPermissionRequired)
        val meteredWait = requireNotNull(database.syncTextDao().task(task.taskId))
        assertTrue(meteredWait.waitingForNetwork)
        assertEquals(GenerationNetworkWaitReason.MeteredPermissionRequired.name, meteredWait.networkWaitReason)
        repository.markRunning(task.taskId, GenerationStage.RecognizingSpeech, 0.6f)
        assertTrue(database.syncTextDao().task(task.taskId)?.waitingForNetwork == true)
        repository.allowMeteredNetworkForContent(ContentId)
        val allowed = requireNotNull(database.syncTextDao().task(task.taskId))
        assertTrue(allowed.meteredNetworkAllowed)
        assertFalse(allowed.waitingForNetwork)
        assertNull(allowed.networkWaitReason)
        repository.markRunning(task.taskId, GenerationStage.RecognizingSpeech, 0.6f)
        repository.markRunning(task.taskId, GenerationStage.DecodingAudio, 0.2f)
        val nonRegressed = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(0.6f, nonRegressed.progress)
        assertEquals(GenerationStage.DecodingAudio.name, nonRegressed.stage)
        repository.resumeTask(task.taskId)
        repository.setPauseAll(true)
        assertTrue(database.syncTextDao().queueControl()?.pauseAll == true)
        assertNull(repository.nextRunnableTask())
        repository.setPauseAll(false)
        assertEquals(task.taskId, repository.nextRunnableTask()?.taskId)

        repository.deleteTask(task.taskId)
        val deleted = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.Deleted.name, deleted.state)
        assertNull(deleted.activeDedupeKey)
        repository.markRunning(task.taskId, GenerationStage.RecognizingSpeech, 0.75f)
        assertEquals(GenerationTaskState.Deleted.name, database.syncTextDao().task(task.taskId)?.state)
        assertNull(repository.nextRunnableTask())

        val replacement =
            repository.submit(
                GenerationRequest(ContentId, GenerationInput.ExistingTrack(TrackId), GenerationOutput.SourceOnly, scheme.schemeId),
            )
        assertFalse(replacement.taskId == task.taskId)
        database.syncTextDao().pruneTerminalTasks(Long.MAX_VALUE)
        assertNull(database.syncTextDao().task(task.taskId))
        assertEquals(replacement.taskId, repository.nextRunnableTask()?.taskId)
    }

    @Test
    fun `aborting a task makes it terminal and allows a replacement`() = runBlocking {
        insertContentWithTextTrack()
        repository.saveCredential("abort", "Abort", CredentialPurpose.Shared, "secret")
        val scheme = repository.createScheme(
            name = "Abort",
            asrProfile = AsrProfile.OpenRouterWhisperV1,
            asrCredentialId = "abort",
            translationProtocol = TranslationProtocol.ChatCompletions,
            translationEndpoint = "https://example.test/v1/chat/completions",
            translationModel = "model",
            translationCredentialId = "abort",
            defaultTargetLanguage = "fr",
        )
        val task = repository.submit(
            GenerationRequest(ContentId, GenerationInput.ExistingTrack(TrackId), GenerationOutput.SourceOnly, scheme.schemeId),
        )

        repository.abortTask(task.taskId)

        val aborted = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.Aborted.name, aborted.state)
        assertNull(aborted.activeDedupeKey)
        assertNull(repository.nextRunnableTask())
        val replacement = repository.submit(
            GenerationRequest(ContentId, GenerationInput.ExistingTrack(TrackId), GenerationOutput.SourceOnly, scheme.schemeId),
        )
        assertEquals(replacement.taskId, repository.nextRunnableTask()?.taskId)

        repository.deleteTask(task.taskId)
        assertEquals(GenerationTaskState.Deleted.name, database.syncTextDao().task(task.taskId)?.state)
    }

    private suspend fun insertContentWithTextTrack() {
        val dao = database.contentDao()
        dao.upsertContent(ContentEntity(ContentId, 1L))
        dao.upsertIdentity(ContentIdentityEntity("identity", ContentId))
        dao.upsertLocator(
            MediaLocatorEntity(
                locatorId = LocatorId,
                contentId = ContentId,
                identityKey = "identity",
                sourceId = null,
                uriValue = "content://media/item",
                displayName = "item.mkv",
                mimeType = "video/x-matroska",
                title = "Item",
                artist = null,
                album = null,
                durationMs = 10_000L,
                folder = null,
                dateAddedSeconds = 0L,
                origin = "OpenDocument",
                kind = "Video",
                sizeBytes = 100L,
                videoWidth = 1,
                videoHeight = 1,
                availability = "Available",
                seenGeneration = 0L,
                updatedAtMs = 1L,
            ),
        )
        dao.upsertActiveLocator(ActiveMediaLocatorEntity(ContentId, LocatorId))
        dao.upsertTextTrack(
            TimedTextTrackEntity(
                trackId = TrackId,
                contentId = ContentId,
                origin = "Sidecar",
                locatorIdentity = "embedded:text:0",
                revisionId = null,
                embeddedTrackId = "text:0",
                generatedSlotId = null,
                displayName = "item.vtt",
                mimeType = "text/vtt",
                format = "vtt",
                language = "en",
                label = "English",
                matchBasis = "UserSelected",
                readiness = "Ready",
                failureMessage = null,
            ),
        )
    }

    private fun promptBytes(id: String): ByteArray {
        val name = if (id == "system.v1") "translation_system_v1.md" else "translation_style_zh_cn_v1.md"
        return File("src/main/res/raw/$name").readBytes()
    }

    companion object {
        private const val ContentId = "content"
        private const val LocatorId = "locator"
        private const val TrackId = "track"
        private var clock = 1_000L
        private var nextId = 1
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncTextTaskRunnerRecoveryTest {
    private lateinit var database: VortexDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var artifactStore: MemoryTextArtifactStore
    private lateinit var textStorage: TextStorageRepository
    private lateinit var revisionReader: TimelineRevisionReader
    private lateinit var rawResponses: MemoryRawResponseStore
    private lateinit var repository: SyncTextRepository
    private lateinit var credentials: MemoryCredentialStore

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        artifactStore = MemoryTextArtifactStore()
        textStorage = TextStorageRepository(database, artifactStore, nowMs = { runnerClock++ })
        revisionReader = TimelineRevisionReader(textStorage)
        rawResponses = MemoryRawResponseStore()
        credentials = MemoryCredentialStore()
        repository =
            SyncTextRepository(
                database = database,
                textStorage = textStorage,
                revisionReader = revisionReader,
                credentials = credentials,
                policies = TranslationPolicyRegistry.forTesting(::promptBytes),
                scope = scope,
                nowMs = { runnerClock++ },
                newId = { "runner-id-${runnerNextId++}" },
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `runner publishes contiguous prefix and resumes only missing asr window`() = runBlocking {
        insertAudioContent(durationMs = 700_000L)
        repository.saveCredential("asr", "ASR", CredentialPurpose.Asr, "secret")
        val scheme =
            repository.createScheme(
                name = "ASR",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr",
                isDefault = true,
            )
        val task =
            repository.submit(
                GenerationRequest(
                    contentId = RunnerContentId,
                    input = GenerationInput.AudioTrack("audio:0"),
                    output = GenerationOutput.SourceOnly,
                    schemeId = scheme.schemeId,
                ),
            )
        val engine = RecoveringAsrEngine()
        val runner = runner(engine)

        assertTrue(runner.runUntilIdle())
        val failedTask = requireNotNull(database.syncTextDao().task(task.taskId))
        val partial = requireNotNull(repository.currentSourceResult(RunnerContentId))
        assertEquals(GenerationTaskState.NeedsAttention.name, failedTask.state)
        assertEquals(GeneratedResultCompleteness.Partial, partial.completeness)
        assertEquals(1, partial.completedWindowCount)
        assertEquals(3, partial.totalWindowCount)
        assertEquals(1, engine.calls(0))
        assertEquals(1, engine.calls(1))
        assertEquals(1, engine.calls(2))

        repository.resumeTask(task.taskId)
        assertTrue(runner.runUntilIdle())
        val completedTask = requireNotNull(database.syncTextDao().task(task.taskId))
        val final = requireNotNull(repository.currentSourceResult(RunnerContentId))
        assertEquals(GenerationTaskState.Completed.name, completedTask.state)
        assertEquals(GeneratedResultCompleteness.Final, final.completeness)
        assertEquals(3, final.completedWindowCount)
        assertEquals(3, final.totalWindowCount)
        assertEquals(1, engine.calls(0))
        assertEquals(2, engine.calls(1))
        assertEquals(1, engine.calls(2))
    }

    @Test
    fun `corrupt asr checkpoint is a task local storage failure`() = runBlocking {
        insertAudioContent(durationMs = 700_000L)
        repository.saveCredential("asr-corrupt", "ASR", CredentialPurpose.Asr, "secret")
        val scheme =
            repository.createScheme(
                name = "ASR corrupt",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-corrupt",
                isDefault = true,
            )
        val task =
            repository.submit(
                GenerationRequest(
                    contentId = RunnerContentId,
                    input = GenerationInput.AudioTrack("audio:0"),
                    output = GenerationOutput.SourceOnly,
                    schemeId = scheme.schemeId,
                ),
            )
        val engine = RecoveringAsrEngine()
        val runner = runner(engine)

        assertTrue(runner.runUntilIdle())
        val attemptId = requireNotNull(database.syncTextDao().task(task.taskId)).currentAttemptId
        val firstWindow =
            requireNotNull(
                database.syncTextDao().asrWindows(attemptId)
                    .firstOrNull { it.ordinal == 0 && it.state == "Completed" },
            )
        artifactStore.corrupt(requireNotNull(firstWindow.transcriptArtifactId))

        repository.resumeTask(task.taskId)
        assertTrue(runner.runUntilIdle())
        val blocked = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.NeedsAttention.name, blocked.state)
        assertEquals(GenerationErrorCategory.Storage.name, blocked.errorCategory)
        assertFalse(blocked.schemeBlocked)
        assertEquals(1, engine.calls(0))
    }

    @Test
    fun `metered task waits without retrying and resumes after explicit permission`() = runBlocking {
        insertAudioContent(durationMs = 100_000L)
        repository.saveCredential("asr-metered", "ASR", CredentialPurpose.Asr, "secret")
        val scheme =
            repository.createScheme(
                name = "Metered ASR",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-metered",
                isDefault = true,
            )
        val task =
            repository.submit(
                GenerationRequest(
                    contentId = RunnerContentId,
                    input = GenerationInput.AudioTrack("audio:0"),
                    output = GenerationOutput.SourceOnly,
                    schemeId = scheme.schemeId,
                ),
            )
        val engine = RecoveringAsrEngine()
        val networkState = MeteredNetworkState()
        val runner = runner(engine, networkState)

        assertTrue(runner.runUntilIdle())
        val waiting = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.Waiting.name, waiting.state)
        assertEquals(GenerationNetworkWaitReason.MeteredPermissionRequired.name, waiting.networkWaitReason)
        assertFalse(repository.hasRetryableNetworkWaitingTasks())
        assertEquals(0, engine.calls(0))

        repository.allowMeteredNetworkForContent(RunnerContentId)
        assertTrue(runner.runUntilIdle())
        val completedTask = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.Completed.name, completedTask.state)
        assertEquals(1, engine.calls(0))
        val request = database.syncTextDao().requestAttempts(completedTask.currentAttemptId).single()
        assertEquals("AsrWindow", request.workUnitKind)
        assertTrue(request.workUnitId?.isNotBlank() == true)
        assertEquals(1, request.operationOrdinal)
        assertEquals(1, request.retryOrdinal)
        assertTrue(requireNotNull(request.finishedAtMs) > request.startedAtMs)
        assertTrue(requireNotNull(request.durationMs) >= 0L)
    }

    @Test
    fun `transport failure becomes recoverable wait when validated network disappears`() = runBlocking {
        insertAudioContent(durationMs = 100_000L)
        repository.saveCredential("asr-network", "ASR", CredentialPurpose.Asr, "secret")
        val scheme =
            repository.createScheme(
                name = "Network ASR",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-network",
                isDefault = true,
            )
        val task =
            repository.submit(
                GenerationRequest(
                    contentId = RunnerContentId,
                    input = GenerationInput.AudioTrack("audio:0"),
                    output = GenerationOutput.SourceOnly,
                    schemeId = scheme.schemeId,
                ),
            )
        val networkState = MutableNetworkState()
        val engine = NetworkDroppingAsrEngine(networkState)
        val runner = runner(engine, networkState)

        assertTrue(runner.runUntilIdle())
        val waiting = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.Waiting.name, waiting.state)
        assertEquals(GenerationNetworkWaitReason.Unavailable.name, waiting.networkWaitReason)
        assertFalse(waiting.needsAttention)
        assertTrue(repository.hasRetryableNetworkWaitingTasks())

        networkState.available = true
        repository.wakeEligibleNetworkTasks(networkState)
        assertTrue(runner.runUntilIdle())
        assertEquals(GenerationTaskState.Completed.name, database.syncTextDao().task(task.taskId)?.state)
        assertEquals(2, engine.calls.get())
    }

    private fun runner(
        engine: AsrEngine,
        networkState: SyncTextNetworkState = AlwaysAvailableNetworkState,
    ) =
        SyncTextTaskRunner(
            database = database,
            repository = repository,
            policies = TranslationPolicyRegistry.forTesting(::promptBytes),
            rawResponses = rawResponses,
            textStorage = textStorage,
            revisionReader = revisionReader,
            sourceText =
                object : SourceTextMaterializer {
                    override suspend fun materialize(
                        contentId: String,
                        snapshot: io.github.zvensmoluya.vortexcue.data.db.GenerationTaskSnapshotEntity,
                    ): SourceTextMaterialization = error("Source materializer should not be used")
                },
            audio =
                object : AudioWindowExtractor {
                    override suspend fun inspect(uri: android.net.Uri): List<AudioTrackInfo> = emptyList()

                    override suspend fun extract(
                        uri: android.net.Uri,
                        audioTrackId: String?,
                        plan: AsrWindowPlan,
                        outputFile: File,
                    ): ExtractedAudioWindow {
                        outputFile.writeBytes(byteArrayOf(1))
                        return ExtractedAudioWindow(
                            outputFile,
                            AudioTrackInfo("audio:0", 0, "audio/test", "en", null, 1, 16_000, 700_000L),
                            1L,
                        )
                    }
                },
            spool = TemporaryPcmSpool(),
            asr = engine,
            translation = TranslationNetworkGateway(TranslationAdapterRegistry(emptyList())),
            networkState = networkState,
            nowMs = { runnerClock++ },
            newId = { "lease-${runnerNextId++}" },
        )

    private suspend fun insertAudioContent(durationMs: Long) {
        val dao = database.contentDao()
        dao.upsertContent(ContentEntity(RunnerContentId, 1L))
        dao.upsertIdentity(ContentIdentityEntity("runner-identity", RunnerContentId))
        dao.upsertLocator(
            MediaLocatorEntity(
                locatorId = RunnerLocatorId,
                contentId = RunnerContentId,
                identityKey = "runner-identity",
                sourceId = null,
                uriValue = "content://media/runner",
                displayName = "runner.mkv",
                mimeType = "video/x-matroska",
                title = "Runner",
                artist = null,
                album = null,
                durationMs = durationMs,
                folder = null,
                dateAddedSeconds = 0L,
                origin = "OpenDocument",
                kind = "Video",
                sizeBytes = 100L,
                videoWidth = 1,
                videoHeight = 1,
                availability = "Available",
                seenGeneration = 0L,
                updatedAtMs = 1L,
            ),
        )
        dao.upsertActiveLocator(ActiveMediaLocatorEntity(RunnerContentId, RunnerLocatorId))
    }

    private fun promptBytes(id: String): ByteArray {
        val name = if (id == "system.v1") "translation_system_v1.md" else "translation_style_zh_cn_v1.md"
        return File("src/main/res/raw/$name").readBytes()
    }

    companion object {
        private const val RunnerContentId = "runner-content"
        private const val RunnerLocatorId = "runner-locator"
        private var runnerClock = 20_000L
        private var runnerNextId = 20_000
    }
}

private object AlwaysAvailableNetworkState : SyncTextNetworkState {
    override fun permits(allowMetered: Boolean) = true
}

private class MeteredNetworkState : SyncTextNetworkState {
    override fun permits(allowMetered: Boolean): Boolean = allowMetered

    override fun waitReason(allowMetered: Boolean): GenerationNetworkWaitReason? =
        if (allowMetered) null else GenerationNetworkWaitReason.MeteredPermissionRequired
}

private class MutableNetworkState : SyncTextNetworkState {
    @Volatile
    var available: Boolean = true

    override fun permits(allowMetered: Boolean): Boolean = available
}

private class NetworkDroppingAsrEngine(
    private val networkState: MutableNetworkState,
) : AsrEngine {
    val calls = AtomicInteger(0)

    override suspend fun transcribe(request: AsrRequest): AsrWindowResult {
        if (calls.incrementAndGet() == 1) {
            networkState.available = false
            throw AsrException(
                category = GenerationErrorCategory.TemporaryNetwork,
                retryable = false,
                shouldSplit = false,
                message = "network disappeared",
                cause = IOException("socket closed"),
            )
        }
        return AsrWindowResult(
            segments =
                listOf(
                    AsrTimestampedToken(
                        request.window.ownedStartMs + 10L,
                        request.window.ownedStartMs + 1_000L,
                        "recovered",
                    ),
                ),
            detectedLanguage = "en",
            usage = UsageRecord(totalTokens = 1L),
            rawResponse = byteArrayOf(1),
            httpStatus = 200,
            requestBytes = 1L,
            responseBytes = 1L,
            requestId = "response-recovered",
        )
    }
}

private class RecoveringAsrEngine : AsrEngine {
    private val callCounts = ConcurrentHashMap<Int, Int>()
    private val secondRootStarted = CompletableDeferred<Unit>()

    override suspend fun transcribe(request: AsrRequest): AsrWindowResult {
        val ordinal = request.window.ordinal
        val count = callCounts.merge(ordinal, 1, Int::plus) ?: 1
        if (ordinal == 2) secondRootStarted.complete(Unit)
        if (ordinal == 1 && count == 1) secondRootStarted.await()
        if (ordinal == 1 && count == 1) {
            throw AsrException(
                category = GenerationErrorCategory.TemporaryNetwork,
                retryable = false,
                shouldSplit = false,
                message = "temporary test failure",
            )
        }
        return AsrWindowResult(
            segments =
                listOf(
                    AsrTimestampedToken(
                        request.window.ownedStartMs + 10L,
                        request.window.ownedStartMs + 1_000L,
                        "window-$ordinal",
                    ),
                ),
            detectedLanguage = "en",
            usage = UsageRecord(totalTokens = 1L),
            rawResponse = byteArrayOf(1),
            httpStatus = 200,
            requestBytes = 1L,
            responseBytes = 1L,
            requestId = null,
        )
    }

    fun calls(ordinal: Int): Int = callCounts[ordinal] ?: 0
}

private class TemporaryPcmSpool : PcmSpool {
    private val root = Files.createTempDirectory("vortexcue-runner-test").toFile()

    override suspend fun reserve(taskId: String, windowId: String): File = File(root, "$taskId-$windowId.wav")

    override suspend fun release(file: File, delete: Boolean) {
        if (delete) file.delete()
    }
}

private class MemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun put(credentialId: String, secret: String) {
        values[credentialId] = secret
    }

    override suspend fun get(credentialId: String): String = requireNotNull(values[credentialId])

    override suspend fun delete(credentialId: String) {
        values.remove(credentialId)
    }

    override suspend fun contains(credentialId: String): Boolean = credentialId in values
}

private class MemoryRawResponseStore : RawResponseStore {
    private val bytes = mutableMapOf<String, ByteArray>()

    override suspend fun write(fileName: String, bytes: ByteArray): String {
        this.bytes[fileName] = bytes
        return fileName
    }

    override suspend fun delete(fileName: String) {
        bytes.remove(fileName)
    }

    override suspend fun pruneExcept(retainedFileNames: Set<String>) {
        bytes.keys.retainAll(retainedFileNames)
    }
}

private class MemoryTextArtifactStore : TextArtifactStore {
    private val bytes = mutableMapOf<String, ByteArray>()

    override suspend fun publish(
        kind: TextArtifactKind,
        payload: TextArtifactPayload,
        canonicalizerVersion: Int,
    ): StoredTextArtifact {
        val encoded = TextArtifactCodec.encode(kind, payload, canonicalizerVersion)
        bytes.putIfAbsent(encoded.artifactId, encoded.bytes)
        return StoredTextArtifact(
            artifactId = encoded.artifactId,
            kind = encoded.kind,
            schemaVersion = encoded.schemaVersion,
            codecVersion = encoded.codecVersion,
            canonicalizerVersion = encoded.canonicalizerVersion,
            payloadSha256 = encoded.artifactId,
            byteSize = encoded.bytes.size.toLong(),
            cueCount = encoded.cueCount,
        )
    }

    override suspend fun read(metadata: StoredTextArtifact): DecodedTextArtifact {
        val value = bytes[metadata.artifactId] ?: throw IOException("Artifact is unavailable")
        if (value.size.toLong() != metadata.byteSize) throw IOException("Artifact size does not match")
        if (TextArtifactCodec.sha256(value) != metadata.artifactId) throw IOException("Artifact hash does not match")
        return TextArtifactCodec.decode(value)
    }

    override suspend fun inspectPublished(
        artifactId: String,
        expectedKind: TextArtifactKind,
    ): io.github.zvensmoluya.vortexcue.text.InspectedTextArtifact {
        val value = bytes[artifactId] ?: throw IOException("Artifact is unavailable")
        if (TextArtifactCodec.sha256(value) != artifactId) throw IOException("Artifact hash does not match")
        val decoded = TextArtifactCodec.decode(value)
        if (decoded.kind != expectedKind) throw IOException("Artifact kind does not match")
        val cueCount =
            when (val payload = decoded.payload) {
                is TextArtifactPayload.Timeline -> payload.value.cues.size
                is TextArtifactPayload.Delta -> payload.value.entries.size
            }
        return io.github.zvensmoluya.vortexcue.text.InspectedTextArtifact(
            StoredTextArtifact(
                artifactId,
                decoded.kind,
                decoded.schemaVersion,
                decoded.codecVersion,
                decoded.canonicalizerVersion,
                artifactId,
                value.size.toLong(),
                cueCount,
            ),
            decoded,
        )
    }

    override suspend fun exists(metadata: StoredTextArtifact): Boolean =
        runCatching { read(metadata) }.isSuccess

    override suspend fun listFiles(): List<TextArtifactFile> = emptyList()

    override suspend fun delete(metadata: StoredTextArtifact): Boolean = bytes.remove(metadata.artifactId) != null

    override suspend fun deleteUnregistered(file: TextArtifactFile): Boolean = true

    fun corrupt(artifactId: String) {
        bytes[artifactId] = "corrupt".toByteArray()
    }
}
