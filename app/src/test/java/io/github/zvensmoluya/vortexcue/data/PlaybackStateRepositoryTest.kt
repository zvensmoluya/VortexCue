package io.github.zvensmoluya.vortexcue.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.data.db.ActiveMediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentIdentityEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentRedirectEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentDraftEntity
import io.github.zvensmoluya.vortexcue.data.db.MediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.playback.toMediaItem
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class PlaybackStateRepositoryTest {
    private lateinit var database: VortexDatabase
    private lateinit var repository: PlaybackStateRepository
    private val clock = AtomicLong(100L)

    @Before
    fun setUp() = runBlocking {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val contentRepository = ContentRepository(database, nowMs = clock::incrementAndGet)
        repository = PlaybackStateRepository(database, contentRepository, nowMs = clock::incrementAndGet)
        listOf("one", "two", "three", "four").forEach { seedContent(it) }
        repository.initialize()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replaceAndAppendCanonicalizeDeduplicateAndClearDrafts() = runBlocking {
        val dao = database.contentDao()
        dao.upsertRedirect(ContentRedirectEntity("alias-one", "one", 1L))
        dao.upsertDraft(CurrentDraftEntity(contentId = "two", updatedAtMs = 1L))

        val replaced = repository.replace(listOf("alias-one", "two", "one"), currentIndex = 1, positionMs = 400L)

        assertEquals(listOf("one", "two"), replaced.queue.map { it.contentId })
        assertEquals("two", replaced.currentItem?.contentId)
        assertEquals(400L, replaced.positionMs)
        assertNull(dao.draft())
        val revision = replaced.queueRevision

        val appended = repository.append(listOf("two", "three", "three"))
        assertEquals(listOf("one", "two", "three"), appended.queue.map { it.contentId })
        assertEquals(revision + 1L, appended.queueRevision)

        dao.upsertDraft(CurrentDraftEntity(contentId = "three", updatedAtMs = 2L))
        val noOp = repository.append(listOf("three", "alias-one"))
        assertEquals(appended.queueRevision, noOp.queueRevision)
        assertEquals(appended.queue.map { it.contentId }, noOp.queue.map { it.contentId })
        assertNull(dao.draft())
    }

    @Test
    fun moveAndRemovePreserveOrCorrectCurrentItem() = runBlocking {
        val initial = repository.replace(listOf("one", "two", "three"), currentIndex = 1, positionMs = 500L)

        val moved = repository.move(fromIndex = 2, toIndex = 0)
        assertEquals(listOf("three", "one", "two"), moved.queue.map { it.contentId })
        assertEquals("two", moved.currentItem?.contentId)
        assertEquals(500L, moved.positionMs)
        assertEquals(initial.queueRevision + 1L, moved.queueRevision)

        val removed = repository.remove("two")
        assertEquals(listOf("three", "one"), removed.queue.map { it.contentId })
        assertEquals("one", removed.currentItem?.contentId)
        assertEquals(0L, removed.positionMs)
        assertEquals(0L, removed.durationMs)

        val noOp = repository.remove("four")
        assertEquals(removed.queueRevision, noOp.queueRevision)
    }

    @Test
    fun removingWorkOnlyDropsQueueEntriesOriginatingFromThatWork() = runBlocking {
        val initial =
            repository.replaceEntries(
                listOf(
                    QueueContentRef("one", originWorkId = "removed-work"),
                    QueueContentRef("two", originWorkId = "removed-work"),
                    QueueContentRef("three", originPlaylistEntryId = "manual-entry"),
                ),
                currentIndex = 1,
                positionMs = 500L,
            )

        val retained = repository.removeWork("removed-work")

        assertEquals(listOf("three"), retained.queue.map { it.contentId })
        assertEquals("three", retained.currentItem?.contentId)
        assertEquals("manual-entry", retained.currentItem?.originPlaylistEntryId)
        assertEquals(0L, retained.positionMs)
        assertEquals(initial.queueRevision + 1L, retained.queueRevision)

        val noOp = repository.removeWork("other-work")
        assertEquals(retained.queueRevision, noOp.queueRevision)
    }

    @Test
    fun clearInvalidatesOldProgressAndRecoveryRetainsPlaybackParameters() = runBlocking {
        val active = repository.replace(listOf("one", "two"), currentIndex = 0, positionMs = 200L)
        repository.updatePlaybackParameters(playbackSpeed = 1.5f, shuffleEnabled = true, repeatMode = 2)

        val cleared = repository.clear()

        assertEquals(active.queueRevision + 1L, cleared.queueRevision)
        assertEquals(emptyList<String>(), cleared.queue.map { it.contentId })
        assertNull(cleared.currentItem)
        assertFalse(
            repository.updateProgress(
                contentId = "one",
                positionMs = 900L,
                durationMs = 1_000L,
                playbackSpeed = 1f,
                expectedRevision = active.queueRevision,
            ),
        )
        val recovery = repository.recovery()
        assertEquals(1.5f, recovery.playbackSpeed)
        assertEquals(true, recovery.shuffleEnabled)
        assertEquals(2, recovery.repeatMode)
    }

    @Test
    fun currentTransitionIncrementsRevisionAndRejectsStaleEvents() = runBlocking {
        val initial = repository.replace(listOf("one", "two"), currentIndex = 0)

        val transitioned =
            repository.setCurrent(
                contentId = "two",
                positionMs = 25L,
                durationMs = 1_000L,
                expectedRevision = initial.queueRevision,
            )

        assertNotNull(transitioned)
        assertEquals("two", transitioned?.currentItem?.contentId)
        assertEquals(initial.queueRevision + 1L, transitioned?.queueRevision)

        val replaced = repository.replace(listOf("three", "four"), currentIndex = 1)
        assertNull(
            repository.setCurrent(
                contentId = "one",
                positionMs = 700L,
                durationMs = 1_000L,
                expectedRevision = initial.queueRevision,
            ),
        )
        val recovery = repository.recovery()
        assertEquals(replaced.queueRevision, recovery.queueRevision)
        assertEquals(listOf("three", "four"), recovery.queue.map { it.contentId })
        assertEquals("four", recovery.currentItem?.contentId)
    }

    @Test
    fun restoredMediaItemUsesContentIdAndActiveLocatorUri() = runBlocking {
        val item = repository.replace(listOf("one")).queue.single().toMediaItem()

        assertEquals("one", item.mediaId)
        assertEquals("content://media/one", item.localConfiguration?.uri.toString())
    }

    private suspend fun seedContent(contentId: String) {
        val dao = database.contentDao()
        val locatorId = "locator-$contentId"
        dao.upsertContent(ContentEntity(contentId, 1L))
        dao.upsertIdentity(ContentIdentityEntity("identity-$contentId", contentId))
        dao.upsertLocator(
            MediaLocatorEntity(
                locatorId = locatorId,
                contentId = contentId,
                identityKey = "identity-$contentId",
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
}
