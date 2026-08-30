package io.github.zvensmoluya.vortexcue.synctext

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.data.db.ActiveMediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentIdentityEntity
import io.github.zvensmoluya.vortexcue.data.db.MediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.SyncTextRunnerLeaseEntity
import io.github.zvensmoluya.vortexcue.data.db.TextSelectionEntity
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
    private lateinit var scheduler: RecordingSyncTextScheduler
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
        scheduler = RecordingSyncTextScheduler()
        repository =
            SyncTextRepository(
                database = database,
                textStorage = textStorage,
                revisionReader = revisionReader,
                credentials = credentials,
                policies = TranslationPolicyRegistry.forTesting(::promptBytes),
                scope = scope,
                scheduler = scheduler,
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
    fun `task snapshots dedupe attempts and keep latest generated alternatives atomic`() = runBlocking {
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
                translationReasoningLevel = TranslationReasoningLevel.High,
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

        repository.saveScheme(
            scheme.copy(
                translationModel = "model-v2",
                translationReasoningLevel = TranslationReasoningLevel.Low,
                translationUserAgent = "PrivateGatewayClient/5.0",
            ),
        )
        val sourceEntity = requireNotNull(database.syncTextDao().task(sourceTask.taskId))
        assertEquals("model-v1", database.syncTextDao().attempt(sourceEntity.currentAttemptId)?.translationModel)
        assertEquals(
            TranslationReasoningLevel.High.name,
            database.syncTextDao().attempt(sourceEntity.currentAttemptId)?.translationReasoningLevel,
        )

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
        assertEquals(
            TranslationReasoningLevel.Low.name,
            database.syncTextDao().attempt(translationTask.currentAttemptId)?.translationReasoningLevel,
        )

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

        assertFalse(firstResult.slotId == secondResult.slotId)
        assertEquals(secondResult.resultId, database.syncTextDao().currentGeneratedResult(secondResult.slotId)?.resultId)
        assertEquals(firstResult.resultId, database.syncTextDao().currentGeneratedResult(firstResult.slotId)?.resultId)
        assertTrue(textStorage.revision(firstResult.sourceRevisionId) != null)
        assertEquals(TextPresentationMode.TargetOnly, repository.loadPresentation(secondResult.slotId)?.result?.presentationMode)
        repository.setPresentationMode(secondResult.slotId, TextPresentationMode.SourceTarget)
        assertEquals(secondResult.resultId, database.syncTextDao().currentGeneratedResult(secondResult.slotId)?.resultId)
        assertEquals(TextPresentationMode.SourceTarget, repository.loadPresentation(secondResult.slotId)?.result?.presentationMode)
        assertEquals("vortexcue-translation-v3", secondResult.policyRevision)
        assertEquals(
            "0caef8f6f9f2a28e951196c67b8852c4307c9232596786549e7ea702e5352029",
            secondResult.systemPromptSha256,
        )
        assertEquals(
            "599dddf9486a6fb5a1822dc2135dd090337838c2e4674aab2df36c26c62e910a",
            secondResult.stylePromptSha256,
        )
        val thirdTask =
            repository.submitTranslationFromSlot(
                slotId = secondResult.slotId,
                schemeId = scheme.schemeId,
                targetLanguage = "zh",
                activationTrackId = TrackId,
            )
        val thirdSnapshot = requireNotNull(database.syncTextDao().taskSnapshot(thirdTask.taskId))
        assertNull(thirdSnapshot.inputSourceResultId)
        assertEquals(sourceResult.sourceRevisionId, thirdSnapshot.inputSourceRevisionId)
        assertEquals(sourceResult.sourceSha256, thirdSnapshot.inputSourceRevisionHash)
        assertEquals(TrackId, thirdSnapshot.activationTrackId)
        val thirdResult =
            repository.publishResult(
                taskId = thirdTask.taskId,
                sourceLanguage = "en",
                targetLanguage = "zh-CN",
                presentationMode = TextPresentationMode.TargetOnly,
                sourceCues = source,
                targetCues = target.map { it.copy(text = "third target") },
                completeTask = true,
            )
        assertNull(database.syncTextDao().generatedResult(firstResult.resultId))
        assertEquals(secondResult.resultId, database.syncTextDao().generatedResult(secondResult.resultId)?.resultId)
        assertEquals(thirdResult.resultId, database.syncTextDao().generatedResult(thirdResult.resultId)?.resultId)
        val translatedTracks = database.contentDao().textTracks(ContentId).filter { it.language == "zh-CN" }
        assertEquals(2, translatedTracks.size)
        assertTrue(translatedTracks.any { it.label == "生成译文 · zh-CN" })
        assertTrue(translatedTracks.any { it.label == "生成译文 · zh-CN · 上一版" })
        val deleteFailure = runCatching { repository.deleteCredential("asr-key") }.exceptionOrNull()
        assertTrue(deleteFailure is CredentialStillReferencedException)
        val schemeDeleteFailure = runCatching { repository.deleteScheme(scheme.schemeId) }.exceptionOrNull()
        assertTrue(schemeDeleteFailure is SchemeStillReferencedException)

        database.syncTextDao().pruneTerminalTasks(Long.MAX_VALUE)
        assertNull(database.syncTextDao().task(regeneration.taskId))
        assertEquals(thirdResult.resultId, database.syncTextDao().currentGeneratedResult(thirdResult.slotId)?.resultId)
    }

    @Test
    fun `audio translation submission freezes media and intended activation`() = runBlocking {
        insertContentWithTextTrack()
        database.contentDao().upsertTextSelection(
            TextSelectionEntity(
                contentId = ContentId,
                slot = TextTrackSlot.Primary.name,
                selectedTrackId = TrackId,
                mode = TextSelectionMode.Manual.name,
            ),
        )
        repository.saveCredential("asr-combined", "ASR", CredentialPurpose.Asr, "asr-secret")
        repository.saveCredential(
            "translation-combined",
            "Translation",
            CredentialPurpose.Translation,
            "translation-secret",
        )
        val scheme =
            repository.createScheme(
                name = "Combined",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-combined",
                translationProtocol = TranslationProtocol.Responses,
                translationEndpoint = "https://example.test/v1/responses",
                translationModel = "model",
                translationCredentialId = "translation-combined",
                defaultTargetLanguage = "zh-CN",
            )

        val task =
            repository.submit(
                GenerationRequest(
                    contentId = ContentId,
                    input = GenerationInput.AudioTrack("audio:0"),
                    output = GenerationOutput.Translated("zh", TextPresentationMode.TargetOnly),
                    schemeId = scheme.schemeId,
                    mediaLocatorId = LocatorId,
                    activationTrackId = TrackId,
                ),
            )

        val entity = requireNotNull(database.syncTextDao().task(task.taskId))
        val snapshot = requireNotNull(database.syncTextDao().taskSnapshot(task.taskId))
        assertEquals(GenerationTaskKind.SourceGeneration.name, entity.kind)
        assertEquals("zh-CN", entity.targetLanguage)
        assertEquals(LocatorId, snapshot.mediaLocatorId)
        assertEquals("zh-CN", snapshot.targetLanguage)
        assertEquals(TrackId, snapshot.activationTrackId)
        assertNull(snapshot.inputSourceRevisionId)

        repository.publishResult(
            taskId = task.taskId,
            sourceLanguage = "en",
            targetLanguage = null,
            presentationMode = TextPresentationMode.TargetOnly,
            sourceCues = CueIdNormalizer.normalize(listOf(SubtitleCue(0L, 1_000L, "recognized"))),
            targetCues = null,
            completeTask = false,
            completeness = GeneratedResultCompleteness.Final,
        )
        repository.blockTask(
            taskId = task.taskId,
            category = GenerationErrorCategory.InvalidResponse,
            message = "translation failed",
            blockScheme = false,
        )

        val primary = database.contentDao().textSelections(ContentId).single { it.slot == TextTrackSlot.Primary.name }
        assertEquals(TrackId, primary.selectedTrackId)
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

        assertTrue(repository.hasSchedulableTasks())
        scheduler.reset()
        repository.pauseTask(task.taskId)
        assertEquals(GenerationTaskState.Paused.name, database.syncTextDao().task(task.taskId)?.state)
        assertFalse(repository.hasSchedulableTasks())
        assertEquals(listOf(task.taskId), scheduler.cancellations)
        assertEquals(1, scheduler.schedules)
        repository.markRunning(task.taskId, GenerationStage.RecognizingSpeech, 0.5f)
        assertEquals(GenerationTaskState.Paused.name, database.syncTextDao().task(task.taskId)?.state)
        scheduler.reset()
        repository.resumeTask(task.taskId)
        assertEquals(GenerationTaskState.Waiting.name, database.syncTextDao().task(task.taskId)?.state)
        assertTrue(repository.hasSchedulableTasks())
        assertEquals(listOf(task.taskId), scheduler.cancellations)
        assertEquals(1, scheduler.schedules)
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
        scheduler.reset()
        repository.setPauseAll(true)
        assertTrue(database.syncTextDao().queueControl()?.pauseAll == true)
        assertFalse(repository.hasSchedulableTasks())
        assertEquals(listOf("sync-text-queue"), scheduler.cancellations)
        assertEquals(0, scheduler.schedules)
        assertNull(repository.nextRunnableTask())
        scheduler.reset()
        repository.setPauseAll(false)
        assertEquals(listOf("sync-text-queue"), scheduler.cancellations)
        assertEquals(1, scheduler.schedules)
        assertEquals(task.taskId, repository.nextRunnableTask()?.taskId)

        scheduler.reset()
        repository.deleteTask(task.taskId)
        val deleted = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.Deleted.name, deleted.state)
        assertNull(deleted.activeDedupeKey)
        assertEquals(listOf(task.taskId), scheduler.cancellations)
        assertEquals(1, scheduler.schedules)
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
        val name = if (id == "system.v2") "translation_system_v2.md" else "translation_style_zh_cn_v3.md"
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

private class RecordingSyncTextScheduler : SyncTextScheduler {
    var schedules = 0
        private set
    val cancellations = mutableListOf<String>()

    override fun schedule() {
        schedules += 1
    }

    override fun cancel(taskId: String) {
        cancellations += taskId
    }

    fun reset() {
        schedules = 0
        cancellations.clear()
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
    fun `silent tail window completes without cancelling the speaking main window`() = runBlocking {
        insertAudioContent(durationMs = 303_000L)
        repository.saveCredential("asr-silent-tail", "ASR", CredentialPurpose.Asr, "secret")
        val scheme =
            repository.createScheme(
                name = "ASR silent tail",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-silent-tail",
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
        val engine = SilentTailAsrEngine()

        assertTrue(runner(engine).runUntilIdle())

        val completedTask = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(
            "task failed: ${completedTask.errorCategory}: ${completedTask.errorMessage}",
            GenerationTaskState.Completed.name,
            completedTask.state,
        )
        val windows = database.syncTextDao().asrWindows(completedTask.currentAttemptId).sortedBy { it.ordinal }
        val final = requireNotNull(repository.currentSourceResult(RunnerContentId))
        val published = revisionReader.read(final.sourceRevisionId)
        assertEquals(listOf("main speech"), published.cues.map { it.text })
        assertEquals(listOf(1, 0), windows.map { it.cueCount })
        assertTrue(windows.all { it.state == "Completed" && it.transcriptArtifactId != null })
        assertEquals(1, engine.calls(0))
        assertEquals(1, engine.calls(1))
    }

    @Test
    fun `runner continues from audio recognition into translation in one task`() = runBlocking {
        insertAudioContent(durationMs = 100_000L)
        repository.saveCredential("asr-combined", "ASR", CredentialPurpose.Asr, "asr-secret")
        repository.saveCredential(
            "translation-combined",
            "Translation",
            CredentialPurpose.Translation,
            "translation-secret",
        )
        val scheme =
            repository.createScheme(
                name = "Combined",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-combined",
                translationProtocol = TranslationProtocol.Responses,
                translationEndpoint = "https://example.test/v1/responses",
                translationModel = "model",
                translationCredentialId = "translation-combined",
                defaultTargetLanguage = "zh-CN",
                isDefault = true,
            )
        val task =
            repository.submit(
                GenerationRequest(
                    contentId = RunnerContentId,
                    input = GenerationInput.AudioTrack("audio:0"),
                    output = GenerationOutput.Translated("zh", TextPresentationMode.TargetOnly),
                    schemeId = scheme.schemeId,
                    mediaLocatorId = RunnerLocatorId,
                ),
            )
        val adapter = SuccessfulTranslationAdapter()

        assertTrue(runner(RecoveringAsrEngine(), translationAdapter = adapter).runUntilIdle())

        assertEquals(GenerationTaskState.Completed.name, database.syncTextDao().task(task.taskId)?.state)
        assertEquals(1, database.syncTextDao().generatedSlotsForTarget(RunnerContentId, null).size)
        val translatedSlot = database.syncTextDao().generatedSlotsForTarget(RunnerContentId, "zh-CN").single()
        val presentation = requireNotNull(repository.loadPresentation(translatedSlot.slotId))
        assertEquals("zh-CN", presentation.result.targetLanguage)
        assertEquals("translated", presentation.targetCues?.single()?.text)
        assertEquals(2, adapter.calls.get())
        assertEquals(TranslationReasoningLevel.Low, adapter.preparationRequest?.reasoningLevel)
        assertTrue(adapter.preparationRequest?.userPrompt?.contains("TIMELINE") == true)
        assertTrue(adapter.lastRequest?.userPrompt?.contains("window-0") == true)
        assertTrue(adapter.lastRequest?.userPrompt?.contains("content_mode: dialogue") == true)
        val attemptId = requireNotNull(database.syncTextDao().task(task.taskId)).currentAttemptId
        assertEquals(
            SourcePreparationCheckpointState.Completed.name,
            database.syncTextDao().sourcePreparation(attemptId)?.state,
        )
    }

    @Test
    fun `asr only preparation applies deterministic cleanup without a translation model`() = runBlocking {
        insertAudioContent(durationMs = 100_000L)
        repository.saveCredential("asr-cleanup", "ASR", CredentialPurpose.Asr, "secret")
        val scheme =
            repository.createScheme(
                name = "ASR cleanup",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-cleanup",
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

        assertTrue(runner(EventAsrEngine()).runUntilIdle())

        val taskEntity = requireNotNull(database.syncTextDao().task(task.taskId))
        val final = requireNotNull(repository.currentSourceResult(RunnerContentId))
        val published = revisionReader.read(final.sourceRevisionId)
        assertEquals(GenerationTaskState.Completed.name, taskEntity.state)
        assertEquals(GeneratedResultCompleteness.Final, final.completeness)
        assertEquals(listOf("今日は話しましょう。", "ご視聴ありがとうございました。"), published.cues.map { it.text })
        assertEquals(
            SourcePreparationCheckpointState.SkippedNoModel.name,
            database.syncTextDao().sourcePreparation(taskEntity.currentAttemptId)?.state,
        )
        assertEquals(
            SourcePreparationPolicyRegistry.PolicyVersion,
            database.syncTextDao().attempt(taskEntity.currentAttemptId)?.sourcePreparationPolicyVersion,
        )
        assertEquals(
            SourcePreparationPolicyRegistry.PromptSha256,
            database.syncTextDao().attempt(taskEntity.currentAttemptId)?.sourcePreparationPromptSha256,
        )
    }

    @Test
    fun `explicit simplified Chinese is frozen sent to asr and normalized before publication`() = runBlocking {
        insertAudioContent(durationMs = 100_000L)
        repository.saveCredential("asr-zh-cn", "ASR", CredentialPurpose.Asr, "secret")
        val scheme =
            repository.createScheme(
                name = "ASR simplified Chinese",
                asrProfile = AsrProfile.OpenRouterWhisperV1,
                asrCredentialId = "asr-zh-cn",
                isDefault = true,
            )
        val task =
            repository.submit(
                GenerationRequest(
                    contentId = RunnerContentId,
                    input = GenerationInput.AudioTrack("audio:0"),
                    output = GenerationOutput.SourceOnly,
                    schemeId = scheme.schemeId,
                    sourceLanguage = "zh-CN",
                ),
            )
        val engine = TraditionalChineseAsrEngine()

        assertTrue(runner(engine).runUntilIdle())

        val taskEntity = requireNotNull(database.syncTextDao().task(task.taskId))
        val snapshot = requireNotNull(database.syncTextDao().taskSnapshot(task.taskId))
        val final = requireNotNull(repository.currentSourceResult(RunnerContentId))
        val published = revisionReader.read(final.sourceRevisionId)
        assertEquals(GenerationTaskState.Completed.name, taskEntity.state)
        assertEquals("zh-CN", snapshot.sourceLanguage)
        assertEquals("zh-CN", engine.requestedSourceLanguage)
        assertEquals("zh-CN", final.sourceLanguage)
        assertEquals(listOf("生命不息，奋斗不止"), published.cues.map { it.text })
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
        translationAdapter: TranslationProtocolAdapter? = null,
    ) =
        SyncTextTaskRunner(
            database = database,
            repository = repository,
            policies = TranslationPolicyRegistry.forTesting(::promptBytes),
            sourcePreparationPolicies =
                SourcePreparationPolicyRegistry.forTesting {
                    File("src/main/res/raw/source_preparation_system_v2.md").readBytes()
                },
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
            translation =
                TranslationNetworkGateway(
                    registry = TranslationAdapterRegistry(listOfNotNull(translationAdapter)),
                    streamResponses = false,
                ),
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
        val name = if (id == "system.v2") "translation_system_v2.md" else "translation_style_zh_cn_v3.md"
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

private class SilentTailAsrEngine : AsrEngine {
    private val callCounts = ConcurrentHashMap<Int, Int>()
    private val mainStarted = CompletableDeferred<Unit>()
    private val tailCompleted = CompletableDeferred<Unit>()

    override suspend fun transcribe(request: AsrRequest): AsrWindowResult {
        val ordinal = request.window.ordinal
        callCounts.merge(ordinal, 1, Int::plus)
        val segments =
            if (ordinal == 0) {
                mainStarted.complete(Unit)
                tailCompleted.await()
                listOf(AsrTimestampedToken(10L, 1_000L, "main speech"))
            } else {
                mainStarted.await()
                tailCompleted.complete(Unit)
                emptyList()
            }
        return AsrWindowResult(
            segments = segments,
            detectedLanguage = "en",
            usage = UsageRecord(totalTokens = 1L),
            rawResponse = if (segments.isEmpty()) "{\"segments\":[]}".toByteArray() else byteArrayOf(1),
            httpStatus = 200,
            requestBytes = 1L,
            responseBytes = 1L,
            requestId = "response-$ordinal",
        )
    }

    fun calls(ordinal: Int): Int = callCounts[ordinal] ?: 0
}

private class SuccessfulTranslationAdapter : TranslationProtocolAdapter {
    override val protocol = TranslationProtocol.Responses
    val calls = AtomicInteger(0)
    var preparationRequest: TranslationProviderRequest? = null
        private set
    var lastRequest: TranslationProviderRequest? = null
        private set

    override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
        calls.incrementAndGet()
        if (request.systemPrompt.contains("prepare ASR source subtitles")) {
            preparationRequest = request
            val text =
                """
                {
                  "patches": [],
                  "source_context": {
                    "style_profile": {
                      "content_mode": "dialogue",
                      "register": "casual",
                      "politeness": null,
                      "utterance_shape": "short turns",
                      "preserve_traits": []
                    },
                    "entity_mentions": [],
                    "alias_clusters": [],
                    "addressing_functions": []
                  }
                }
                """.trimIndent()
            return response(request, text, "source-preparation-${calls.get()}")
        }
        lastRequest = request
        val translateOnly =
            request.userPrompt
                .substringAfter("TRANSLATE_ONLY\n")
                .substringBefore("\nCONTEXT_AFTER")
        val ids =
            translateOnly.lineSequence().mapNotNull { line ->
                Regex("""^\[([^]]+)]""").find(line)?.groupValues?.get(1)
            }.toList()
        val text = ids.joinToString("\n") { id -> "[$id] translated" }
        return response(request, text, "translation-${calls.get()}")
    }

    private fun response(
        request: TranslationProviderRequest,
        text: String,
        requestId: String,
    ) =
        TranslationProviderResponse(
            text = text,
            usage = UsageRecord(totalTokens = 1L),
            rawResponse = text.toByteArray(),
            httpStatus = 200,
            requestBytes = request.userPrompt.length.toLong(),
            responseBytes = text.length.toLong(),
            requestId = requestId,
            endpoint = request.endpoint,
        )
}

private class EventAsrEngine : AsrEngine {
    override suspend fun transcribe(request: AsrRequest): AsrWindowResult =
        AsrWindowResult(
            segments =
                listOf(
                    AsrTimestampedToken(100L, 800L, "*Sigh*"),
                    AsrTimestampedToken(1_000L, 2_000L, "今日は話しましょう。"),
                    AsrTimestampedToken(2_500L, 3_500L, "ご視聴ありがとうございました。"),
                ),
            detectedLanguage = "ja",
            usage = UsageRecord(totalTokens = 1L),
            rawResponse = byteArrayOf(1),
            httpStatus = 200,
            requestBytes = 1L,
            responseBytes = 1L,
            requestId = "event-asr",
        )
}

private class TraditionalChineseAsrEngine : AsrEngine {
    var requestedSourceLanguage: String? = null
        private set

    override suspend fun transcribe(request: AsrRequest): AsrWindowResult {
        requestedSourceLanguage = request.sourceLanguage
        return AsrWindowResult(
            segments = listOf(AsrTimestampedToken(100L, 1_000L, "生命不息，奮鬥不止")),
            detectedLanguage = "zh-Hant",
            usage = UsageRecord(totalTokens = 1L),
            rawResponse = byteArrayOf(1),
            httpStatus = 200,
            requestBytes = 1L,
            responseBytes = 1L,
            requestId = "traditional-chinese-asr",
        )
    }
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
                is TextArtifactPayload.Preparation -> payload.value.patches.size
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
