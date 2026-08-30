package io.github.zvensmoluya.vortexcue.ui.synctext

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.synctext.TranslationReasoningLevel
import io.github.zvensmoluya.vortexcue.synctext.DiscoveredTranslationModel
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncTextConfigPagerComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tabsAndHorizontalPagerKeepTwoConfigurationPages() {
        compose.setContent {
            VortexCueTheme {
                SyncTextConfigPage(
                    state = SyncTextUiState(),
                    onAction = {},
                )
            }
        }

        compose.onNodeWithTag("sync-text-translation-page").assertIsDisplayed()
        compose.onNodeWithTag("sync-text-config-tab-0").assertIsSelected()
        compose.onNodeWithText("翻译服务").assertDoesNotExist()
        compose.onNodeWithText("服务协议").assertIsDisplayed()
        compose.onAllNodesWithText("语音识别").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sync-text-asr-page").assertIsDisplayed()
        compose.onNodeWithTag("sync-text-config-tab-1").assertIsSelected()
        compose.onAllNodesWithText("语音识别").assertCountEquals(1)
        compose.onNodeWithText("识别模型").assertIsDisplayed()

        compose.onNodeWithTag("sync-text-asr-page").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("sync-text-translation-page").assertIsDisplayed()
        compose.onNodeWithTag("sync-text-config-tab-0").assertIsSelected()
    }

    @Test
    fun focusedTextInputKeepsHorizontalDragOnCurrentPage() {
        compose.setContent {
            VortexCueTheme {
                SyncTextConfigPage(
                    state = SyncTextUiState(),
                    onAction = {},
                )
            }
        }

        compose.onAllNodesWithTag("sync-text-base-url-field").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sync-text-translation-page").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithTag("sync-text-translation-page").assertIsDisplayed()
        compose.onNodeWithTag("sync-text-config-tab-0").assertIsSelected()

        compose.onNodeWithTag("sync-text-config-tab-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sync-text-asr-page").assertIsDisplayed()
        compose.onNodeWithTag("sync-text-config-tab-1").assertIsSelected()
    }

    @Test
    fun reasoningSelectorDragsAcrossDiscreteLevelsAndStillSupportsTaps() {
        compose.setContent {
            VortexCueTheme {
                var value by remember { mutableStateOf(TranslationReasoningLevel.Off) }
                ReasoningLevelPicker(value = value, onValueChange = { value = it })
            }
        }

        compose.onNodeWithTag("translation-reasoning-off").assertIsSelected()
        compose.onNodeWithTag("translation-reasoning-selector").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("translation-reasoning-high").assertIsSelected()

        compose.onNodeWithTag("translation-reasoning-medium").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("translation-reasoning-medium").assertIsSelected()
    }

    @Test
    fun modelPickerSearchesLargeCatalogAndPreservesManualRequestIds() {
        val models =
            (0 until 300).map { index ->
                val author = if (index < 150) "Alpha" else "Zulu"
                DiscoveredTranslationModel(
                    requestId = "${author.lowercase()}/model-$index",
                    displayName = "Model $index",
                    groupLabel = author,
                )
            }
        var selected by mutableStateOf(models.first().requestId)
        compose.setContent {
            VortexCueTheme {
                ModelPicker(
                    value = selected,
                    options = models,
                    loading = false,
                    error = null,
                    onOpen = {},
                    onValueChange = { selected = it },
                )
            }
        }

        compose.onNodeWithText(models.first().requestId).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("translation-model-search").performTextInput("model-299")
        compose.waitForIdle()
        compose.onNodeWithTag("translation-model-zulu/model-299").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("zulu/model-299", selected) }

        compose.onNodeWithText("zulu/model-299").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("translation-model-search").performTextInput("gemini-3.7-flash")
        compose.onNodeWithTag("translation-model-manual-action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("gemini-3.7-flash", selected) }
    }

    @Test
    fun connectionTestActionProvidesStableIdleRunningAndFailureFeedback() {
        var connectionState by
            mutableStateOf(
                ConnectionTestUiState(
                    success = true,
                    testedFingerprint = "current",
                ),
            )
        var clickCount = 0
        compose.setContent {
            VortexCueTheme {
                ConnectionStatusRow(
                    state = connectionState,
                    stale = false,
                    enabled = true,
                    onClick = { clickCount += 1 },
                )
            }
        }

        compose.onNodeWithTag("connection-test-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("重新测试").assertIsDisplayed()
        compose.onNodeWithTag("connection-test-action").performClick()
        compose.runOnIdle { assertEquals(1, clickCount) }

        compose.runOnIdle { connectionState = ConnectionTestUiState(running = true) }
        compose.onNodeWithText("正在测试").assertIsDisplayed()
        compose.onNodeWithTag("connection-test-action").assertIsNotEnabled()

        compose.runOnIdle {
            connectionState =
                ConnectionTestUiState(
                    success = false,
                    message = "无法连接",
                    testedFingerprint = "current",
                )
        }
        compose.onNodeWithText("连接失败").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.onNodeWithTag("connection-test-action").assertIsEnabled()
    }

    @Test
    fun successfulConnectionTestBrieflyAcknowledgesCompletion() {
        var connectionState by mutableStateOf(ConnectionTestUiState(running = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VortexCueTheme {
                ConnectionStatusRow(
                    state = connectionState,
                    stale = false,
                    enabled = true,
                    onClick = {},
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()

        compose.runOnIdle {
            connectionState =
                ConnectionTestUiState(
                    success = true,
                    message = "连接成功",
                    testedFingerprint = "current",
                )
        }
        compose.mainClock.advanceTimeBy(200L)
        compose.onNodeWithText("连接成功").assertIsDisplayed()
        compose.onNodeWithTag("connection-test-action").assertIsNotEnabled()

        compose.mainClock.advanceTimeBy(1_100L)
        compose.onNodeWithText("重新测试").assertIsDisplayed()
        compose.onNodeWithTag("connection-test-action").assertIsEnabled()
    }
}
