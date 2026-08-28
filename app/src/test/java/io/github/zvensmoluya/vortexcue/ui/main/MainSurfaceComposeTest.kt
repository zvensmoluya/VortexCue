package io.github.zvensmoluya.vortexcue.ui.main

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.data.ContentDisposition
import io.github.zvensmoluya.vortexcue.data.ContentSummary
import io.github.zvensmoluya.vortexcue.data.PlaybackItem
import io.github.zvensmoluya.vortexcue.data.PlaybackRecoveryProjection
import io.github.zvensmoluya.vortexcue.data.PlaylistSummary
import io.github.zvensmoluya.vortexcue.data.WorkDetails
import io.github.zvensmoluya.vortexcue.data.WorkPartSummary
import io.github.zvensmoluya.vortexcue.data.WorkSummary
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
import io.github.zvensmoluya.vortexcue.ui.player.PlaybackSnapshot
import io.github.zvensmoluya.vortexcue.ui.queue.QueueItemRow
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainSurfaceComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fourTabsAndCentralImportExposeIndependentActions() {
        var selected = MainTab.Playlists
        var importRequests = 0
        compose.setContent {
            VortexCueTheme {
                MainNavigationBar(
                    selectedTab = selected,
                    onTabChanged = { selected = it },
                    onOpenImport = { importRequests += 1 },
                )
            }
        }

        listOf("歌单", "播放列表", "配置", "设置").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithContentDescription("添加内容", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("播放列表").performClick()
        compose.onNodeWithText("添加").performClick()

        compose.runOnIdle {
            assertEquals(MainTab.Queue, selected)
            assertEquals(1, importRequests)
        }
    }

    @Test
    fun miniPlayerOpensOnlyOnClickAndKeepsPlaybackSeparate() {
        var openRequests = 0
        var playRequests = 0
        compose.setContent {
            VortexCueTheme {
                MiniPlayer(
                    title = "当前标题",
                    isPlaying = false,
                    isBuffering = false,
                    positionMs = 30_000L,
                    durationMs = 90_000L,
                    onOpen = { openRequests += 1 },
                    onPlayPause = { playRequests += 1 },
                )
            }
        }

        val card = compose.onNodeWithText("当前标题").assertHasClickAction()
        assertEquals(false, card.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        card.performClick()
        compose.onNodeWithText("00:30 · -01:00").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onNodeWithContentDescription("播放").performClick()

        compose.runOnIdle {
            assertEquals(1, openRequests)
            assertEquals(1, playRequests)
        }
    }

    @Test
    fun miniPlayerUsesPersistedTimingUntilRuntimeTimingIsAvailable() {
        val recovery =
            PlaybackRecoveryProjection(
                queue =
                    listOf(
                        PlaybackItem(
                            contentId = "content",
                            locatorId = "locator",
                            media = MediaSourceRef(Uri.parse("content://test/content"), "恢复标题.mp3", "audio/mpeg"),
                            availability = ContentAvailability.Available,
                        ),
                    ),
                positionMs = 30_000L,
                durationMs = 90_000L,
            )

        assertEquals(
            MiniPlayerUiState("恢复标题", false, 30_000L, 90_000L),
            resolveMiniPlayerUiState(recovery, PlaybackSnapshot()),
        )
        assertEquals(
            MiniPlayerUiState("恢复标题", true, 45_000L, 120_000L),
            resolveMiniPlayerUiState(
                recovery,
                PlaybackSnapshot(positionMs = 45_000L, durationMs = 120_000L, isPlaying = true),
            ),
        )
    }

    @Test
    fun emptyContentOffersOneImportEntrypoint() {
        val calls = mutableListOf<String>()
        compose.setContent {
            VortexCueTheme {
                EmptyContent(onAddContent = { calls += "hub" })
            }
        }

        compose.onNodeWithText("添加内容").performClick()

        compose.runOnIdle { assertEquals(listOf("hub"), calls) }
    }

    @Test
    fun queueRowSelectsAndRemovesTheSameCurrentQueueItem() {
        var selected = 0
        var removed = 0
        compose.setContent {
            VortexCueTheme {
                QueueItemRow(
                    itemKey = "content",
                    index = 0,
                    itemCount = 1,
                    title = "示例内容",
                    artist = "作者",
                    isCurrent = true,
                    onSelect = { selected += 1 },
                    onMove = { _, _ -> },
                    onRemove = { removed += 1 },
                )
            }
        }

        compose.onNodeWithText("正在播放").assertExists()
        compose.onNodeWithText("示例内容").performClick()
        compose.onNodeWithContentDescription("移除 示例内容").performClick()

        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(1, removed)
        }
    }

    @Test
    fun playlistOverviewShowsRecentShortcutAndAllContentWithoutPageTitle() {
        var opens = 0
        compose.setContent {
            VortexCueTheme {
                PlaylistOverview(
                    playlists = listOf(PlaylistSummary("all-list", "练习", 3)),
                    recentPlaylists = listOf(PlaylistSummary("recent-list", "睡前", 5, lastPlayedAtMs = 1L)),
                    works = emptyList(),
                    allContentCount = 8,
                    reorderMode = false,
                    listState = rememberLazyListState(),
                    onOpenAllContent = {},
                    onOpenPlaylist = { opens += 1 },
                    onOpenWork = {},
                    onCreatePlaylist = {},
                    onRenamePlaylist = {},
                    onDeletePlaylist = {},
                    onReorderModeChanged = {},
                    onMovePlaylist = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("最近听过").assertExists()
        compose.onNodeWithText("全部歌单").assertExists()
        compose.onNodeWithText("全部内容").assertExists()
        compose.onNodeWithText("首页").assertDoesNotExist()
        compose.onNodeWithText("搜索内容").assertDoesNotExist()
        compose.onNodeWithText("睡前").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun playlistOverviewKeepsImportedWorkAsOnePersistentEntry() {
        var openedWork: String? = null
        compose.setContent {
            VortexCueTheme {
                PlaylistOverview(
                    playlists = emptyList(),
                    recentPlaylists = emptyList(),
                    works =
                        listOf(
                            WorkSummary(
                                workId = "work-test",
                                sourceKey = "folder:test:rj01658524",
                                releaseCode = "RJ01658524",
                                title = "蓝空に、吐息。",
                                profileLabel = "WAV 优先 · 包含特典",
                                partCount = 16,
                                totalDurationMs = 7_200_000L,
                                lastPlayedAtMs = null,
                            ),
                        ),
                    allContentCount = 1,
                    reorderMode = false,
                    listState = rememberLazyListState(),
                    onOpenAllContent = {},
                    onOpenPlaylist = {},
                    onOpenWork = { openedWork = it },
                    onCreatePlaylist = {},
                    onRenamePlaylist = {},
                    onDeletePlaylist = {},
                    onReorderModeChanged = {},
                    onMovePlaylist = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("作品 · RJ01658524 · 16 段 · 2小时0分").assertExists()
        compose.onNodeWithText("WAV 优先 · 包含特典").assertDoesNotExist()
        compose.onNodeWithText("蓝空に、吐息。").performClick()
        compose.runOnIdle { assertEquals("work-test", openedWork) }
    }

    @Test
    fun workDetailKeepsOnlyIdentityEditionsAndParts() {
        var selectedEdition: String? = null
        val standard = summary("00_standard.wav", durationMs = 300_000L)
        val withoutSe = summary("00_without_se.wav", durationMs = 300_000L)
        val bonus = summary("bonus.wav", durationMs = 300_000L)
        val details =
            WorkDetails(
                summary =
                    WorkSummary(
                        workId = "work-test",
                        sourceKey = "folder:test:rj01658524",
                        releaseCode = "RJ01658524",
                        title = "RJ01658524",
                        profileLabel = "WAV 优先 · 标准版 · 包含特典",
                        partCount = 3,
                        totalDurationMs = 900_000L,
                        playbackPartCount = 2,
                        playbackDurationMs = 600_000L,
                        lastPlayedAtMs = 1L,
                        progressContentId = standard.contentId,
                        progressPositionMs = 60_000L,
                    ),
                parts =
                    listOf(
                        WorkPartSummary(standard, "正篇", "default", 0L, true),
                        WorkPartSummary(withoutSe, "正篇", "without-se", 1L, false),
                        WorkPartSummary(bonus, "特典", "default", 2L, true),
                    ),
            )

        compose.setContent {
            VortexCueTheme {
                WorkDetailPage(
                    details = details,
                    onBack = {},
                    onPlayFromStart = {},
                    onPlayPart = {},
                    onPartLongPress = {},
                    onSelectEdition = { selectedEdition = it },
                    onRenameWork = {},
                    onDeleteWork = {},
                )
            }
        }

        compose.onAllNodesWithText("RJ01658524").assertCountEquals(1)
        compose.onNodeWithText("内容版本").assertDoesNotExist()
        compose.onNodeWithText("2 段 · 10分钟").assertDoesNotExist()
        compose.onNodeWithText("上次听到第 1 段 · 00_standard").assertDoesNotExist()
        compose.onNodeWithText("继续播放").assertDoesNotExist()
        compose.onNodeWithText("整部加入歌单").assertDoesNotExist()
        compose.onNodeWithText("选择段落").assertDoesNotExist()
        compose.onNodeWithText("无 SE 版").performClick()
        compose.runOnIdle { assertEquals("without-se", selectedEdition) }
    }

    @Test
    fun playlistDetailExposesPlayAllAndStartsFromSelectedItem() {
        var playAll = 0
        var selectedIndex = -1
        val content = listOf(summary("第一项.mp3"), summary("第二项.mp3"))
        compose.setContent {
            VortexCueTheme {
                PlaylistDetail(
                    title = "睡前",
                    content = content,
                    isAllContent = false,
                    reorderMode = false,
                    listState = rememberLazyListState(),
                    onBack = {},
                    onPlayAll = { playAll += 1 },
                    onPlay = { selectedIndex = it },
                    onLongPress = {},
                    onReorderModeChanged = {},
                    onMove = { _, _ -> },
                    onAddContent = {},
                )
            }
        }

        compose.onNodeWithText("播放全部").performClick()
        compose.onNodeWithText("第二项").performClick()
        compose.runOnIdle {
            assertEquals(1, playAll)
            assertEquals(1, selectedIndex)
        }
    }

    @Test
    fun playlistDetailOffersBatchOrganizationActions() {
        val content = listOf(summary("第一项.mp3"), summary("第二项.mp3"))
        var addRequests = 0
        var moveRequests = 0
        var removeRequests = 0
        compose.setContent {
            VortexCueTheme {
                var organizeMode by remember { mutableStateOf(false) }
                var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                PlaylistDetail(
                    title = "睡前",
                    content = content,
                    isAllContent = false,
                    reorderMode = false,
                    listState = rememberLazyListState(),
                    onBack = {},
                    onPlayAll = {},
                    onPlay = {},
                    onLongPress = {},
                    onReorderModeChanged = {},
                    onMove = { _, _ -> },
                    onAddContent = {},
                    organizeMode = organizeMode,
                    selectedContentIds = selectedIds,
                    onOrganizeModeChanged = { organizeMode = it },
                    onSelectionChanged = { selectedIds = it },
                    onBatchAdd = { addRequests += 1 },
                    onBatchMove = { moveRequests += 1 },
                    onBatchRemove = { removeRequests += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription("歌单操作").performClick()
        compose.onNodeWithText("整理内容").performClick()
        compose.onNodeWithText("第一项").performClick()
        compose.onNodeWithText("加入歌单").performClick()
        compose.onNodeWithText("移动到").performClick()
        compose.onNodeWithText("从此移除").performClick()

        compose.runOnIdle {
            assertEquals(1, addRequests)
            assertEquals(1, moveRequests)
            assertEquals(1, removeRequests)
        }
    }

    @Test
    fun membershipSheetSavesMultiplePlaylistSelections() {
        var saved: Set<String>? = null
        compose.setContent {
            VortexCueTheme {
                PlaylistMembershipSheet(
                    title = "示例内容",
                    playlists = listOf(PlaylistSummary("first", "睡前", 1), PlaylistSummary("second", "练习", 1)),
                    initialSelection = setOf("first"),
                    onDismiss = {},
                    onSave = { ids, _ -> saved = ids },
                )
            }
        }

        compose.onNodeWithText("练习").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(setOf("first", "second"), saved) }
    }

    @Test
    fun batchTargetSheetSupportsMultipleAddTargets() {
        var saved: Set<String>? = null
        compose.setContent {
            VortexCueTheme {
                PlaylistBatchTargetSheet(
                    mode = PlaylistBatchTargetMode.Add,
                    selectedContentCount = 2,
                    playlists =
                        listOf(
                            PlaylistSummary("current", "当前歌单", 2),
                            PlaylistSummary("first", "睡前", 1),
                            PlaylistSummary("second", "练习", 1),
                        ),
                    excludedPlaylistId = "current",
                    onDismiss = {},
                    onConfirm = { ids, _ -> saved = ids },
                )
            }
        }

        compose.onNodeWithText("睡前").performClick()
        compose.onNodeWithText("练习").performClick()
        compose.onNodeWithText("加入").performClick()
        compose.runOnIdle { assertEquals(setOf("first", "second"), saved) }
    }

    @Test
    fun batchTargetSheetKeepsMoveDestinationSingle() {
        var saved: Set<String>? = null
        compose.setContent {
            VortexCueTheme {
                PlaylistBatchTargetSheet(
                    mode = PlaylistBatchTargetMode.Move,
                    selectedContentCount = 2,
                    playlists = listOf(PlaylistSummary("first", "睡前", 1), PlaylistSummary("second", "练习", 1)),
                    excludedPlaylistId = null,
                    onDismiss = {},
                    onConfirm = { ids, _ -> saved = ids },
                )
            }
        }

        compose.onNodeWithText("睡前").performClick()
        compose.onNodeWithText("练习").performClick()
        compose.onNodeWithText("移动").performClick()
        compose.runOnIdle { assertEquals(setOf("second"), saved) }
    }

    @Test
    fun contentRowKeepsClickAndLongPressAsSeparateActions() {
        var plays = 0
        var menus = 0
        val item =
            ContentSummary(
                contentId = "content",
                locatorId = "locator",
                media =
                    MediaSourceRef(
                        uri = Uri.parse("content://test/content"),
                        displayName = "长按示例.mp3",
                        mimeType = "audio/mpeg",
                    ),
                availability = ContentAvailability.Available,
                disposition = ContentDisposition.Collected,
                lastOpenedAtMs = null,
            )
        compose.setContent {
            VortexCueTheme {
                ContentRow(
                    item = item,
                    status = null,
                    onClick = { plays += 1 },
                    onLongPress = { menus += 1 },
                )
            }
        }

        compose.onNodeWithText("长按示例").performClick()
        compose.onNodeWithText("长按示例").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, plays)
            assertEquals(1, menus)
        }
    }

    private fun summary(name: String, durationMs: Long = 0L): ContentSummary =
        ContentSummary(
            contentId = name,
            locatorId = "locator-$name",
            media = MediaSourceRef(Uri.parse("content://test/$name"), name, "audio/mpeg", durationMs = durationMs),
            availability = ContentAvailability.Available,
            disposition = ContentDisposition.Collected,
            lastOpenedAtMs = null,
        )

}
