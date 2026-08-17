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
import java.io.File
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

class SyncTextRepositoryTest {
    private lateinit var database: VortexDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var assets: MemoryGeneratedTextAssetStore
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
        assets = MemoryGeneratedTextAssetStore()
        credentials = MemoryCredentialStore()
        repository =
            SyncTextRepository(
                database = database,
                assets = assets,
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
        val request =
            GenerationRequest(
                contentId = ContentId,
                input = GenerationInput.ExistingTrack(TrackId),
                output = GenerationOutput.Translated("zh", TextPresentationMode.SourceTarget),
                schemeId = scheme.schemeId,
            )

        val first = repository.submit(request)
        val duplicate = repository.submit(request)
        assertEquals(first.taskId, duplicate.taskId)
        assertEquals("zh-CN", database.syncTextDao().taskSnapshot(first.taskId)?.targetLanguage)

        repository.saveScheme(scheme.copy(translationModel = "model-v2", translationUserAgent = "PrivateGatewayClient/5.0"))
        val firstTask = requireNotNull(database.syncTextDao().task(first.taskId))
        assertEquals("model-v1", database.syncTextDao().attempt(firstTask.currentAttemptId)?.translationModel)
        assertEquals("PrivateGatewayClient/4.1", database.syncTextDao().attempt(firstTask.currentAttemptId)?.translationUserAgent)

        val source = CueIdNormalizer.normalize(listOf(SubtitleCue(0L, 1_000L, "source")))
        val target = source.map { it.copy(text = "target") }
        val firstResult =
            repository.publishResult(
                taskId = first.taskId,
                sourceLanguage = "en",
                targetLanguage = "zh-CN",
                presentationMode = TextPresentationMode.SourceTarget,
                sourceCues = source,
                targetCues = target,
                usage = UsageRecord(totalTokens = 12L, costUsd = "0.01"),
                completeTask = true,
            )
        assertEquals(firstResult.resultId, database.syncTextDao().currentGeneratedResult(firstResult.slotId)?.resultId)

        val regeneration = repository.submit(request)
        assertFalse(regeneration.taskId == first.taskId)
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
        assertFalse(assets.contains(firstResult.sourceAssetFileName))
        assertEquals(TextPresentationMode.TargetOnly, repository.loadPresentation(secondResult.slotId)?.result?.presentationMode)
        assertEquals(TranslationPolicyRegistry.OriginRevision, secondResult.policyRevision)
        assertEquals(
            "bbf6c688ce3df8953fe876ade60a69c28e837234cb3061694bab1467f642891f",
            secondResult.systemPromptSha256,
        )
        assertEquals(
            "09de51ae2e3709c8e506a4d9a82c9ce3bc9b16a13d0f683a928aad3a2d91e07b",
            secondResult.stylePromptSha256,
        )
        val generatedTrack =
            requireNotNull(
                database.contentDao().textTracks(ContentId)
                    .firstOrNull { it.generatedSlotId == secondResult.slotId },
            )
        val reuseTask =
            repository.submit(
                GenerationRequest(
                    ContentId,
                    GenerationInput.ExistingTrack(generatedTrack.trackId),
                    GenerationOutput.Translated("ja", TextPresentationMode.TargetOnly),
                    scheme.schemeId,
                ),
            )
        assertEquals(secondResult.resultId, database.syncTextDao().task(reuseTask.taskId)?.sourceResultId)
        val deleteFailure = runCatching { repository.deleteCredential("asr-key") }.exceptionOrNull()
        assertTrue(deleteFailure is CredentialStillReferencedException)
        val schemeDeleteFailure = runCatching { repository.deleteScheme(scheme.schemeId) }.exceptionOrNull()
        assertTrue(schemeDeleteFailure is SchemeStillReferencedException)

        database.syncTextDao().pruneTerminalTasks(Long.MAX_VALUE)
        assertNull(database.syncTextDao().task(regeneration.taskId))
        assertEquals(secondResult.resultId, database.syncTextDao().currentGeneratedResult(secondResult.slotId)?.resultId)
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
        repository.resumeTask(task.taskId)
        assertEquals(GenerationTaskState.Waiting.name, database.syncTextDao().task(task.taskId)?.state)
        repository.setPauseAll(true)
        assertTrue(database.syncTextDao().queueControl()?.pauseAll == true)
        assertNull(repository.nextRunnableTask())
        repository.setPauseAll(false)
        assertEquals(task.taskId, repository.nextRunnableTask()?.taskId)

        repository.deleteTask(task.taskId)
        val deleted = requireNotNull(database.syncTextDao().task(task.taskId))
        assertEquals(GenerationTaskState.Deleted.name, deleted.state)
        assertNull(deleted.activeDedupeKey)
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
                locatorIdentity = "uri:content://text/item",
                uriValue = "content://text/item",
                embeddedTrackId = null,
                generatedSlotId = null,
                displayName = "item.vtt",
                mimeType = "text/vtt",
                cachedFileName = null,
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

private class MemoryGeneratedTextAssetStore : GeneratedTextAssetStore {
    private val cues = mutableMapOf<String, List<SubtitleCue>>()
    private val bytes = mutableMapOf<String, ByteArray>()

    override suspend fun writeResult(
        resultId: String,
        sourceCues: List<SubtitleCue>,
        targetCues: List<SubtitleCue>?,
    ): StoredGeneratedAssets {
        val sourceName = "$resultId.source.vtt"
        val targetName = targetCues?.let { "$resultId.target.vtt" }
        cues[sourceName] = sourceCues
        targetName?.let { cues[it] = requireNotNull(targetCues) }
        return StoredGeneratedAssets(
            source = StoredTextAsset(sourceName, "source-$resultId", 1L),
            target = targetName?.let { StoredTextAsset(it, "target-$resultId", 1L) },
            cueCount = sourceCues.size,
        )
    }

    override suspend fun readTextAsset(fileName: String): List<SubtitleCue> = requireNotNull(cues[fileName])

    override suspend fun writeCheckpoint(fileName: String, bytes: ByteArray): String {
        this.bytes[fileName] = bytes
        return fileName
    }

    override suspend fun readCheckpoint(fileName: String): ByteArray = requireNotNull(bytes[fileName])

    override suspend fun writeRawResponse(fileName: String, bytes: ByteArray): String =
        writeCheckpoint(fileName, bytes)

    override suspend fun delete(fileName: String) {
        cues.remove(fileName)
        bytes.remove(fileName)
    }

    override suspend fun pruneExcept(retainedFileNames: Set<String>) {
        cues.keys.retainAll(retainedFileNames)
        bytes.keys.retainAll(retainedFileNames)
    }

    fun contains(fileName: String): Boolean = fileName in cues || fileName in bytes
}
