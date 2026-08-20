package io.github.zvensmoluya.vortexcue.text

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
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
}
