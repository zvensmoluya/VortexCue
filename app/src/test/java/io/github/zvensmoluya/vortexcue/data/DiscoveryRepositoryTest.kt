package io.github.zvensmoluya.vortexcue.data

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaKind
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.library.LibrarySourceIds
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
import io.github.zvensmoluya.vortexcue.library.buildSidecarTextTrackCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class DiscoveryRepositoryTest {
    private lateinit var database: VortexDatabase
    private lateinit var repository: ContentRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        repository = ContentRepository(database, nowMs = { 1_000L }, idFactory = { "generated" })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun systemLocationsUseStableKeysNaturalOrderAndRecommendationPriority() = runBlocking {
        val descriptor = systemDescriptor()
        repository.applySuccessfulScan(
            descriptor = descriptor,
            media =
                listOf(
                    audio("track-10.mp3", "Track 10", "A/Music", durationMs = 120_000L, added = 10L),
                    audio("track-2.mp3", "Track 2", "A/Music", durationMs = 120_000L, added = 20L),
                    audio("other.mp3", "Other", "B/Music", durationMs = 180_000L, added = 30L),
                    audio("ping.mp3", "Ping", "Notifications", durationMs = 30_000L, added = 40L),
                ),
            textCandidates = emptyList(),
        )

        val groups = repository.discoveryGroups.first()
        assertEquals(3, groups.size)
        assertTrue(groups.take(2).all(DiscoveryGroupSummary::isRecommended))
        assertFalse(groups.last().isRecommended)
        assertEquals("Notifications", groups.last().displayName)
        assertEquals(setOf("A / Music", "B / Music"), groups.take(2).mapTo(hashSetOf(), DiscoveryGroupSummary::displayName))

        val aGroup = groups.first { it.displayName == "A / Music" }
        assertEquals(listOf("Track 2", "Track 10"), repository.observeDiscoveryItems(aGroup.sourceKey).first().map(DiscoveryItem::title))
        val sourceKey = aGroup.sourceKey

        repository.applySuccessfulScan(
            descriptor = descriptor,
            media = listOf(audio("track-2.mp3", "Track 2", "A\\Music", durationMs = 120_000L, added = 20L)),
            textCandidates = emptyList(),
        )
        assertEquals(sourceKey, repository.discoveryGroups.first().single().sourceKey)
    }

    @Test
    fun persistentScansRejectNewVideoAndDoNotInvalidateExplicitVideo() = runBlocking {
        val descriptor = systemDescriptor()
        val discoveredVideo = video("auto.mp4", MediaOrigin.MediaStore)
        repository.applySuccessfulScan(descriptor, listOf(discoveredVideo), emptyList())
        assertNull(repository.contentIdFor(discoveredVideo))

        val explicitVideo = video("explicit.mp4", MediaOrigin.OpenDocument)
        val explicitVideoId = repository.resolveOrCreate(explicitVideo)
        val audio = audio("episode.mp3", "Episode", "Music", 120_000L, 1L)
        val audioId = repository.resolveOrCreate(audio)
        repository.collect(explicitVideoId)
        repository.collect(audioId)

        repository.applySuccessfulScan(descriptor, emptyList(), emptyList())

        assertEquals(ContentAvailability.Available, repository.details(explicitVideoId)?.contentUnit?.availability)
        assertEquals(ContentAvailability.Unavailable, repository.details(audioId)?.contentUnit?.availability)
    }

    @Test
    fun folderScanOnlyOffersSubtitleOwnershipAfterContentIsCollected() = runBlocking {
        val root = Uri.parse("content://provider/tree/root")
        val descriptor =
            SourceScanDescriptor(
                sourceId = "folder",
                kind = LibrarySourceKind.Folder,
                rootUri = root.toString(),
                displayName = "Folder",
            )
        val media =
            MediaSourceRef(
                uri = DocumentsContract.buildDocumentUriUsingTree(root, "root/episode.mp3"),
                displayName = "episode.mp3",
                mimeType = "audio/mpeg",
                durationMs = 120_000L,
                folder = "Folder",
                origin = MediaOrigin.DocumentTree,
                sourceId = descriptor.sourceId,
                kind = MediaKind.Audio,
            )
        val subtitle =
            SubtitleSourceRef(
                uri = DocumentsContract.buildDocumentUriUsingTree(root, "root/episode.srt"),
                displayName = "episode.srt",
                mimeType = "application/x-subrip",
            )
        val candidate = requireNotNull(buildSidecarTextTrackCandidate(media, subtitle))

        assertTrue(repository.applySuccessfulScan(descriptor, listOf(media), listOf(candidate)).isEmpty())
        val contentId = requireNotNull(repository.contentIdFor(media))
        assertEquals(1, repository.details(contentId)?.discoveredTextCandidates?.size)

        repository.collect(contentId)
        val materializationIds = repository.applySuccessfulScan(descriptor, listOf(media), listOf(candidate))

        assertEquals(1, materializationIds.size)
        assertEquals(materializationIds.single(), repository.details(contentId)?.discoveredTextCandidates?.single()?.candidateId)
    }

    private fun systemDescriptor() =
        SourceScanDescriptor(
            sourceId = LibrarySourceIds.SystemLibrary,
            kind = LibrarySourceKind.SystemLibrary,
            rootUri = null,
            displayName = "设备媒体库",
        )

    private fun audio(
        name: String,
        title: String,
        folder: String,
        durationMs: Long,
        added: Long,
    ) =
        MediaSourceRef(
            uri = Uri.parse("content://media/audio/$name"),
            displayName = name,
            mimeType = "audio/mpeg",
            title = title,
            durationMs = durationMs,
            folder = folder,
            dateAddedSeconds = added,
            origin = MediaOrigin.MediaStore,
            sourceId = LibrarySourceIds.SystemLibrary,
            kind = MediaKind.Audio,
        )

    private fun video(name: String, origin: MediaOrigin) =
        MediaSourceRef(
            uri = Uri.parse("content://media/video/$name"),
            displayName = name,
            mimeType = "video/mp4",
            durationMs = 120_000L,
            folder = "Movies",
            origin = origin,
            sourceId = LibrarySourceIds.SystemLibrary,
            kind = MediaKind.Video,
        )
}
