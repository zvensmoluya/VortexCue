package io.github.zvensmoluya.vortexcue

import org.junit.Assert.assertEquals
import org.junit.Test

class RestorableProgressTest {
    @Test
    fun `keeps stored progress while controller timeline is pending`() {
        assertEquals(
            42_000L,
            resolveRestorablePosition(
                currentPositionMs = 0L,
                currentDurationMs = 0L,
                previousPositionMs = 42_000L,
            ),
        )
        assertEquals(
            180_000L,
            resolveRestorableDuration(
                currentDurationMs = 0L,
                previousDurationMs = 180_000L,
            ),
        )
    }

    @Test
    fun `uses confirmed controller progress including a real restart`() {
        assertEquals(
            0L,
            resolveRestorablePosition(
                currentPositionMs = 0L,
                currentDurationMs = 180_000L,
                previousPositionMs = 42_000L,
            ),
        )
        assertEquals(
            7_000L,
            resolveRestorablePosition(
                currentPositionMs = 7_000L,
                currentDurationMs = 0L,
                previousPositionMs = 42_000L,
            ),
        )
    }
}
