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
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.library.LibrarySourceIds
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
import io.github.zvensmoluya.vortexcue.library.buildSidecarTextTrackCandidate
import java.io.File
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
    fun systemFormatVariantsShareOneWorkGroup() = runBlocking {
        repository.applySuccessfulScan(
            descriptor = systemDescriptor(),
            media =
                listOf(
                    audio("track.wav", "WAV Track", "Downloads/Work/WAV", 120_000L, 10L),
                    audio("track.mp3", "MP3 Track", "Downloads/Work/MP3", 120_000L, 20L),
                    audio("other.mp3", "Other Track", "Downloads/Other/WAV", 120_000L, 30L),
                ),
            textCandidates = emptyList(),
        )

        val groups = repository.discoveryGroups.first()
        assertEquals(listOf("Other", "Work"), groups.map(DiscoveryGroupSummary::displayName).sorted())
        val work = groups.single { it.displayName == "Work" }
        assertEquals(2, work.totalCount)
        assertEquals(
            listOf("MP3 Track", "WAV Track"),
            repository.observeDiscoveryItems(work.sourceKey).first().map(DiscoveryItem::title),
        )
        assertEquals(
            listOf("WAV Track"),
            repository.observeDiscoveryItems(work.sourceKey).first().filter(DiscoveryItem::isDefaultSelected).map(DiscoveryItem::title),
        )
    }

    @Test
    fun folderSourceSplitsWorksAndCollapsesTheirFormatVariants() = runBlocking {
        val descriptor =
            SourceScanDescriptor(
                sourceId = "folder",
                kind = LibrarySourceKind.Folder,
                rootUri = "content://provider/tree/root",
                displayName = "DLsite",
            )
        repository.applySuccessfulScan(
            descriptor = descriptor,
            media =
                listOf(
                    audio("a-wav.mp3", "A WAV", "DLsite/Work A/WAV", 120_000L, 10L, descriptor.sourceId),
                    audio("a-mp3.mp3", "A MP3", "DLsite/Work A/MP3", 120_000L, 20L, descriptor.sourceId),
                    audio("b.mp3", "B", "DLsite/Work B/WAV", 120_000L, 30L, descriptor.sourceId),
                ),
            textCandidates = emptyList(),
        )

        val groups = repository.discoveryGroups.first()
        assertEquals(listOf("Work A", "Work B"), groups.map(DiscoveryGroupSummary::displayName).sorted())
        assertEquals(2, groups.single { it.displayName == "Work A" }.totalCount)
        assertTrue(groups.all { it.sourceKey.startsWith("folder:${descriptor.sourceId}:") })
    }

    @Test
    fun trustedRjPackageImportsAsWorkAndCanBeReferencedByPlaylist() = runBlocking {
        val descriptor =
            SourceScanDescriptor(
                sourceId = "folder:rj",
                kind = LibrarySourceKind.Folder,
                rootUri = "content://provider/tree/rj",
                displayName = "DLsite",
            )
        repository.applySuccessfulScan(
            descriptor,
            listOf(
                audio("01.wav", "第一段", "DLsite/RJ01658524 作品/01_WAV版", 120_000L, 10L, descriptor.sourceId),
                audio("01.mp3", "第一段", "DLsite/RJ01658524 作品/02_MP3版", 120_500L, 10L, descriptor.sourceId),
                audio("01-no-se.wav", "第一段（无 SE）", "DLsite/RJ01658524 作品/03_効果音無し", 119_000L, 10L, descriptor.sourceId),
                audio("bonus.wav", "特典", "DLsite/RJ01658524 作品/04_特典", 60_000L, 10L, descriptor.sourceId),
            ),
            emptyList(),
        )
        val group = repository.discoveryGroups.first().single()
        assertEquals("RJ01658524", group.releaseCode)
        assertEquals("作品", group.displayName)

        val result = repository.importWork(group.sourceKey)
        val details = requireNotNull(repository.workDetails(result.workId))
        assertEquals(3, details.parts.size)
        assertEquals(2, details.includedParts.size)
        assertEquals(3, result.contentIds.size)
        assertTrue(details.parts.all { it.content.disposition == ContentDisposition.Collected })
        val standard = details.parts.single { it.variantKey == "default" && it.sectionName == "正篇" }
        val withoutSe = details.parts.single { it.variantKey == "without-se" }
        val bonus = details.parts.single { it.sectionName == "特典" }
        assertEquals(2, repository.details(standard.content.contentId)?.mediaLocators?.size)
        assertTrue(repository.playlistIdsForContent(standard.content.contentId).isEmpty())
        assertEquals(listOf(standard.content.contentId, bonus.content.contentId), repository.workQueue(result.workId).map { it.contentId })

        repository.setWorkEdition(result.workId, "without-se")
        assertEquals(listOf(withoutSe.content.contentId, bonus.content.contentId), repository.workQueue(result.workId).map { it.contentId })
        repository.renameWork(result.workId, "用户修改的作品名")
        repository.importWork(group.sourceKey)
        assertEquals(listOf(withoutSe.content.contentId, bonus.content.contentId), repository.workQueue(result.workId).map { it.contentId })
        assertEquals("用户修改的作品名", repository.workDetails(result.workId)?.summary?.title)

        val playlist = repository.createPlaylist("夜听")
        repository.addWorkToPlaylists(result.workId, setOf(playlist.playlistId))
        val entry = repository.observePlaylistEntries(playlist.playlistId).first().single()
        assertTrue(entry is PlaylistEntrySummary.Work)
        val queue = repository.playlistQueue(playlist.playlistId)
        assertTrue(queue.all { it.originWorkId == result.workId })
        assertTrue(queue.all { it.originPlaylistEntryId == entry.entryId })
    }

    @Test
    fun trustedRjPackageUsesChildFolderAsTitleAndRejectsFormatAlbum() = runBlocking {
        val descriptor = systemDescriptor()
        repository.applySuccessfulScan(
            descriptor,
            listOf(
                audio(
                    "01.wav",
                    "01_清扫一下下w",
                    "Download/RJ01520881/妈妈小鬼/01本篇/wav",
                    558_025L,
                    10L,
                    album = "wav",
                ),
                audio(
                    "01.mp3",
                    "01_清扫一下下w",
                    "Download/RJ01520881/妈妈小鬼/01本篇/mp3",
                    558_054L,
                    10L,
                    album = "mp3",
                ),
                audio(
                    "02.wav",
                    "02_宠宠你吧♡w",
                    "Download/RJ01520881/妈妈小鬼/01本篇/wav",
                    1_945_194L,
                    10L,
                    album = "wav",
                ),
                audio(
                    "02.mp3",
                    "02_宠宠你吧♡w",
                    "Download/RJ01520881/妈妈小鬼/01本篇/mp3",
                    1_945_234L,
                    10L,
                    album = "mp3",
                ),
            ),
            emptyList(),
        )

        val group = repository.discoveryGroups.first().single()
        assertEquals("妈妈小鬼", group.displayName)
        assertEquals("RJ01520881", group.releaseCode)
        val preview = requireNotNull(repository.observeWorkImportPreview(group.sourceKey).first())
        assertEquals("妈妈小鬼", preview.title)
        assertEquals(2, preview.logicalPartCount)

        repository.applySuccessfulScan(
            descriptor,
            listOf(
                audio(
                    "fallback.wav",
                    "Fallback",
                    "Download/RJ09999999/wav",
                    120_000L,
                    20L,
                    album = "wav",
                ),
            ),
            emptyList(),
        )
        assertEquals("RJ09999999", repository.discoveryGroups.first().single().displayName)
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

    @Test
    fun folderSidecarsAlsoTargetEquivalentCollectedSystemAudio() = runBlocking {
        val systemWav =
            audio(
                "01_episode.wav",
                "01_episode",
                "Download/RJ01520881/Work/01Main/wav",
                120_000L,
                10L,
            ).copy(sizeBytes = 12_345L, mimeType = "audio/wav")
        val systemMp3 =
            audio(
                "01_episode.mp3",
                "01_episode",
                "Download/RJ01520881/Work/01Main/mp3",
                120_000L,
                10L,
            ).copy(sizeBytes = 5_678L)
        repository.applySuccessfulScan(systemDescriptor(), listOf(systemWav, systemMp3), emptyList())
        val systemGroup = repository.discoveryGroups.first().single()
        val work = repository.importWork(systemGroup.sourceKey)
        val systemContentId = work.contentIds.single()

        val root = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FRJ01520881")
        val folderDescriptor =
            SourceScanDescriptor(
                sourceId = "folder:rj",
                kind = LibrarySourceKind.Folder,
                rootUri = root.toString(),
                displayName = "RJ01520881",
            )
        val folderWav =
            systemWav.copy(
                uri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        root,
                        "primary:Download/RJ01520881/Work/01Main/wav/01_episode.wav",
                    ),
                folder = "RJ01520881/Work/01Main/wav",
                origin = MediaOrigin.DocumentTree,
                sourceId = folderDescriptor.sourceId,
            )
        val folderMp3 =
            systemMp3.copy(
                uri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        root,
                        "primary:Download/RJ01520881/Work/01Main/mp3/01_episode.mp3",
                    ),
                folder = "RJ01520881/Work/01Main/mp3",
                origin = MediaOrigin.DocumentTree,
                sourceId = folderDescriptor.sourceId,
            )
        fun candidate(media: MediaSourceRef): io.github.zvensmoluya.vortexcue.library.TimedTextTrackCandidate {
            val subtitle =
                SubtitleSourceRef(
                    uri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            root,
                            DocumentsContract.getDocumentId(media.uri) + ".vtt",
                        ),
                    displayName = media.displayName + ".vtt",
                    mimeType = "text/vtt",
                )
            return requireNotNull(buildSidecarTextTrackCandidate(media, subtitle))
        }

        val candidateIds =
            repository.applySuccessfulScan(
                folderDescriptor,
                listOf(folderWav, folderMp3),
                listOf(candidate(folderWav), candidate(folderMp3)),
            )

        assertEquals(1, candidateIds.size)
        val systemCandidates = repository.details(systemContentId)?.discoveredTextCandidates.orEmpty()
        assertEquals(listOf("01_episode.wav.vtt"), systemCandidates.map { it.displayName })
        assertEquals(TimedTextMatchBasis.ExactStem, systemCandidates.single().matchBasis)
    }

    @Test
    fun fullStorageSidecarsPersistOnTheSystemSource() = runBlocking {
        val media =
            audio(
                "episode.wav",
                "Episode",
                "Download/RJ12345678/Work/wav",
                120_000L,
                10L,
            ).copy(sizeBytes = 12_345L, mimeType = "audio/wav")
        val subtitle =
            SubtitleSourceRef(
                uri = Uri.fromFile(File("/storage/emulated/0/Download/RJ12345678/Work/wav/episode.wav.vtt")),
                displayName = "episode.wav.vtt",
                mimeType = "text/vtt",
            )

        repository.applySuccessfulScan(
            systemDescriptor(),
            listOf(media),
            listOf(requireNotNull(buildSidecarTextTrackCandidate(media, subtitle))),
        )

        val contentId = repository.discoveryGroups.first().single().let { group ->
            repository.observeDiscoveryItems(group.sourceKey).first().single().contentId
        }
        val candidate = repository.details(contentId)?.discoveredTextCandidates?.single()
        assertEquals("episode.wav.vtt", candidate?.displayName)
        assertEquals(TimedTextMatchBasis.ExactStem, candidate?.matchBasis)
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
        sourceId: String = LibrarySourceIds.SystemLibrary,
        album: String? = null,
    ) =
        MediaSourceRef(
            uri = Uri.parse("content://media/audio/$name"),
            displayName = name,
            mimeType = "audio/mpeg",
            title = title,
            album = album,
            durationMs = durationMs,
            folder = folder,
            dateAddedSeconds = added,
            origin = if (sourceId == LibrarySourceIds.SystemLibrary) MediaOrigin.MediaStore else MediaOrigin.DocumentTree,
            sourceId = sourceId,
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
