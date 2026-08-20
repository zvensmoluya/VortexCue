package io.github.zvensmoluya.vortexcue.data

import android.app.Application
import android.net.Uri
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.stableTimedTextTrackId
import io.github.zvensmoluya.vortexcue.data.db.ActiveMediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentDispositionEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentFolderEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentFolderMembershipEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentIdentityEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentDraftEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentPlaybackSessionEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentQueueEntryEntity
import io.github.zvensmoluya.vortexcue.data.db.MediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.RecentEntryEntity
import io.github.zvensmoluya.vortexcue.data.db.TextSelectionEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackRoleEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ContentMergeTest {
    private lateinit var database: VortexDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun identityCollisionKeepsPrimaryAndMergesEveryRelationship() = runBlocking {
        val dao = database.contentDao()
        val playbackDao = database.playbackDao()
        seedContent("primary", "locator-primary", "identity-primary", 25L)
        seedContent("duplicate", "locator-duplicate", "identity-duplicate", 90L)
        seedContent("other", "locator-other", "identity-other", 0L)
        dao.upsertDisposition(ContentDispositionEntity("primary", false, 2L))
        dao.upsertDisposition(ContentDispositionEntity("duplicate", true, 3L))
        dao.upsertRecent(RecentEntryEntity("primary", 10L))
        dao.upsertRecent(RecentEntryEntity("duplicate", 20L))
        dao.upsertContentFolder(ContentFolderEntity("folder", "Test", 1L, 1L))
        dao.upsertContentFolderMembership(ContentFolderMembershipEntity("duplicate", "folder", 2L))
        dao.upsertDraft(CurrentDraftEntity(contentId = "duplicate", updatedAtMs = 2L))

        val primaryTrackId = stableTimedTextTrackId("primary", TimedTextOrigin.Sidecar, SharedTextIdentity)
        val duplicateTrackId = stableTimedTextTrackId("duplicate", TimedTextOrigin.Sidecar, SharedTextIdentity)
        dao.upsertTextTrack(textTrack(primaryTrackId, "primary", "UserSelected"))
        dao.upsertTextTrack(textTrack(duplicateTrackId, "duplicate", "ExactStem"))
        dao.insertTextRoles(
            listOf(
                TimedTextTrackRoleEntity(primaryTrackId, "Default"),
                TimedTextTrackRoleEntity(duplicateTrackId, "Sdh"),
            ),
        )
        dao.upsertTextSelection(TextSelectionEntity("primary", "Primary", primaryTrackId, "Manual"))

        playbackDao.upsertQueueEntries(
            listOf(
                CurrentQueueEntryEntity("primary", 0, 1L),
                CurrentQueueEntryEntity("duplicate", 1, 1L),
                CurrentQueueEntryEntity("other", 2, 1L),
            ),
        )
        playbackDao.upsertSession(
            CurrentPlaybackSessionEntity(
                currentContentId = "duplicate",
                queueRevision = 7L,
                updatedAtMs = 1L,
            ),
        )

        val repository = ContentRepository(database, nowMs = { 100L })
        val result = repository.mergeIdentityCollision("primary", "duplicate")

        assertEquals("primary", result)
        assertNull(dao.content("duplicate"))
        assertEquals("primary", dao.redirectTarget("duplicate"))
        assertEquals("primary", dao.contentIdForIdentity("identity-duplicate"))
        assertEquals("primary", dao.locator("locator-duplicate")?.contentId)
        assertEquals(25L, dao.content("primary")?.subtitleOffsetMs)
        assertEquals(true, dao.disposition("primary")?.isCollected)
        assertEquals(20L, dao.recent("primary")?.lastOpenedAtMs)
        assertEquals("folder", dao.contentFolderMembership("primary")?.folderId)
        assertEquals("primary", dao.draft()?.contentId)
        assertEquals(listOf("primary", "other"), playbackDao.queueEntries().map { it.contentId })
        assertEquals("primary", playbackDao.session()?.currentContentId)
        assertEquals(8L, playbackDao.session()?.queueRevision)
        assertEquals(listOf(primaryTrackId), dao.textTracks("primary").map { it.trackId })
        assertEquals(setOf("Default", "Sdh"), dao.textRoles("primary").map { it.role }.toSet())
        assertEquals(
            primaryTrackId,
            dao.textSelections("primary").first { it.slot == "Primary" }.selectedTrackId,
        )
    }

    @Test
    fun strongIdentityReuseAndExplicitRelinkKeepTheRequestedContentId() = runBlocking {
        val repository = ContentRepository(database, nowMs = { 100L })
        val primaryMedia =
            MediaSourceRef(
                uri = Uri.parse("content://media/primary-relink"),
                displayName = "primary.mp3",
                mimeType = "audio/mpeg",
                title = "Primary",
            )
        val duplicateMedia =
            MediaSourceRef(
                uri = Uri.parse("content://media/duplicate-relink"),
                displayName = "duplicate.mp3",
                mimeType = "audio/mpeg",
                title = "Duplicate",
            )
        val primaryId = repository.resolveOrCreate(primaryMedia)
        val reusedId = repository.resolveOrCreate(primaryMedia.copy(title = "Updated primary"))
        val duplicateId = repository.resolveOrCreate(duplicateMedia)

        val canonical = repository.relink(primaryId, duplicateMedia.copy(title = "Relinked"))

        val dao = database.contentDao()
        assertEquals(primaryId, reusedId)
        assertEquals(primaryId, canonical)
        assertNull(dao.content(duplicateId))
        assertEquals(primaryId, dao.redirectTarget(duplicateId))
        assertEquals(primaryId, repository.contentIdFor(duplicateMedia))
        assertEquals("Relinked", repository.summary(primaryId)?.media?.title)
    }

    @Test
    fun failedAndCancelledScansKeepThePreviousGenerationUntilSuccess() = runBlocking {
        val clock = AtomicLong(10L)
        val repository = ContentRepository(database, nowMs = clock::incrementAndGet)
        val descriptor =
            SourceScanDescriptor(
                sourceId = "folder:test",
                kind = LibrarySourceKind.Folder,
                rootUri = "content://tree/test",
                displayName = "Test",
            )
        val previous =
            MediaSourceRef(
                uri = Uri.parse("content://tree/test/previous.mp3"),
                displayName = "previous.mp3",
                mimeType = "audio/mpeg",
                sourceId = descriptor.sourceId,
            )
        val replacement =
            MediaSourceRef(
                uri = Uri.parse("content://tree/test/replacement.mp3"),
                displayName = "replacement.mp3",
                mimeType = "audio/mpeg",
                sourceId = descriptor.sourceId,
            )
        repository.applySuccessfulScan(descriptor, listOf(previous), emptyList())
        val previousId = requireNotNull(repository.contentIdFor(previous))
        repository.ignore(previousId)

        repository.recordSourceScanFailure(descriptor, "unavailable", hasAccess = false)
        assertEquals(1L, database.contentDao().source(descriptor.sourceId)?.generation)
        assertEquals("Available", repository.summary(previousId)?.availability?.name)

        val calls = AtomicInteger()
        val cancellingRepository =
            ContentRepository(
                database = database,
                nowMs = {
                    if (calls.incrementAndGet() == 2) throw CancellationException("cancel scan")
                    100L
                },
            )
        val cancellation =
            runCatching {
                cancellingRepository.applySuccessfulScan(
                    descriptor,
                    listOf(replacement),
                    emptyList(),
                )
            }

        assertTrue(cancellation.exceptionOrNull() is CancellationException)
        assertEquals(1L, database.contentDao().source(descriptor.sourceId)?.generation)
        assertNull(repository.contentIdFor(replacement))
        assertEquals("Available", repository.summary(previousId)?.availability?.name)

        repository.applySuccessfulScan(descriptor, listOf(replacement), emptyList())
        assertEquals(2L, database.contentDao().source(descriptor.sourceId)?.generation)
        assertEquals("Unavailable", repository.summary(previousId)?.availability?.name)
        val replacementId = requireNotNull(repository.contentIdFor(replacement))
        assertEquals("Available", repository.summary(replacementId)?.availability?.name)

        repository.removeSource(descriptor.sourceId)
        assertEquals("Unavailable", repository.summary(previousId)?.availability?.name)
        assertNull(repository.summary(replacementId))
    }

    private suspend fun seedContent(
        contentId: String,
        locatorId: String,
        identityKey: String,
        offsetMs: Long,
    ) {
        val dao = database.contentDao()
        dao.upsertContent(ContentEntity(contentId, createdAtMs = 1L, subtitleOffsetMs = offsetMs))
        dao.upsertIdentity(ContentIdentityEntity(identityKey, contentId))
        dao.upsertLocator(
            MediaLocatorEntity(
                locatorId = locatorId,
                contentId = contentId,
                identityKey = identityKey,
                sourceId = null,
                uriValue = "content://media/$contentId",
                displayName = "$contentId.mp3",
                mimeType = "audio/mpeg",
                title = contentId,
                artist = null,
                album = null,
                durationMs = 1_000L,
                folder = null,
                dateAddedSeconds = 1L,
                origin = "OpenDocument",
                kind = "Audio",
                sizeBytes = 1L,
                videoWidth = 0,
                videoHeight = 0,
                availability = "Available",
                seenGeneration = 0L,
                updatedAtMs = 1L,
            ),
        )
        dao.upsertActiveLocator(ActiveMediaLocatorEntity(contentId, locatorId))
    }

    private fun textTrack(
        trackId: String,
        contentId: String,
        matchBasis: String,
    ) =
        TimedTextTrackEntity(
            trackId = trackId,
            contentId = contentId,
            origin = "Embedded",
            locatorIdentity = SharedTextIdentity,
            revisionId = null,
            embeddedTrackId = "embedded-shared",
            generatedSlotId = null,
            displayName = "shared.srt",
            mimeType = "application/x-subrip",
            format = "srt",
            language = "en",
            label = null,
            matchBasis = matchBasis,
            readiness = "Ready",
            failureMessage = null,
        )

    private companion object {
        const val SharedTextIdentity = "embedded:embedded-shared"
    }
}
