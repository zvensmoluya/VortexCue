package io.github.zvensmoluya.vortexcue.data

import android.app.Application
import android.net.Uri
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaKind
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class PlaylistRepositoryTest {
    private lateinit var database: VortexDatabase
    private lateinit var repository: ContentRepository
    private var nextId = 0
    private var clock = 1_000L

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        repository =
            ContentRepository(
                database = database,
                nowMs = { clock++ },
                idFactory = { "id-${nextId++}" },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun contentCanBelongToMultiplePlaylistsAndDeletingPlaylistPreservesContent() = runBlocking {
        val contentId = add("first.mp3", 1L)
        val alpha = repository.createPlaylist("Alpha")
        val beta = repository.createPlaylist("Beta")

        repository.setContentPlaylists(contentId, setOf(alpha.playlistId, beta.playlistId))

        assertEquals(setOf(alpha.playlistId, beta.playlistId), repository.playlistIdsForContent(contentId))
        assertEquals(listOf(contentId), repository.playlistContentIds(alpha.playlistId))
        assertEquals(listOf(contentId), repository.playlistContentIds(beta.playlistId))

        repository.deletePlaylist(alpha.playlistId)

        assertEquals(setOf(beta.playlistId), repository.playlistIdsForContent(contentId))
        assertEquals(listOf(contentId), repository.allContent.first().map(ContentSummary::contentId))
    }

    @Test
    fun playlistsAndMembersKeepManualOrderAndRecentOnlyTracksPlayedNonEmptyPlaylists() = runBlocking {
        val first = add("first.mp3", 1L)
        val second = add("second.mp3", 2L)
        val alpha = repository.createPlaylist("Alpha")
        val beta = repository.createPlaylist("Beta")
        repository.setContentPlaylists(first, setOf(alpha.playlistId))
        repository.setContentPlaylists(second, setOf(alpha.playlistId))

        repository.reorderPlaylists(listOf(beta.playlistId, alpha.playlistId))
        repository.reorderPlaylistItems(alpha.playlistId, listOf(second, first))
        repository.markPlaylistPlayed(alpha.playlistId)
        repository.markPlaylistPlayed(beta.playlistId)

        assertEquals(listOf(beta.playlistId, alpha.playlistId), repository.playlists.first().map(PlaylistSummary::playlistId))
        assertEquals(listOf(second, first), repository.playlistContentIds(alpha.playlistId))
        assertEquals(listOf(alpha.playlistId), repository.recentPlaylists.first().map(PlaylistSummary::playlistId))
    }

    @Test
    fun importAddsOneInitialPlaylistAndIgnoreClearsEveryMembership() = runBlocking {
        val selected = add("selected.mp3", 1L)
        val unchecked = add("unchecked.mp4", 2L)
        repository.clearDisposition(selected)
        repository.clearDisposition(unchecked)
        val playlist = repository.createPlaylist("Imported")

        repository.importContent(
            contentIds = setOf(selected),
            playlistId = playlist.playlistId,
            ignoredContentIds = setOf(unchecked),
        )

        assertEquals(ContentDisposition.Collected, repository.details(selected)?.disposition)
        assertEquals(setOf(playlist.playlistId), repository.playlistIdsForContent(selected))
        assertEquals(ContentDisposition.Ignored, repository.details(unchecked)?.disposition)
        assertTrue(repository.playlistIdsForContent(unchecked).isEmpty())

        repository.ignore(selected)
        assertTrue(repository.playlistIdsForContent(selected).isEmpty())
        assertFalse(repository.allContent.first().any { it.contentId == selected })
        repository.collect(selected)
        assertTrue(repository.allContent.first().any { it.contentId == selected })
        assertTrue(repository.playlistIdsForContent(selected).isEmpty())
    }

    @Test
    fun recentPlaylistsAreLimitedToSixNewestPlayedLists() = runBlocking {
        val contentId = add("shared.mp3", 1L)
        val created = (1..7).map { repository.createPlaylist("List $it") }
        repository.setContentPlaylists(contentId, created.mapTo(linkedSetOf()) { it.playlistId })
        created.forEach { repository.markPlaylistPlayed(it.playlistId) }

        val recent = repository.recentPlaylists.first()

        assertEquals(6, recent.size)
        assertEquals(created.last().playlistId, recent.first().playlistId)
        assertFalse(recent.any { it.playlistId == created.first().playlistId })
    }

    private suspend fun add(name: String, dateAddedSeconds: Long): String {
        val id =
            repository.resolveOrCreate(
                MediaSourceRef(
                    uri = Uri.parse("content://test/$name"),
                    displayName = name,
                    mimeType = "audio/mpeg",
                    dateAddedSeconds = dateAddedSeconds,
                    origin = MediaOrigin.OpenDocument,
                    kind = MediaKind.Audio,
                ),
                availability = ContentAvailability.Available,
            )
        repository.collect(id)
        return id
    }
}
