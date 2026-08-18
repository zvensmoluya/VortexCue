package io.github.zvensmoluya.vortexcue.ui.synctext

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
        compose.onNodeWithText("翻译服务").assertDoesNotExist()
        compose.onNodeWithText("服务协议").assertIsDisplayed()
        compose.onAllNodesWithText("语音识别").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sync-text-asr-page").assertIsDisplayed()
        compose.onAllNodesWithText("语音识别").assertCountEquals(1)
        compose.onNodeWithText("识别模型").assertIsDisplayed()

        compose.onNodeWithTag("sync-text-asr-page").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("sync-text-translation-page").assertIsDisplayed()
    }
}
