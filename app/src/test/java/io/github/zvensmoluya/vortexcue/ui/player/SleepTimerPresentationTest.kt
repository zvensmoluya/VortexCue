package io.github.zvensmoluya.vortexcue.ui.player

import io.github.zvensmoluya.vortexcue.playback.SleepTimerState
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

    @Test
    fun `remaining duration is rounded up without showing seconds`() {
        assertEquals("即将停止", formatSleepTimerRemaining(0L))
        assertEquals("不到 1 分钟", formatSleepTimerRemaining(12_345L))
        assertEquals("2 分钟", formatSleepTimerRemaining(60_001L))
        assertEquals("2 小时", formatSleepTimerRemaining(120L * 60_000L))
        assertEquals("2 小时 17 分钟", formatSleepTimerRemaining(137L * 60_000L))
    }

    @Test
    fun `remaining ring reports active timer fraction`() {
        assertEquals(
            0.5f,
            sleepTimerRemainingFraction(
                SleepTimerState(
                    isActive = true,
                    remainingMs = 15L * 60_000L,
                    configuredDurationMs = 30L * 60_000L,
                ),
            ),
            0.0001f,
        )
        assertEquals(
            0f,
            sleepTimerRemainingFraction(
                SleepTimerState(
                    isActive = false,
                    remainingMs = 15L * 60_000L,
                    configuredDurationMs = 30L * 60_000L,
                ),
            ),
            0.0001f,
        )
    }
}
