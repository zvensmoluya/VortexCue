package io.github.zvensmoluya.vortexcue.text

import android.app.Application
import android.net.Uri
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.data.db.ActiveMediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.DiscoveredTextCandidateEntity
import io.github.zvensmoluya.vortexcue.data.db.MediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.SourceEntity
import io.github.zvensmoluya.vortexcue.data.db.TextImportOperationEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class TextImportCoordinatorTest {
    private lateinit var database: VortexDatabase
    private lateinit var storage: TextStorageRepository
    private lateinit var artifactStore: TestTextArtifactStore
    private var input = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".encodeToByteArray()
    private var clock = 100L
    private var nextId = 1

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        artifactStore = TestTextArtifactStore()
        storage = TextStorageRepository(database, artifactStore, nowMs = { clock++ })
        runBlocking { seedContent() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `manual import owns one revision and commits selection without uri fallback`() = runBlocking {
        val coordinator = coordinator()
        val track =
            coordinator.importManual(
                contentId = ContentId,
                slot = TextTrackSlot.Primary,
                source = SubtitleSourceRef(Uri.parse("content://input/manual.srt"), "manual.srt", "application/x-subrip"),
            )

        val revisionId = requireNotNull(track.revisionId)
        assertEquals("Ready", track.readiness)
        assertEquals("revision:$revisionId", track.locatorIdentity)
        assertEquals(track.trackId, database.contentDao().textSelections(ContentId).single().selectedTrackId)
        val operation = database.textStorageDao().importsForContent(ContentId).single()
        assertEquals(TextImportState.Committed.name, operation.state)
        assertNull(operation.stagingUri)
        assertEquals("Hello", TimelineRevisionReader(storage).read(revisionId).cues.single().text)
    }

    @Test
    fun `failed import leaves no ready track and records a terminal operation`() {
        input = "1\n00:00:10,000 --> 00:00:11,000\nOutside\n".encodeToByteArray()
        val coordinator = coordinator()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                coordinator.importManual(
                    contentId = ContentId,
                    slot = TextTrackSlot.Primary,
                    source = SubtitleSourceRef(Uri.parse("content://input/outside.srt"), "outside.srt", null),
                )
            }
        }
        runBlocking {
            assertTrue(database.contentDao().textTracks(ContentId).isEmpty())
            val operation = database.textStorageDao().importsForContent(ContentId).single()
            assertEquals(TextImportState.Failed.name, operation.state)
            assertNull(operation.stagingUri)
        }
    }

    @Test
    fun `startup recovery finishes an interrupted import`() = runBlocking {
        database.textStorageDao().upsertImportOperation(
            TextImportOperationEntity(
                importId = "interrupted",
                contentId = ContentId,
                candidateId = null,
                stagingUri = "content://input/recover.srt",
                releasePermissionOnCommit = false,
                sourceDisplayName = "recover.srt",
                mimeType = "application/x-subrip",
                detectedFormat = "srt",
                requestedSlot = TextTrackSlot.Secondary.name,
                selectionMode = TextSelectionMode.Manual.name,
                state = TextImportState.Reading.name,
                artifactId = null,
                revisionId = null,
                trackId = null,
                failureCategory = null,
                failureMessage = null,
                createdAtMs = clock,
                updatedAtMs = clock,
            ),
        )

        coordinator().recoverPending()

        val recovered = requireNotNull(database.textStorageDao().importOperation("interrupted"))
        assertEquals(TextImportState.Committed.name, recovered.state)
        assertNotNull(recovered.revisionId)
        assertEquals(recovered.trackId, database.contentDao().textSelections(ContentId).single().selectedTrackId)
    }

    @Test
    fun `artifact published recovery does not read the staging uri again`() = runBlocking {
        val timeline =
            CanonicalTextNormalizer.normalizeImported(
                SubtitleParser.parse("published.srt", input),
                mediaDurationMs = 5_000L,
            )
        val artifact = storage.publishTimelineArtifact(TextArtifactKind.ImportedTimeline, timeline)
        database.textStorageDao().upsertImportOperation(
            TextImportOperationEntity(
                importId = "published",
                contentId = ContentId,
                candidateId = null,
                stagingUri = null,
                releasePermissionOnCommit = false,
                sourceDisplayName = "published.srt",
                mimeType = "application/x-subrip",
                detectedFormat = "srt",
                requestedSlot = TextTrackSlot.Primary.name,
                selectionMode = TextSelectionMode.Manual.name,
                state = TextImportState.ArtifactPublished.name,
                artifactId = artifact.artifactId,
                revisionId = null,
                trackId = null,
                failureCategory = null,
                failureMessage = null,
                createdAtMs = clock,
                updatedAtMs = clock,
            ),
        )
        val coordinator =
            TextImportCoordinator(
                context = RuntimeEnvironment.getApplication(),
                database = database,
                storage = storage,
                nowMs = { clock++ },
                newId = { "unused" },
                inputReader = { error("staging URI must not be read after artifact publication") },
            )

        coordinator.recoverPending()

        val recovered = requireNotNull(database.textStorageDao().importOperation("published"))
        assertEquals(TextImportState.Committed.name, recovered.state)
        assertNotNull(recovered.revisionId)
        assertEquals(recovered.trackId, database.contentDao().textSelections(ContentId).single().selectedTrackId)
    }

    @Test
    fun `candidate is source relative until consumption and is then removed`() = runBlocking {
        database.contentDao().upsertSource(
            SourceEntity(
                sourceId = "folder",
                kind = "SafTree",
                rootUri = "content://provider/tree/root",
                displayName = "Folder",
                createdAtMs = 1L,
            ),
        )
        database.textStorageDao().upsertCandidates(
            listOf(
                DiscoveredTextCandidateEntity(
                    candidateId = "candidate",
                    contentId = ContentId,
                    sourceId = "folder",
                    documentId = "root/item.en.srt",
                    displayName = "item.en.srt",
                    mimeType = "application/x-subrip",
                    format = "srt",
                    language = "en",
                    label = "en",
                    rolesMask = 1,
                    matchBasis = "LanguageSuffix",
                    sourceGeneration = 1L,
                ),
            ),
        )

        val track = coordinator().importCandidate("candidate", TextTrackSlot.Primary, TextSelectionMode.Automatic)

        assertNull(database.textStorageDao().candidate("candidate"))
        assertEquals("en", track.language)
        assertEquals("Automatic", database.contentDao().textSelections(ContentId).single().mode)
    }

    private fun coordinator() =
        TextImportCoordinator(
            context = RuntimeEnvironment.getApplication(),
            database = database,
            storage = storage,
            nowMs = { clock++ },
            newId = { "import-${nextId++}" },
            inputReader = { input },
        )

    private suspend fun seedContent() {
        database.contentDao().upsertContent(ContentEntity(ContentId, 1L))
        database.contentDao().upsertLocator(
            MediaLocatorEntity(
                locatorId = "media",
                contentId = ContentId,
                identityKey = "media-identity",
                sourceId = null,
                uriValue = "content://media/item",
                displayName = "item.mkv",
                mimeType = "video/x-matroska",
                title = null,
                artist = null,
                album = null,
                durationMs = 5_000L,
                folder = null,
                dateAddedSeconds = 1L,
                origin = "OpenDocument",
                kind = "Video",
                sizeBytes = 1L,
                videoWidth = 1,
                videoHeight = 1,
                availability = "Available",
                seenGeneration = 0L,
                updatedAtMs = 1L,
            ),
        )
        database.contentDao().upsertActiveLocator(ActiveMediaLocatorEntity(ContentId, "media"))
    }

    private companion object {
        const val ContentId = "content"
    }
}
