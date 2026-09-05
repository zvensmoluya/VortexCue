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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        compose.onNodeWithText("预设 · 平衡").performClick()
        compose.onNodeWithText("柔和").performClick()
        compose.runOnIdle { assertEquals(DisplayProfile.Soft, latestProfile) }

        compose.onNodeWithText("纯净预览").performClick()
        compose.onNodeWithText("文字明暗").assertDoesNotExist()
        compose.onNodeWithContentDescription("显示微光调整面板").performClick()
        compose.onNodeWithText("文字明暗").assertExists()
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

        compose.onNodeWithText("微光校准").assertExists()
        compose.onNodeWithText("完成").assertExists()
        compose.onNodeWithText("返回").assertExists()
    }

    @Test
    fun rockerContinuesWhileHeldAndStopsAfterRelease() {
        var latest = DisplayProfile.Balanced
        compose.setContent {
            var profile by remember { mutableStateOf(latest) }
            GlowDisplayEditor(profile, false, { profile = it; latest = it }, {}, {})
        }
        compose.mainClock.autoAdvance = false
        val rocker = compose.onNodeWithContentDescription("回弹调节杆，向左减少，向右增加")
        rocker.performTouchInput {
            down(center)
            moveTo(Offset(width * 0.65f, center.y))
            moveTo(Offset(width * 0.85f, center.y))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { assertTrue(latest.glowSubtitleLevel > DisplayProfile.Balanced.glowSubtitleLevel) }
        rocker.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(80)
        val released = latest
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { assertEquals(released, latest) }
    }

    @Test
    fun verticalPositionUsesReadableLabels() {
        assertEquals("偏上", glowVerticalPositionLabel(-0.4f))
        assertEquals("居中", glowVerticalPositionLabel(0f))
        assertEquals("偏下", glowVerticalPositionLabel(0.4f))
    }
}
