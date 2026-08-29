package io.github.zvensmoluya.vortexcue.data.db

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.synctext.GenerationRuntimePhase
import io.github.zvensmoluya.vortexcue.synctext.GenerationRuntimeStatusWriter
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
    fun streamActivitySurvivesWriterRecreationAndOnlyPublishesRealDeltas() = runBlocking {
        insertContent("stream", "content://media/stream")
        val syncTextDao = database.syncTextDao()
        syncTextDao.upsertCredential(
            CredentialMetadataEntity("credential", "Credential", "Shared", "••••", 1L, 1L),
        )
        syncTextDao.upsertScheme(
            SyncTextSchemeEntity(
                schemeId = "scheme",
                name = "Scheme",
                asrProfile = "OpenRouter",
                asrCredentialId = "credential",
                translationProtocol = "OpenAIChatCompletions",
                translationEndpoint = null,
                translationModel = "model",
                translationCredentialId = "credential",
                defaultTargetLanguage = "zh-CN",
                defaultPresentationMode = "Primary",
                isDefault = true,
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        )
        syncTextDao.upsertTask(
            GenerationTaskEntity(
                taskId = "task",
                contentId = "stream",
                schemeId = "scheme",
                kind = "TranslationDerivation",
                activeDedupeKey = "stream-translation",
                state = "Running",
                stage = "Translating",
                queuePosition = 0,
                progress = 0f,
                targetLanguage = "zh-CN",
                waitingForNetwork = false,
                paused = false,
                needsAttention = false,
                schemeBlocked = false,
                currentAttemptId = "attempt",
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        )

        var now = 1_000L
        val firstWriter = GenerationRuntimeStatusWriter(syncTextDao) { now }
        firstWriter.updateTask("task", GenerationRuntimePhase.PreparingTranslation)
        firstWriter.recordTaskStreamActivity("task", "abcd")
        now += 100L
        firstWriter.recordTaskStreamActivity("task", "界")

        assertEquals(4, syncTextDao.runtimeStatus("task:task")?.observedItemCount)
        firstWriter.flushTaskStreamActivity("task")
        assertEquals(8, syncTextDao.runtimeStatus("task:task")?.observedItemCount)

        now += 250L
        val restoredWriter = GenerationRuntimeStatusWriter(syncTextDao) { now }
        restoredWriter.recordTaskStreamActivity("task", "xy")

        val restored = syncTextDao.runtimeStatus("task:task")
        assertEquals(10, restored?.observedItemCount)
        assertEquals(GenerationRuntimePhase.PreparingTranslation.name, restored?.phase)
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
        contentDao.upsertPlaylist(PlaylistEntity("playlist-root", "Root", 0L, 1L, 1L))
        contentDao.upsertPlaylistItem(PlaylistItemEntity("playlist-root", "folder", 0L, 1L))
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

        assertEquals(setOf("orphan"), candidates)
    }

    @Test
    fun pagingReadsActiveLocatorMetadataWithoutContentCopy() = runBlocking {
        contentDao.upsertPlaylist(PlaylistEntity("library", "Library", 0L, 1L, 1L))
        repeat(55) { index ->
            val id = "content-$index"
            insertContent(id, "content://media/$index", dateAdded = index.toLong())
            contentDao.upsertDisposition(ContentDispositionEntity(id, true, index.toLong()))
            contentDao.upsertPlaylistItem(PlaylistItemEntity("library", id, index.toLong(), index.toLong()))
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
                assertEquals(listOf("content", "playlist", "playlist_entry"), tables)
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
    fun versionSevenMigrationPreservesDataInVersionNine() = runBlocking {
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

        helper.runMigrationsAndValidate(9, listOf(MIGRATION_8_9)).use { connection ->
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
    fun versionEightMigrationPreservesFoldersAsPlaylistsWithoutInventingRecency() = runBlocking {
        val helper =
            MigrationTestHelper(
                databasePath = temporaryFolder.root.resolve("migration-v8-v9.db").toPath(),
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(8).use { connection ->
            connection.prepare(
                "INSERT INTO content (content_id, created_at_ms, subtitle_offset_ms) VALUES ('preserved-content', 1, 0)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO content_folder (folder_id, name, created_at_ms, updated_at_ms) VALUES ('folder:test', '睡前', 2, 3)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO content_folder_membership (content_id, folder_id, moved_at_ms) VALUES ('preserved-content', 'folder:test', 4)",
            ).use { it.step() }
        }

        helper.runMigrationsAndValidate(9, listOf(MIGRATION_8_9)).use { connection ->
            connection.prepare(
                "SELECT name, sort_position, last_played_at_ms FROM playlist WHERE playlist_id = 'folder:test'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("睡前", statement.getText(0))
                assertEquals(0L, statement.getLong(1))
                assertTrue(statement.isNull(2))
            }
            connection.prepare(
                "SELECT content_id, sort_position, added_at_ms FROM playlist_item WHERE playlist_id = 'folder:test'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("preserved-content", statement.getText(0))
                assertEquals(0L, statement.getLong(1))
                assertEquals(4L, statement.getLong(2))
            }
            connection.prepare("SELECT content_id FROM content WHERE content_id = 'preserved-content'").use { statement ->
                assertTrue(statement.step())
            }
        }
    }

    @Test
    fun versionNineMigrationCreatesDefaultMembershipAndPreservesDurableData() = runBlocking {
        val helper =
            MigrationTestHelper(
                databasePath = temporaryFolder.root.resolve("migration-v9-v10.db").toPath(),
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(9).use { connection ->
            connection.prepare(
                "INSERT INTO playlist (playlist_id, name, sort_position, created_at_ms, updated_at_ms, last_played_at_ms) " +
                    "VALUES ('existing-default', '默认收藏', 0, 1, 1, NULL), " +
                    "('alpha', 'Alpha', 1, 2, 2, 99), ('beta', 'Beta', 2, 3, 3, NULL)",
            ).use { it.step() }
            val media =
                listOf(
                    Triple("orphan-audio", "Audio", true),
                    Triple("shared-audio", "Audio", true),
                    Triple("orphan-audio-older", "Audio", true),
                    Triple("ignored-video", "Video", false),
                    Triple("undecided-video", "Video", null),
                    Triple("collected-video", "Video", true),
                )
            media.forEachIndexed { index, (contentId, kind, disposition) ->
                connection.prepare(
                    "INSERT INTO content (content_id, created_at_ms, subtitle_offset_ms) " +
                        "VALUES ('$contentId', ${index + 10}, 0)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO content_identity (identity_key, content_id) " +
                        "VALUES ('identity-$contentId', '$contentId')",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO media_locator (locator_id, content_id, identity_key, source_id, uri_value, " +
                        "display_name, mime_type, title, artist, album, duration_ms, folder, date_added_seconds, " +
                        "origin, kind, size_bytes, video_width, video_height, availability, seen_generation, updated_at_ms) " +
                        "VALUES ('locator-$contentId', '$contentId', 'identity-$contentId', NULL, " +
                        "'content://media/$contentId', '$contentId', NULL, '$contentId', NULL, NULL, 1000, " +
                        "'Music', ${index + 10}, 'MediaStore', '$kind', 10, 0, 0, 'Available', 1, 10)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO active_media_locator (content_id, locator_id) " +
                        "VALUES ('$contentId', 'locator-$contentId')",
                ).use { it.step() }
                disposition?.let { collected ->
                    connection.prepare(
                        "INSERT INTO content_disposition (content_id, is_collected, decided_at_ms) " +
                            "VALUES ('$contentId', ${if (collected) 1 else 0}, ${100 - index})",
                    ).use { it.step() }
                }
            }
            connection.prepare(
                "INSERT INTO playlist_item (playlist_id, content_id, sort_position, added_at_ms) VALUES " +
                    "('alpha', 'shared-audio', 0, 20), ('beta', 'shared-audio', 0, 21), " +
                    "('alpha', 'collected-video', 1, 22)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO timed_text_track (track_id, content_id, origin, locator_identity, revision_id, " +
                    "embedded_track_id, display_name, mime_type, format, language, label, match_basis, readiness, " +
                    "failure_message, is_rejected, generated_slot_id) VALUES " +
                    "('track-shared', 'shared-audio', 'Sidecar', 'sidecar:shared', NULL, NULL, 'shared.srt', " +
                    "'application/x-subrip', 'srt', NULL, NULL, 'ExactStem', 'Ready', NULL, 0, NULL)",
            ).use { it.step() }
        }

        helper.runMigrationsAndValidate(10, listOf(MIGRATION_9_10)).use { connection ->
            connection.prepare("SELECT playlist_id FROM playlist WHERE system_role = 'DefaultFavorite'").use { statement ->
                assertTrue(statement.step())
                assertEquals("existing-default", statement.getText(0))
            }
            connection.prepare(
                "SELECT content_id FROM playlist_item WHERE playlist_id = 'existing-default' ORDER BY sort_position",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("orphan-audio", statement.getText(0))
                assertTrue(statement.step())
                assertEquals("orphan-audio-older", statement.getText(0))
                assertFalse(statement.step())
            }
            connection.prepare(
                "SELECT playlist_id FROM playlist_item WHERE content_id = 'shared-audio' ORDER BY playlist_id",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("alpha", statement.getText(0))
                assertTrue(statement.step())
                assertEquals("beta", statement.getText(0))
                assertFalse(statement.step())
            }
            connection.prepare("SELECT last_played_at_ms FROM playlist WHERE playlist_id = 'alpha'").use { statement ->
                assertTrue(statement.step())
                assertEquals(99L, statement.getLong(0))
            }
            connection.prepare("SELECT track_id FROM timed_text_track WHERE content_id = 'shared-audio'").use { statement ->
                assertTrue(statement.step())
                assertEquals("track-shared", statement.getText(0))
            }
            connection.prepare(
                "SELECT content_id FROM content WHERE content_id IN " +
                    "('ignored-video', 'undecided-video', 'collected-video') ORDER BY content_id",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("collected-video", statement.getText(0))
                assertTrue(statement.step())
                assertEquals("ignored-video", statement.getText(0))
                assertFalse(statement.step())
            }
            connection.prepare("SELECT is_collected FROM content_disposition WHERE content_id = 'ignored-video'").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun versionTenMigrationCreatesWorkModelAndPreservesPlaylistEntriesAndQueue() = runBlocking {
        val helper =
            MigrationTestHelper(
                databasePath = temporaryFolder.root.resolve("migration-v10-v11.db").toPath(),
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(10).use { connection ->
            connection.prepare(
                "INSERT INTO content (content_id, created_at_ms, subtitle_offset_ms) VALUES ('part', 1, 0)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO playlist (playlist_id, name, sort_position, created_at_ms, updated_at_ms, last_played_at_ms, system_role) " +
                    "VALUES ('playlist', '测试', 0, 1, 1, NULL, NULL)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO playlist_item (playlist_id, content_id, sort_position, added_at_ms) " +
                    "VALUES ('playlist', 'part', 3, 4)",
            ).use { it.step() }
            connection.prepare(
                "INSERT INTO current_queue_entry (content_id, position, added_at_ms) VALUES ('part', 0, 5)",
            ).use { it.step() }
        }

        helper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11)).use { connection ->
            connection.prepare(
                "SELECT entry_id, playlist_id, content_id, work_id, sort_position FROM playlist_entry",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("content:playlist:part", statement.getText(0))
                assertEquals("playlist", statement.getText(1))
                assertEquals("part", statement.getText(2))
                assertTrue(statement.isNull(3))
                assertEquals(3L, statement.getLong(4))
            }
            connection.prepare("PRAGMA table_info(current_queue_entry)").use { statement ->
                val columns = buildList {
                    while (statement.step()) add(statement.getText(1))
                }
                assertTrue("origin_work_id" in columns)
                assertTrue("origin_playlist_entry_id" in columns)
            }
            connection.prepare("SELECT COUNT(*) FROM work").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun versionElevenAddsOffReasoningDefaultsToConfigAndAttemptSnapshots() = runBlocking {
        val path = temporaryFolder.root.resolve("migration-v11-v12.db").toPath()
        val helper =
            MigrationTestHelper(
                databasePath = path,
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(11).close()

        helper.runMigrationsAndValidate(12, listOf(MIGRATION_11_12)).use { connection ->
            listOf("sync_text_scheme", "generation_attempt").forEach { table ->
                connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
                    var found = false
                    while (statement.step()) {
                        if (statement.getText(1) == "translation_reasoning_level") {
                            found = true
                            assertEquals("'Off'", statement.getText(4))
                        }
                    }
                    assertTrue(found)
                }
            }
        }
    }

    @Test
    fun versionTwelveAddsDurableGenerationRuntimeFacts() = runBlocking {
        val path = temporaryFolder.root.resolve("migration-v12-v13.db").toPath()
        val helper =
            MigrationTestHelper(
                databasePath = path,
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(12).close()

        helper.runMigrationsAndValidate(13, listOf(MIGRATION_12_13)).use { connection ->
            connection.prepare("PRAGMA table_info(generation_runtime_status)").use { statement ->
                val columns = buildSet {
                    while (statement.step()) add(statement.getText(1))
                }
                assertTrue("runtime_id" in columns)
                assertTrue("task_id" in columns)
                assertTrue("phase" in columns)
                assertTrue("observed_item_count" in columns)
                assertTrue("expected_item_count" in columns)
                assertTrue("phase_started_at_ms" in columns)
            }
        }
    }

    @Test
    fun versionThirteenAddsFrozenGenerationInputs() = runBlocking {
        val path = temporaryFolder.root.resolve("migration-v13-v14.db").toPath()
        val helper =
            MigrationTestHelper(
                databasePath = path,
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(13).close()

        helper.runMigrationsAndValidate(14, listOf(MIGRATION_13_14)).use { connection ->
            connection.prepare("PRAGMA table_info(generation_task_snapshot)").use { statement ->
                val columns = buildSet {
                    while (statement.step()) add(statement.getText(1))
                }
                assertTrue("media_locator_id" in columns)
                assertTrue("input_source_revision_id" in columns)
                assertTrue("input_source_revision_hash" in columns)
                assertTrue("activation_track_id" in columns)
            }
        }
    }

    @Test
    fun versionFourteenAddsFrozenSourcePreparationCheckpoint() = runBlocking {
        val path = temporaryFolder.root.resolve("migration-v14-v15.db").toPath()
        val helper =
            MigrationTestHelper(
                databasePath = path,
                schemaDirectoryPath = File("schemas").toPath(),
                driver = BundledSQLiteDriver(),
                databaseClass = VortexDatabase::class,
                databaseFactory = { VortexDatabase_Impl() },
                autoMigrationSpecs = emptyList(),
            )
        helper.createDatabase(14).close()

        helper.runMigrationsAndValidate(15, listOf(MIGRATION_14_15)).use { connection ->
            connection.prepare("PRAGMA table_info(generation_attempt)").use { statement ->
                val columns = buildSet {
                    while (statement.step()) add(statement.getText(1))
                }
                assertTrue("source_preparation_policy_version" in columns)
                assertTrue("source_preparation_prompt_sha256" in columns)
            }
            connection.prepare("PRAGMA table_info(source_preparation_checkpoint)").use { statement ->
                val columns = buildSet {
                    while (statement.step()) add(statement.getText(1))
                }
                assertTrue("attempt_id" in columns)
                assertTrue("input_source_sha256" in columns)
                assertTrue("output_artifact_id" in columns)
                assertTrue("policy_version" in columns)
                assertTrue("prompt_sha256" in columns)
            }
        }
    }

    @Test
    fun versionSixIsDestructivelyRebuiltAsVersionNine() = runBlocking {
        val path = temporaryFolder.root.resolve("migration-v6-v9.db").toPath()
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
