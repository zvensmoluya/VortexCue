package io.github.zvensmoluya.vortexcue.ui.synctext

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.synctext.TranslationReasoningLevel
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
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
}
