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

        val ordered = repository.playlists.first()
        assertTrue(ordered.first().isDefaultFavorite)
        assertEquals(listOf(beta.playlistId, alpha.playlistId), ordered.drop(1).map(PlaylistSummary::playlistId))
        assertEquals(listOf(second, first), repository.playlistContentIds(alpha.playlistId))
        assertEquals(listOf(alpha.playlistId), repository.recentPlaylists.first().map(PlaylistSummary::playlistId))
    }

    @Test
    fun importAddsMultipleTargetsAndLeavesUncheckedContentPending() = runBlocking {
        val selected = add("selected.mp3", 1L, collect = false)
        val unchecked = add("unchecked.mp3", 2L, collect = false)
        val playlist = repository.createPlaylist("Imported")
        val second = repository.createPlaylist("Also imported")

        repository.importContent(
            contentIds = listOf(selected),
            playlistIds = setOf(playlist.playlistId, second.playlistId),
            sourceKey = "system:test",
        )

        assertEquals(ContentDisposition.Collected, repository.details(selected)?.disposition)
        assertEquals(setOf(playlist.playlistId, second.playlistId), repository.playlistIdsForContent(selected))
        assertEquals(null, repository.details(unchecked)?.disposition)
        assertTrue(repository.playlistIdsForContent(unchecked).isEmpty())
        assertEquals(setOf(playlist.playlistId, second.playlistId), repository.observeImportTargets("system:test").first())
        repository.importContent(
            contentIds = listOf(selected, selected),
            playlistIds = setOf(playlist.playlistId, second.playlistId),
            sourceKey = "system:test",
        )
        assertEquals(listOf(selected), repository.playlistContentIds(playlist.playlistId))
        assertEquals(listOf(selected), repository.playlistContentIds(second.playlistId))
        assertEquals(listOf(selected), repository.allContent.first().map(ContentSummary::contentId))

        repository.deletePlaylist(playlist.playlistId)
        repository.deletePlaylist(second.playlistId)
        val defaultId = repository.playlists.first().first { it.isDefaultFavorite }.playlistId
        assertEquals(setOf(defaultId), repository.observeImportTargets("system:test").first())

        repository.ignore(selected)
        assertTrue(repository.playlistIdsForContent(selected).isEmpty())
        assertFalse(repository.allContent.first().any { it.contentId == selected })
        repository.collect(selected)
        assertTrue(repository.allContent.first().any { it.contentId == selected })
        assertTrue(repository.playlistIdsForContent(selected).single() == repository.playlists.first().first { it.isDefaultFavorite }.playlistId)
    }

    @Test
    fun removingLastMembershipAndDeletingExclusivePlaylistMoveContentToDefault() = runBlocking {
        val first = add("first.mp3", 1L)
        val second = add("second.mp3", 2L)
        val playlist = repository.createPlaylist("Temporary")
        repository.setContentPlaylists(first, setOf(playlist.playlistId))
        repository.setContentPlaylists(second, setOf(playlist.playlistId))
        val defaultId = repository.playlists.first().first { it.isDefaultFavorite }.playlistId

        repository.removeContentFromPlaylist(first, playlist.playlistId)
        assertEquals(setOf(defaultId), repository.playlistIdsForContent(first))

        repository.deletePlaylist(playlist.playlistId)
        assertEquals(setOf(defaultId), repository.playlistIdsForContent(second))
        assertEquals(listOf(first, second), repository.playlistContentIds(defaultId))
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

    @Test
    fun defaultFavoriteIsProtectedAndCanAppearInRecentPlaylists() = runBlocking {
        add("favorite.mp3", 1L)
        val default = repository.playlists.first().first { it.isDefaultFavorite }

        repository.markPlaylistPlayed(default.playlistId)

        assertEquals(default.playlistId, repository.recentPlaylists.first().single().playlistId)
        assertTrue(runCatching { repository.renamePlaylist(default.playlistId, "Other") }.isFailure)
        assertTrue(runCatching { repository.deletePlaylist(default.playlistId) }.isFailure)
    }

    private suspend fun add(name: String, dateAddedSeconds: Long, collect: Boolean = true): String {
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
        if (collect) repository.collect(id)
        return id
    }
}
