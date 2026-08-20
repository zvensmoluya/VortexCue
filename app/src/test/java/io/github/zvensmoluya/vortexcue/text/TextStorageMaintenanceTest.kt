package io.github.zvensmoluya.vortexcue.text

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.GeneratedTextResultEntity
import io.github.zvensmoluya.vortexcue.data.db.GeneratedTextSlotEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class TextStorageMaintenanceTest {
    private lateinit var database: VortexDatabase
    private lateinit var artifactStore: TestTextArtifactStore
    private lateinit var storage: TextStorageRepository
    private lateinit var maintenance: TextStorageMaintenance
    private var clock = 10_000L

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        artifactStore = TestTextArtifactStore()
        storage = TextStorageRepository(database, artifactStore, nowMs = { clock })
        maintenance =
            TextStorageMaintenance(
                context = RuntimeEnvironment.getApplication(),
                database = database,
                storage = storage,
                store = artifactStore,
                nowMs = { clock },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `unreachable artifact requires a full grace period before deletion`() = runBlocking {
        val timeline = CanonicalTextNormalizer.normalizeGenerated(listOf(SubtitleCue(0L, 1_000L, "unused")))
        val artifact = storage.publishTimelineArtifact(TextArtifactKind.FinalTimeline, timeline)
        storage.registerArtifact(artifact)

        maintenance.recoverAuditAndCollect()
        assertEquals(clock, database.textStorageDao().artifact(artifact.artifactId)?.gcCandidateSinceMs)
        assertTrue(artifactStore.contains(artifact.artifactId))

        clock += TextStorageMaintenance.GcGraceMs - 1L
        maintenance.recoverAuditAndCollect()
        assertNotNull(database.textStorageDao().artifact(artifact.artifactId))

        clock += 1L
        maintenance.recoverAuditAndCollect()
        assertNull(database.textStorageDao().artifact(artifact.artifactId))
        assertFalse(artifactStore.contains(artifact.artifactId))
    }

    @Test
    fun `corrupt reachable revision marks its track missing and never collects it`() = runBlocking {
        val contentId = "content"
        database.contentDao().upsertContent(ContentEntity(contentId, 1L))
        val timeline = CanonicalTextNormalizer.normalizeGenerated(listOf(SubtitleCue(0L, 1_000L, "owned")))
        val artifact = storage.publishTimelineArtifact(TextArtifactKind.FinalTimeline, timeline)
        val revision =
            storage.createRevision(
                contentId = contentId,
                role = TimelineRole.Source,
                completeness = TimelineCompleteness.Final,
                artifacts = listOf(RevisionArtifactRole.Timeline to artifact),
                contentHash = canonicalContentHash(timeline),
                cueCount = 1,
                coveredEndMs = 1_000L,
            )
        database.contentDao().upsertTextTrack(
            TimedTextTrackEntity(
                trackId = "track",
                contentId = contentId,
                origin = "Sidecar",
                locatorIdentity = "revision:${revision.revisionId}",
                revisionId = revision.revisionId,
                embeddedTrackId = null,
                generatedSlotId = null,
                displayName = "owned.srt",
                mimeType = "application/x-subrip",
                format = "srt",
                language = null,
                label = null,
                matchBasis = "UserSelected",
                readiness = "Ready",
                failureMessage = null,
            ),
        )
        artifactStore.corrupt(artifact.artifactId)

        maintenance.recoverAuditAndCollect()

        val track = database.contentDao().textTracks(contentId).single()
        assertEquals("MissingArtifact", track.readiness)
        assertTrue(track.failureMessage?.contains("损坏") == true)
        assertNull(database.textStorageDao().artifact(artifact.artifactId)?.gcCandidateSinceMs)
    }

    @Test
    fun `obsolete generated results stop retaining revisions after replacement`() = runBlocking {
        database.contentDao().upsertContent(ContentEntity("content", 1L))
        val oldTimeline = CanonicalTextNormalizer.normalizeGenerated(listOf(SubtitleCue(0L, 1_000L, "old")))
        val oldArtifact = storage.publishTimelineArtifact(TextArtifactKind.FinalTimeline, oldTimeline)
        val oldRevision =
            storage.createRevision(
                contentId = "content",
                role = TimelineRole.Source,
                completeness = TimelineCompleteness.Final,
                artifacts = listOf(RevisionArtifactRole.Timeline to oldArtifact),
                contentHash = canonicalContentHash(oldTimeline),
                cueCount = 1,
                coveredEndMs = 1_000L,
            )
        val currentTimeline = CanonicalTextNormalizer.normalizeGenerated(listOf(SubtitleCue(0L, 1_000L, "current")))
        val currentArtifact = storage.publishTimelineArtifact(TextArtifactKind.FinalTimeline, currentTimeline)
        val currentRevision =
            storage.createRevision(
                contentId = "content",
                role = TimelineRole.Source,
                completeness = TimelineCompleteness.Final,
                artifacts = listOf(RevisionArtifactRole.Timeline to currentArtifact),
                contentHash = canonicalContentHash(currentTimeline),
                cueCount = 1,
                coveredEndMs = 1_000L,
            )
        val slotId = "generated-slot"
        database.syncTextDao().upsertGeneratedSlot(
            GeneratedTextSlotEntity(
                slotId = slotId,
                contentId = "content",
                targetKey = "source",
                targetLanguage = null,
                presentationMode = "SourceOnly",
                currentResultId = "current-result",
                createdAtMs = clock,
                updatedAtMs = clock,
            ),
        )
        fun result(resultId: String, revision: StoredTimelineRevision) =
            GeneratedTextResultEntity(
                resultId = resultId,
                slotId = slotId,
                contentId = "content",
                taskId = null,
                attemptId = null,
                sourceLanguage = "en",
                targetLanguage = null,
                presentationMode = "SourceOnly",
                sourceRevisionId = revision.revisionId,
                targetRevisionId = null,
                sourceSha256 = revision.contentHash,
                targetSha256 = null,
                cueCount = revision.cueCount,
                completeness = "Final",
                coveredEndMs = revision.coveredEndMs,
                completedWindowCount = null,
                totalWindowCount = null,
                schemeId = "scheme",
                model = null,
                policyId = null,
                policyVersion = null,
                policyRevision = null,
                systemPromptSha256 = null,
                stylePromptSha256 = null,
                inputTokens = null,
                outputTokens = null,
                totalTokens = null,
                costUsd = null,
                createdAtMs = clock,
            )

        database.syncTextDao().insertGeneratedResult(result("old-result", oldRevision))
        database.syncTextDao().insertGeneratedResult(result("current-result", currentRevision))

        maintenance.recoverAuditAndCollect()

        assertNull(database.syncTextDao().generatedResult("old-result"))
        assertNotNull(database.syncTextDao().generatedResult("current-result"))
        assertEquals(clock, database.textStorageDao().artifact(oldArtifact.artifactId)?.gcCandidateSinceMs)

        clock += TextStorageMaintenance.GcGraceMs
        maintenance.recoverAuditAndCollect()

        assertNull(database.textStorageDao().artifact(oldArtifact.artifactId))
        assertNull(database.textStorageDao().revision(oldRevision.revisionId))
        assertNotNull(database.textStorageDao().revision(currentRevision.revisionId))
    }
}
