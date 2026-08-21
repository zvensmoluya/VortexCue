package io.github.zvensmoluya.vortexcue.data.db

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VortexDatabaseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: VortexDatabase
    private lateinit var contentDao: ContentDao
    private lateinit var playbackDao: PlaybackDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        contentDao = database.contentDao()
        playbackDao = database.playbackDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun activeLocatorMustBelongToItsContent() = runBlocking {
        insertContent("first", "content://media/first")
        insertContent("second", "content://media/second")

        val failure =
            runCatching {
                contentDao.upsertActiveLocator(
                    ActiveMediaLocatorEntity(contentId = "first", locatorId = "locator-second"),
                )
            }

        assertTrue(failure.isFailure)
        assertEquals("locator-first", contentDao.activeLocator("first")?.locatorId)
    }

    @Test
    fun textSelectionTrackMustBelongToItsContent() = runBlocking {
        insertContent("first", "content://media/first")
        insertContent("second", "content://media/second")
        contentDao.upsertTextTrack(textTrack("track-second", "second"))

        val failure =
            runCatching {
                contentDao.upsertTextSelection(
                    TextSelectionEntity("first", "Primary", "track-second", "Manual"),
                )
            }

        assertTrue(failure.isFailure)
        assertTrue(contentDao.textSelections("first").isEmpty())
    }

    @Test
    fun currentQueueKeepsOneEntryPerContent() = runBlocking {
        insertContent("first", "content://media/first")
        val now = 10L
        playbackDao.upsertQueueEntries(
            listOf(CurrentQueueEntryEntity("first", 0, now)),
        )
        playbackDao.upsertQueueEntries(
            listOf(CurrentQueueEntryEntity("first", 0, now + 1)),
        )

        assertEquals(1, playbackDao.queueEntries().size)
    }

    @Test
    fun dispositionIsMutuallyExclusive() = runBlocking {
        insertContent("first", "content://media/first")
        contentDao.upsertDisposition(ContentDispositionEntity("first", false, 1L))
        contentDao.upsertDisposition(ContentDispositionEntity("first", true, 2L))

        assertEquals(true, contentDao.disposition("first")?.isCollected)
    }

    @Test
    fun failedScanKeepsGenerationAndSuccessfulScanInvalidatesOlderLocators() = runBlocking {
        val source = SourceEntity("source", "Folder", "content://tree", "Folder", createdAtMs = 1L)
        contentDao.upsertSource(source.copy(generation = 1L, lastIndexedAtMs = 1L))
        insertContent("first", "content://media/first", sourceId = "source", generation = 1L)

        contentDao.upsertSource(
            source.copy(generation = 1L, lastIndexedAtMs = 1L, lastError = "failed"),
        )
        assertEquals(1L, contentDao.source("source")?.generation)
        assertEquals("Available", contentDao.locator("locator-first")?.availability)

        database.withWriteTransaction {
            contentDao.upsertSource(source.copy(generation = 2L, lastIndexedAtMs = 2L))
            contentDao.markOlderSourceLocatorsUnavailable("source", 2L, 2L)
        }
        assertEquals("Unavailable", contentDao.locator("locator-first")?.availability)
    }

    @Test
    fun durableUserRelationsAreGarbageCollectionRoots() = runBlocking {
        insertContent("collected", "content://media/collected", availability = "Unavailable")
        insertContent("ignored", "content://media/ignored", availability = "Unavailable")
        insertContent("recent", "content://media/recent", availability = "Unavailable")
        insertContent("folder", "content://media/folder", availability = "Unavailable")
        insertContent("queued", "content://media/queued", availability = "Unavailable")
        insertContent("draft", "content://media/draft", availability = "Unavailable")
        insertContent("manual-track", "content://media/manual-track", availability = "Unavailable")
        insertContent("manual-selection", "content://media/manual-selection", availability = "Unavailable")
        insertContent("orphan", "content://media/orphan", availability = "Unavailable")
        contentDao.upsertDisposition(ContentDispositionEntity("collected", true, 1L))
        contentDao.upsertDisposition(ContentDispositionEntity("ignored", false, 1L))
        contentDao.upsertRecent(RecentEntryEntity("recent", 1L))
        contentDao.upsertContentFolder(ContentFolderEntity("folder-root", "Root", 1L, 1L))
        contentDao.upsertContentFolderMembership(ContentFolderMembershipEntity("folder", "folder-root", 1L))
        playbackDao.upsertQueueEntries(listOf(CurrentQueueEntryEntity("queued", 0, 1L)))
        contentDao.upsertDraft(CurrentDraftEntity(contentId = "draft", updatedAtMs = 1L))
        contentDao.upsertTextTrack(textTrack("track-manual", "manual-track"))
        contentDao.upsertTextTrack(
            textTrack("track-selected", "manual-selection").copy(matchBasis = "ExactStem"),
        )
        contentDao.upsertTextSelection(
            TextSelectionEntity("manual-selection", "Primary", "track-selected", "Manual"),
        )

        val candidates = contentDao.garbageCollectableContentIds().toSet()

        assertEquals(setOf("folder", "orphan"), candidates)
    }

    @Test
    fun pagingReadsActiveLocatorMetadataWithoutContentCopy() = runBlocking {
        repeat(55) { index ->
            val id = "content-$index"
            insertContent(id, "content://media/$index", dateAdded = index.toLong())
            contentDao.upsertDisposition(ContentDispositionEntity(id, true, index.toLong()))
        }

        val result =
            contentDao.pagedCollected().load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
            )
        val page = result as PagingSource.LoadResult.Page

        assertEquals(50, page.data.size)
        assertEquals("content-54", page.data.first().contentId)
        assertEquals("Title 54", page.data.first().title)
    }

    @Test
    fun recentHistoryIsTrimmedToFiftyEntries() = runBlocking {
        repeat(55) { index ->
            val id = "recent-$index"
            insertContent(id, "content://media/recent/$index")
            contentDao.upsertRecent(RecentEntryEntity(id, index.toLong()))
        }

        contentDao.trimRecent(50)
        val result =
            contentDao.pagedRecent().load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 60, placeholdersEnabled = false),
            ) as PagingSource.LoadResult.Page

        assertEquals(50, result.data.size)
        assertEquals("recent-54", result.data.first().contentId)
        assertEquals("recent-5", result.data.last().contentId)
    }

    @Test
    fun sourceSummaryCountsOnlyActiveLocators() = runBlocking {
        contentDao.upsertSource(
            SourceEntity("source", "Folder", "content://tree", "Folder", createdAtMs = 1L),
        )
        insertContent("first", "content://media/first", sourceId = "source")
        insertContent("second", "content://media/second", sourceId = "source")
        contentDao.upsertLocator(
            requireNotNull(contentDao.locator("locator-first")).copy(
                locatorId = "locator-first-old",
                uriValue = "content://media/first-old",
                identityKey = "identity-first-old",
            ),
        )

        val summary = contentDao.observeSourceSummaries().first().single()

        assertEquals(2, summary.itemCount)
    }

    @Test
    fun staleProgressCannotUpdateRevisedOrClearedQueue() = runBlocking {
        insertContent("first", "content://media/first")
        playbackDao.upsertQueueEntries(listOf(CurrentQueueEntryEntity("first", 0, 1L)))
        playbackDao.upsertSession(
            CurrentPlaybackSessionEntity(
                currentContentId = "first",
                queueRevision = 3L,
                updatedAtMs = 1L,
            ),
        )

        assertEquals(
            0,
            playbackDao.updateProgress("first", 100L, 1_000L, 1f, expectedRevision = 2L, updatedAtMs = 2L),
        )
        assertEquals(
            1,
            playbackDao.updateProgress("first", 100L, 1_000L, 1f, expectedRevision = 3L, updatedAtMs = 2L),
        )
        playbackDao.clearQueueEntries()
        playbackDao.upsertSession(
            requireNotNull(playbackDao.session()).copy(
                currentContentId = null,
                queueRevision = 4L,
                updatedAtMs = 3L,
            ),
        )
        assertEquals(
            0,
            playbackDao.updateProgress("first", 200L, 1_000L, 1f, expectedRevision = 3L, updatedAtMs = 4L),
        )
        assertTrue(playbackDao.queueEntries().isEmpty())
        assertFalse(playbackDao.session()?.currentContentId == "first")
    }

    @Test
    fun incompatiblePreReleaseSchemaIsDestructivelyReset() = runBlocking {
        val path = temporaryFolder.root.resolve("incompatible.db").absolutePath
        val driver = BundledSQLiteDriver()
        driver.open(path).use { connection ->
            connection.prepare("CREATE TABLE legacy_domain (id INTEGER PRIMARY KEY)").use { it.step() }
            connection.prepare("INSERT INTO legacy_domain (id) VALUES (1)").use { it.step() }
            connection.prepare("PRAGMA user_version = 99").use { it.step() }
        }

        val reset =
            Room.databaseBuilder<VortexDatabase>(path) { VortexDatabase_Impl() }
                .setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        reset.contentDao().content("missing")
        reset.close()

        driver.open(path).use { connection ->
            connection.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name IN ('content', 'legacy_domain', 'playlist', 'playlist_entry', 'home_shortcut') ORDER BY name",
            ).use { statement ->
                val tables = buildList {
                    while (statement.step()) add(statement.getText(0))
                }
                assertEquals(listOf("content"), tables)
            }
            connection.prepare("PRAGMA table_info(current_playback_session)").use { statement ->
                val columns = buildList {
                    while (statement.step()) add(statement.getText(1))
                }
                assertFalse("last_surface" in columns)
            }
        }
    }

    @Test
    fun versionFourMigrationPreservesCompletedGeneratedResults() = runBlocking {
        val helper =
            MigrationTestHelper(
                databasePath = temporaryFolder.root.resolve("migration-v4-v5.db").toPath(),
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(4).use { connection ->
            connection.prepare(
                "INSERT INTO content (content_id, created_at_ms, subtitle_offset_ms) VALUES ('content', 1, 0)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO generated_text_slot " +
                    "(slot_id, content_id, target_key, target_language, presentation_mode, current_result_id, created_at_ms, updated_at_ms) " +
                    "VALUES ('slot', 'content', '__source__', NULL, 'TargetOnly', 'result', 1, 1)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO generated_text_result " +
                    "(result_id, slot_id, content_id, task_id, attempt_id, source_language, target_language, " +
                    "presentation_mode, source_asset_file_name, target_asset_file_name, source_sha256, target_sha256, " +
                    "cue_count, scheme_id, model, policy_id, policy_version, policy_revision, system_prompt_sha256, " +
                    "style_prompt_sha256, input_tokens, output_tokens, total_tokens, cost_usd, created_at_ms) " +
                    "VALUES ('result', 'slot', 'content', NULL, NULL, 'en', NULL, 'TargetOnly', 'result.vtt', NULL, " +
                    "'hash', NULL, 1, 'scheme', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1)",
            ).use { it.step() }
        }

        helper.runMigrationsAndValidate(5).use { connection ->
            connection.prepare(
                "SELECT completeness, cue_count FROM generated_text_result WHERE result_id = 'result'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("Final", statement.getText(0))
                assertEquals(1L, statement.getLong(1))
            }
        }
    }

    @Test
    fun versionSevenMigrationPreservesDataInVersionEight() = runBlocking {
        val helper =
            MigrationTestHelper(
                databasePath = temporaryFolder.root.resolve("migration-v7-v8.db").toPath(),
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(7).use { connection ->
            connection.prepare(
                "INSERT INTO content (content_id, created_at_ms, subtitle_offset_ms) VALUES ('preserved-content', 1, 0)",
            ).use { it.step() }
        }

        helper.runMigrationsAndValidate(8).use { connection ->
            connection.prepare("SELECT content_id FROM content WHERE content_id = 'preserved-content'").use { statement ->
                assertTrue(statement.step())
                assertEquals("preserved-content", statement.getText(0))
            }
            connection.prepare("SELECT COUNT(*) FROM sync_text_diagnostic_event").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun versionSixIsDestructivelyRebuiltAsVersionEight() = runBlocking {
        val path = temporaryFolder.root.resolve("migration-v6-v8.db").toPath()
        val helper =
            MigrationTestHelper(
                databasePath = path,
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(6).use { connection ->
            connection.prepare(
                "INSERT INTO content (content_id, created_at_ms, subtitle_offset_ms) VALUES ('old-content', 1, 0)",
            ).use { it.step() }
        }

        val rebuilt =
            Room.databaseBuilder<VortexDatabase>(path.toString()) { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        assertNull(rebuilt.contentDao().content("old-content"))
        assertTrue(rebuilt.textStorageDao().artifacts().isEmpty())
        rebuilt.close()
    }

    private suspend fun insertContent(
        contentId: String,
        uri: String,
        sourceId: String? = null,
        generation: Long = 0L,
        availability: String = "Available",
        dateAdded: Long = 0L,
    ) {
        contentDao.upsertContent(ContentEntity(contentId, 1L))
        contentDao.upsertIdentity(ContentIdentityEntity("identity-$contentId", contentId))
        val locatorId = "locator-${contentId.removePrefix("content-")}"
        contentDao.upsertLocator(
            MediaLocatorEntity(
                locatorId = locatorId,
                contentId = contentId,
                identityKey = "identity-$contentId",
                sourceId = sourceId,
                uriValue = uri,
                displayName = "$contentId.mp3",
                mimeType = "audio/mpeg",
                title = "Title ${contentId.removePrefix("content-")}",
                artist = null,
                album = null,
                durationMs = 1_000L,
                folder = null,
                dateAddedSeconds = dateAdded,
                origin = "OpenDocument",
                kind = "Audio",
                sizeBytes = 10L,
                videoWidth = 0,
                videoHeight = 0,
                availability = availability,
                seenGeneration = generation,
                updatedAtMs = 1L,
            ),
        )
        contentDao.upsertActiveLocator(ActiveMediaLocatorEntity(contentId, locatorId))
        assertNotNull(contentDao.summary(contentId))
    }

    private fun textTrack(trackId: String, contentId: String) =
        TimedTextTrackEntity(
            trackId = trackId,
            contentId = contentId,
            origin = "Sidecar",
            locatorIdentity = "revision:$trackId",
            revisionId = null,
            embeddedTrackId = null,
            generatedSlotId = null,
            displayName = "$trackId.srt",
            mimeType = "application/x-subrip",
            format = "srt",
            language = null,
            label = null,
            matchBasis = "UserSelected",
            readiness = "Ready",
            failureMessage = null,
        )
}
