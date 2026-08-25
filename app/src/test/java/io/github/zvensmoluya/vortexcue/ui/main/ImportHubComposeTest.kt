package io.github.zvensmoluya.vortexcue.ui.main

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.core.model.MediaKind
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.PlaylistSummary
import io.github.zvensmoluya.vortexcue.data.SourceScanDescriptor
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
import io.github.zvensmoluya.vortexcue.library.LibrarySourceIds
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ImportHubComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: VortexDatabase
    private lateinit var repository: ContentRepository
    private var id = 0

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        repository = ContentRepository(database, nowMs = { 1_000L + id }, idFactory = { "id-${id++}" })
        runBlocking { repository.ensureDefaultPlaylist() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deniedPermissionKeepsQuietManualEntrypoints() {
        var permissionRequests = 0
        setHub(
            coordinator = LibraryCoordinatorState(hasRequestedAudioAccess = true),
            onRequestLibraryAccess = { permissionRequests += 1 },
        )

        compose.onNodeWithText("再次允许").performClick()
        compose.onNodeWithText("选择音频或视频文件").assertExists()
        compose.onNodeWithText("从其他位置查找音频").assertExists()
        compose.runOnIdle { assertEquals(1, permissionRequests) }
    }

    @Test
    fun sourceImportDefaultsToPendingAndStaysInDetailAfterMultiTargetImport() = runBlocking {
        val importedBatches = mutableListOf<List<String>>()
        val first = repository.createPlaylist("练习")
        val second = repository.createPlaylist("睡前")
        repository.applySuccessfulScan(
            descriptor = systemDescriptor(),
            media = listOf(audio("episode-10.mp3", "Episode 10"), audio("episode-2.mp3", "Episode 2")),
            textCandidates = emptyList(),
        )
        setHub(
            coordinator = LibraryCoordinatorState(hasAudioAccess = true),
            onContentImported = { importedBatches += it },
        )

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Music").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Music").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已选择 2 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("将 2 项加入「默认收藏」").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("默认收藏").performClick()
        compose.onNodeWithText("默认落点；选择其他歌单时自动让位").assertExists()
        compose.onNodeWithText("练习").performClick()
        compose.onNodeWithText("睡前").performClick()
        compose.onNodeWithText("或新建歌单").performTextInput("新建目标")
        compose.onNodeWithText("加入 3 个歌单").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("将 2 项加入 3 个歌单").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("将 2 项加入 3 个歌单").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已将 2 项加入 3 个歌单").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("添加完成").assertExists()
        compose.onNodeWithText("完成").assertExists()

        compose.onNodeWithText("查看全部").performClick()
        compose.onNodeWithText("全选").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("将 2 项加入 3 个歌单").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("将 2 项加入 3 个歌单").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("所选 2 项已在目标歌单").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Music").assertExists()
        compose.onNodeWithText("字幕").assertDoesNotExist()
        val newPlaylist = repository.playlists.first().first { it.name == "新建目标" }
        repository.allContent.first().forEach { item ->
            assertEquals(
                setOf(first.playlistId, second.playlistId, newPlaylist.playlistId),
                repository.playlistIdsForContent(item.contentId),
            )
        }
        assertTrue(repository.observeImportTargets("system:${LibrarySourceIds.SystemLibrary}:music").first().contains(newPlaylist.playlistId))
        compose.runOnIdle {
            assertEquals(2, importedBatches.size)
            assertTrue(importedBatches.all { it.size == 2 })
        }
    }

    @Test
    fun playlistContextOverridesRememberedTargetForThisImport() = runBlocking {
        val remembered = repository.createPlaylist("之前使用")
        val contextual = repository.createPlaylist("睡前")
        val alreadyImported = audio("remembered.mp3", "Remembered")
        val pending = audio("context.mp3", "Context")
        repository.applySuccessfulScan(
            descriptor = systemDescriptor(),
            media = listOf(alreadyImported, pending),
            textCandidates = emptyList(),
        )
        val sourceKey = "system:${LibrarySourceIds.SystemLibrary}:music"
        val rememberedContentId = requireNotNull(repository.contentIdFor(alreadyImported))
        val contentId = requireNotNull(repository.contentIdFor(pending))
        repository.importContent(listOf(rememberedContentId), setOf(remembered.playlistId), sourceKey)

        setHub(
            coordinator = LibraryCoordinatorState(hasAudioAccess = true),
            initialTargetPlaylistId = contextual.playlistId,
        )

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Music").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Music").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("将 1 项加入「睡前」").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("将 1 项加入「睡前」").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("添加完成").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf(contextual.playlistId), repository.playlistIdsForContent(contentId))
        assertEquals(setOf(remembered.playlistId), repository.observeImportTargets(sourceKey).first())
    }

    @Test
    fun overviewShowsFileErrorWithoutIgnoredRecoverySection() = runBlocking {
        val contentId = repository.resolveOrCreate(audio("ignored.mp3", "Ignored"))
        repository.ignore(contentId)

        setHub(
            coordinator = LibraryCoordinatorState(),
            singleFileImportError = "所选文件已不可用，请重新选择",
        )

        compose.onNodeWithText("所选文件已不可用，请重新选择").assertExists()
        compose.onNodeWithText("已不再推荐").assertDoesNotExist()
        compose.onNodeWithText("恢复").assertDoesNotExist()
    }

    private fun setHub(
        coordinator: LibraryCoordinatorState,
        initialTargetPlaylistId: String? = null,
        singleFileImportError: String? = null,
        onRequestLibraryAccess: () -> Unit = {},
        onContentImported: suspend (List<String>) -> Unit = {},
    ) {
        val playlists = runBlocking { repository.playlists.first() }
        val sources = runBlocking { repository.sources.first() }
        compose.setContent {
            VortexCueTheme {
                ImportHubScreen(
                    repository = repository,
                    sources = sources,
                    playlists = playlists,
                    coordinator = coordinator,
                    initialTargetPlaylistId = initialTargetPlaylistId,
                    singleFileImportError = singleFileImportError,
                    onContentImported = onContentImported,
                    onDismiss = {},
                    onPickMedia = {},
                    onAddFolder = {},
                    onRequestLibraryAccess = onRequestLibraryAccess,
                    onRefreshLibrary = {},
                    onRefreshSource = {},
                    onRelinkSource = {},
                    onCancelScan = {},
                    onRemoveSource = {},
                )
            }
        }
    }

    private fun systemDescriptor() =
        SourceScanDescriptor(
            sourceId = LibrarySourceIds.SystemLibrary,
            kind = LibrarySourceKind.SystemLibrary,
            rootUri = null,
            displayName = "设备媒体库",
        )

    private fun audio(name: String, title: String) =
        MediaSourceRef(
            uri = Uri.parse("content://media/$name"),
            displayName = name,
            mimeType = "audio/mpeg",
            title = title,
            durationMs = 120_000L,
            folder = "Music",
            origin = MediaOrigin.MediaStore,
            sourceId = LibrarySourceIds.SystemLibrary,
            kind = MediaKind.Audio,
        )
}
