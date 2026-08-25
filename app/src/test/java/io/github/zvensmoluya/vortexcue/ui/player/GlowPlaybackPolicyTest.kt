package io.github.zvensmoluya.vortexcue.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowPlaybackPolicyTest {
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
