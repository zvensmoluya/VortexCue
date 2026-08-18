package io.github.zvensmoluya.vortexcue.ui.main

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.data.ContentDisposition
import io.github.zvensmoluya.vortexcue.data.ContentSummary
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
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
        var selected = MainTab.Content
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

        listOf("内容", "播放列表", "同步文字", "设置").forEach {
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
    fun miniPlayerOpensPlayerAndTogglesPlayback() {
        var openRequests = 0
        var playRequests = 0
        compose.setContent {
            VortexCueTheme {
                MiniPlayer(
                    title = "当前标题",
                    isPlaying = false,
                    progress = 0.5f,
                    onOpen = { openRequests += 1 },
                    onPlayPause = { playRequests += 1 },
                )
            }
        }

        compose.onNodeWithText("当前标题").performClick()
        compose.onNodeWithContentDescription("播放").performClick()

        compose.runOnIdle {
            assertEquals(1, openRequests)
            assertEquals(1, playRequests)
        }
    }

    @Test
    fun emptyContentOffersAllImportEntrypoints() {
        val calls = mutableListOf<String>()
        compose.setContent {
            VortexCueTheme {
                EmptyContent(
                    onPickMedia = { calls += "file" },
                    onRequestLibraryAccess = { calls += "device" },
                    onAddFolder = { calls += "folder" },
                )
            }
        }

        compose.onNodeWithText("选择文件").performClick()
        compose.onNodeWithText("从文件夹查找").performClick()
        compose.onNodeWithText("扫描设备媒体").performClick()

        compose.runOnIdle { assertEquals(listOf("file", "folder", "device"), calls) }
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
    fun folderRowNavigatesIntoVirtualFolder() {
        var opens = 0
        compose.setContent {
            VortexCueTheme {
                FolderRow(
                    name = "练习",
                    count = 3,
                    selected = false,
                    onClick = { opens += 1 },
                )
            }
        }

        compose.onNodeWithText("练习").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
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

    @Test
    fun importHubExposesSingleFileImportAction() {
        var fileRequests = 0
        compose.setContent {
            VortexCueTheme {
                ImportHub(
                    sources = emptyList(),
                    folders = emptyList(),
                    coordinator = LibraryCoordinatorState(),
                    ignoredContent = emptyList(),
                    onRestoreIgnored = {},
                    onDismiss = {},
                    onPickMedia = { fileRequests += 1 },
                    onAddFolder = {},
                    onScanDevice = {},
                    onRefreshLibrary = {},
                    onRefreshSource = {},
                    onRelinkSource = {},
                    onCancelScan = {},
                    onRemoveSource = {},
                    onImport = { _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("选择文件并播放").performClick()
        compose.runOnIdle { assertEquals(1, fileRequests) }
    }
}
