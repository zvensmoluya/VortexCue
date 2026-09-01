package io.github.zvensmoluya.vortexcue.ui.player

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlowGestureTeachingComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun initialHintOnlyTeachesHowToRevealControls() {
        compose.setContent {
            VortexCueTheme {
                GlowGestureHint(controlColor = Color.White)
            }
        }

        compose.onNodeWithText("长按屏幕，显示播放控制").assertExists()
        compose.onNodeWithText("双击左 / 中 / 右侧：快退 / 暂停 / 快进").assertDoesNotExist()
        compose.onNodeWithText("右侧上下滑动：调节文字亮度").assertDoesNotExist()
    }

    @Test
    fun expandedGuideExplainsShortcutsInsideTemporaryControls() {
        compose.setContent {
            VortexCueTheme {
                GlowGestureGuide(controlColor = Color.White)
            }
        }

        compose.onNodeWithText("双击左 / 中 / 右侧：快退 / 暂停 / 快进").assertExists()
        compose.onNodeWithText("右侧上下滑动：调节文字亮度").assertExists()
    }
}
