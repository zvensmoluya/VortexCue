package io.github.zvensmoluya.vortexcue.ui.player

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.zvensmoluya.vortexcue.playback.SleepTimerMode
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
    fun activeTimerShowsTheConfiguredDurationWithoutCountdownsOrPresets() {
        compose.setContent {
            VortexCueTheme {
                SleepTimerSheet(
                    state =
                        SleepTimerState(
                            mode = SleepTimerMode.Duration,
                            remainingMs = 12_345L,
                            configuredDurationMs = 137L * 60_000L,
                        ),
                    onSetDuration = {},
                    onEndOfItem = {},
                    showEndOfQueue = true,
                    onEndOfQueue = {},
                    onCancelTimer = {},
                    isGlow = true,
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("睡眠定时").assertIsDisplayed()
        compose.onNodeWithText("已开启").assertIsDisplayed()
        compose.onNodeWithContentDescription("小时，当前 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("分钟，当前 17").assertIsDisplayed()
        compose.onNodeWithText("15 分钟后停止").assertDoesNotExist()
        compose.onNodeWithText("剩余 1 分").assertDoesNotExist()
        compose.onNodeWithText("延长 15 分钟").assertDoesNotExist()
        compose.onNodeWithText("重新计时").assertExists()
        compose.onNodeWithText("关闭定时").assertExists()
    }
}
