package io.github.zvensmoluya.vortexcue

import io.github.zvensmoluya.vortexcue.app.resolveResumeSurface
import io.github.zvensmoluya.vortexcue.core.model.PlayerSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSurfaceTest {
    @Test
    fun `restores only persisted playback surfaces`() {
        assertEquals(PlayerSurface.Normal, PlayerSurface.restoreLastSurface(null))
        assertEquals(PlayerSurface.Normal, PlayerSurface.restoreLastSurface("Calibration"))
        assertEquals(PlayerSurface.Normal, PlayerSurface.restoreLastSurface("Unknown"))
        assertEquals(PlayerSurface.Glow, PlayerSurface.restoreLastSurface("Glow"))
    }

    @Test
    fun `calibration remains a transient surface`() {
        assertEquals(PlayerSurface.Normal, PlayerSurface.Calibration.asLastSurface())
        assertEquals(PlayerSurface.Normal, PlayerSurface.Normal.asLastSurface())
        assertEquals(PlayerSurface.Glow, PlayerSurface.Glow.asLastSurface())
    }

    @Test
    fun `resume returns to glow after calibration`() {
        assertEquals(
            PlayerSurface.Glow,
            resolveResumeSurface(PlayerSurface.Glow, glowCalibrationCompleted = true),
        )
        assertEquals(
            PlayerSurface.Calibration,
            resolveResumeSurface(PlayerSurface.Glow, glowCalibrationCompleted = false),
        )
        assertEquals(
            PlayerSurface.Normal,
            resolveResumeSurface(PlayerSurface.Normal, glowCalibrationCompleted = true),
        )
    }
}
