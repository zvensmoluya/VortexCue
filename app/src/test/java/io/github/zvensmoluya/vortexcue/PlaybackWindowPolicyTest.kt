package io.github.zvensmoluya.vortexcue

import io.github.zvensmoluya.vortexcue.app.shouldKeepPlaybackScreenOn
import io.github.zvensmoluya.vortexcue.core.model.PlayerSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWindowPolicyTest {
    @Test
    fun `normal playback follows system screen timeout`() {
        assertFalse(
            shouldKeepPlaybackScreenOn(
                isPlayerDestination = true,
                surface = PlayerSurface.Normal,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `glow stays visible only while actively playing`() {
        assertTrue(
            shouldKeepPlaybackScreenOn(
                isPlayerDestination = true,
                surface = PlayerSurface.Glow,
                isPlaying = true,
            ),
        )
        assertFalse(
            shouldKeepPlaybackScreenOn(
                isPlayerDestination = true,
                surface = PlayerSurface.Glow,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `calibration stays awake and home never does`() {
        assertTrue(
            shouldKeepPlaybackScreenOn(
                isPlayerDestination = true,
                surface = PlayerSurface.Calibration,
                isPlaying = false,
            ),
        )
        assertFalse(
            shouldKeepPlaybackScreenOn(
                isPlayerDestination = false,
                surface = PlayerSurface.Glow,
                isPlaying = true,
            ),
        )
    }
}
