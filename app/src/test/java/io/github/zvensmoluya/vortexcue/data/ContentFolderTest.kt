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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ContentFolderTest {
    private lateinit var database: VortexDatabase
    private lateinit var repository: ContentRepository
    private var nextId = 0

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
                nowMs = { 1_000L + nextId },
                idFactory = { "id-${nextId++}" },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun movingContentReplacesItsOnlyFolderAndDeletingFolderReturnsToRoot() = runBlocking {
        val first = add("first.mp3", 1L)
        val second = add("second.mp3", 2L)
        val alpha = repository.createFolder("Alpha")
        val beta = repository.createFolder("Beta")

        repository.moveContentToFolder(first, alpha.folderId)
        repository.moveContentToFolder(first, beta.folderId)
        assertEquals(beta.folderId, repository.folderForContent(first))

        repository.moveContentToFolder(second, alpha.folderId)
        repository.deleteContentFolder(alpha.folderId)
        assertNull(repository.folderForContent(second))
        assertEquals(beta.folderId, repository.folderForContent(first))
    }

    @Test
    fun contentSortAndSearchAreAppliedGlobally() = runBlocking {
        val first = add("zulu.mp3", 1L)
        val second = add("alpha.mp3", 2L)
        val byTitle = repository.observeContent(null, sort = ContentSort.Title).first()
        assertEquals(listOf(second, first), byTitle.map(ContentSummary::contentId))

        val folder = repository.createFolder("Moved")
        repository.moveContentToFolder(second, folder.folderId)
        assertEquals(
            listOf(first),
            repository.observeContent(folder.folderId, query = "zulu").first().map(ContentSummary::contentId),
        )

        repository.recordOpened(first)
        val byPlayed = repository.observeContent(null, sort = ContentSort.RecentPlayed).first()
        assertEquals(first, byPlayed.first().contentId)
        assertEquals(listOf(second), repository.observeContent(null, query = "alpha").first().map(ContentSummary::contentId))

        repository.moveContentToFolder(second, null)
        repository.collect(first)
        val byAdded = repository.observeContent(null, query = "", sort = ContentSort.RecentAdded).first()
        assertEquals(first, byAdded.first().contentId)
    }

    @Test
    fun batchImportCollectsSelectionMovesItAndIgnoresUncheckedItems() = runBlocking {
        val selected = add("selected.mp3", 1L)
        val unchecked = add("unchecked.mp4", 2L)
        repository.clearDisposition(selected)
        repository.clearDisposition(unchecked)
        val folder = repository.createFolder("Imported")

        repository.importContent(
            contentIds = setOf(selected),
            folderId = folder.folderId,
            ignoredContentIds = setOf(unchecked),
        )

        assertEquals(ContentDisposition.Collected, repository.details(selected)?.disposition)
        assertEquals(folder.folderId, repository.folderForContent(selected))
        assertEquals(ContentDisposition.Ignored, repository.details(unchecked)?.disposition)
        assertNull(repository.folderForContent(unchecked))
        assertEquals(listOf(selected), repository.observeContent(folder.folderId).first().map(ContentSummary::contentId))

        repository.ignore(selected)
        assertNull(repository.folderForContent(selected))
        repository.collect(unchecked)
        assertEquals(ContentDisposition.Collected, repository.details(unchecked)?.disposition)
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
