package io.github.zvensmoluya.vortexcue.ui.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlowDisplayEditorComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsEditorPreviewsChangesAndCanHideItsAdjustmentPanel() {
        var latestProfile = DisplayProfile.Balanced
        compose.setContent {
            var profile by remember { mutableStateOf(DisplayProfile.Balanced) }
            VortexCueTheme {
                GlowDisplayEditor(
                    profile = profile,
                    isOnboarding = false,
                    onProfileChanged = {
                        profile = it
                        latestProfile = it
                    },
                    onComplete = {},
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText("微光显示").assertExists()
        compose.onNodeWithText("声音继续，画面退场。\n需要时，只让当前文字发光。").assertExists()
        compose.onNodeWithText("柔和").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(DisplayProfile.Soft, latestProfile) }

        compose.onNodeWithText("隐藏面板，查看纯净预览").performScrollTo().performClick()
        compose.onNodeWithText("窗口亮度").assertDoesNotExist()
        compose.onNodeWithContentDescription("显示微光调整面板").performClick()
        compose.onNodeWithText("窗口亮度").assertExists()
    }

    @Test
    fun onboardingReusesTheEditorWithItsOwnCompletionAction() {
        compose.setContent {
            VortexCueTheme {
                GlowDisplayEditor(
                    profile = DisplayProfile.Balanced,
                    isOnboarding = true,
                    onProfileChanged = {},
                    onComplete = {},
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText("第一次微光校准").assertExists()
        compose.onNodeWithText("完成并进入微光").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("返回播放器").assertExists()
    }

    @Test
    fun verticalPositionUsesReadableLabels() {
        assertEquals("偏上", glowVerticalPositionLabel(-0.4f))
        assertEquals("居中", glowVerticalPositionLabel(0f))
        assertEquals("偏下", glowVerticalPositionLabel(0.4f))
    }
}
