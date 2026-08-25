package io.github.zvensmoluya.vortexcue.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerPresentationTest {
    @Test
    fun `duration editor accepts arbitrary hours and minutes`() {
        assertEquals(137L * 60_000L, sleepTimerDurationMs(hours = 2, minutes = 17))
    }

    @Test
    fun `duration editor rejects values outside its supported range`() {
        assertEquals(23L * 60L * 60_000L, sleepTimerDurationMs(hours = 24, minutes = -1))
        assertEquals(59L * 60_000L, sleepTimerDurationMs(hours = -1, minutes = 60))
    }
}
