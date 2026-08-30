package io.github.zvensmoluya.vortexcue.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowPlaybackPolicyTest {
    @Test
    fun `right side vertical drag adjusts and clamps subtitle brightness`() {
        assertEquals(
            62,
            adjustedGlowSubtitleLevel(
                startLevel = 16,
                accumulatedDragPx = -500f,
                gestureHeightPx = 1_000f,
            ),
        )
        assertEquals(96, adjustedGlowSubtitleLevel(16, -1_000f, 1_000f))
        assertEquals(4, adjustedGlowSubtitleLevel(16, 1_000f, 1_000f))
        assertEquals(16, adjustedGlowSubtitleLevel(16, 100f, 0f))
    }

    @Test
    fun `subtitle brightness feedback uses the supported glow range`() {
        assertEquals(0, glowSubtitleBrightnessPercent(4))
        assertEquals(13, glowSubtitleBrightnessPercent(16))
        assertEquals(100, glowSubtitleBrightnessPercent(96))
    }

    @Test
    fun `timed text is hidden after playback ends or fails`() {
        assertFalse(shouldDisplayTimedText(Player.STATE_ENDED, errorMessage = null))
        assertFalse(shouldDisplayTimedText(Player.STATE_READY, errorMessage = "播放失败"))
        assertTrue(shouldDisplayTimedText(Player.STATE_READY, errorMessage = null))
    }

    @Test
    fun `completed sleep timer blacks out paused glow until playback resumes`() {
        assertFalse(
            shouldShowGlowTimedText(
                playbackState = Player.STATE_READY,
                errorMessage = null,
                isPlaying = false,
                sleepTimerDidFinish = true,
            ),
        )
        assertTrue(
            shouldShowGlowTimedText(
                playbackState = Player.STATE_READY,
                errorMessage = null,
                isPlaying = true,
                sleepTimerDidFinish = true,
            ),
        )
    }

    @Test
    fun `ordinary pause keeps the current glow text while releasing screen wake elsewhere`() {
        assertTrue(
            shouldShowGlowTimedText(
                playbackState = Player.STATE_READY,
                errorMessage = null,
                isPlaying = false,
                sleepTimerDidFinish = false,
            ),
        )
    }
}
