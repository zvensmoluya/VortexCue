package io.github.zvensmoluya.vortexcue.ui.player

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.zvensmoluya.vortexcue.playback.SleepTimerState
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SleepTimerSheetComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun inactiveTimerOnlyShowsDurationPickerAndStartAction() {
        compose.setContent {
            VortexCueTheme {
                SleepTimerSheet(
                    state =
                        SleepTimerState(
                            configuredDurationMs = 137L * 60_000L,
                        ),
                    onSetDuration = {},
                    onCancelTimer = {},
                    isGlow = false,
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("睡眠定时").assertIsDisplayed()
        compose.onNodeWithContentDescription("小时，当前 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("分钟，当前 17").assertIsDisplayed()
        compose.onNodeWithText("开始定时").assertIsDisplayed()
        compose.onNodeWithText("停止方式").assertDoesNotExist()
        compose.onNodeWithText("当前内容结束").assertDoesNotExist()
        compose.onNodeWithText("播放列表结束").assertDoesNotExist()
        compose.onNodeWithText("还剩").assertDoesNotExist()
        compose.onNodeWithText("关闭定时").assertDoesNotExist()
    }

    @Test
    fun activeTimerOnlyShowsRemainingTimeAndCancelAction() {
        compose.setContent {
            VortexCueTheme {
                SleepTimerSheet(
                    state =
                        SleepTimerState(
                            isActive = true,
                            remainingMs = 137L * 60_000L,
                            configuredDurationMs = 180L * 60_000L,
                        ),
                    onSetDuration = {},
                    onCancelTimer = {},
                    isGlow = true,
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("睡眠定时").assertIsDisplayed()
        compose.onNodeWithText("还剩").assertIsDisplayed()
        compose.onNodeWithText("2 小时 17 分钟").assertIsDisplayed()
        compose.onNodeWithText("关闭定时").assertExists()
        compose.onNodeWithText("开始定时").assertDoesNotExist()
        compose.onNodeWithContentDescription("小时，当前 3").assertDoesNotExist()
        compose.onNodeWithContentDescription("分钟，当前 0").assertDoesNotExist()
    }
}
