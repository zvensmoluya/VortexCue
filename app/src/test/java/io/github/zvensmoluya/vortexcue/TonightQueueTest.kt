package io.github.zvensmoluya.vortexcue

import io.github.zvensmoluya.vortexcue.playback.TonightQueue
import io.github.zvensmoluya.vortexcue.playback.moveTonightIndex
import io.github.zvensmoluya.vortexcue.playback.removeTonightIndex
import io.github.zvensmoluya.vortexcue.playback.isConfirmedTonightEnd
import io.github.zvensmoluya.vortexcue.playback.resolveTonightStartPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TonightQueueTest {
    @Test
    fun `empty plan is safe and starts inactive`() {
        val queue = TonightQueue()

        assertTrue(queue.isEmpty)
        assertEquals(null, queue.current)
        assertFalse(queue.started)
        assertEquals(queue, queue.removeAt(0))
        assertEquals(queue, queue.move(0, 1))
        assertEquals(queue, queue.startAt())
        assertEquals(queue, queue.completeAt(0))
    }

    @Test
    fun `clear returns canonical empty plan`() {
        assertEquals(TonightQueue(), TonightQueue().clear())
    }

    @Test
    fun `moving items preserves current item identity`() {
        assertEquals(0, moveTonightIndex(3, currentIndex = 1, fromIndex = 0, toIndex = 1))
        assertEquals(2, moveTonightIndex(3, currentIndex = 1, fromIndex = 1, toIndex = 2))
        assertEquals(1, moveTonightIndex(3, currentIndex = 1, fromIndex = 2, toIndex = 2))
        assertEquals(
            0,
            moveTonightIndex(
                itemCount = 3,
                currentIndex = 1,
                fromIndex = 0,
                toIndex = 2,
                preserveCurrent = false,
            ),
        )
    }

    @Test
    fun `removing items preserves or advances current item safely`() {
        assertEquals(1, removeTonightIndex(3, currentIndex = 2, removedIndex = 0))
        assertEquals(1, removeTonightIndex(3, currentIndex = 1, removedIndex = 1))
        assertEquals(0, removeTonightIndex(1, currentIndex = 0, removedIndex = 0))
    }

    @Test
    fun `completed progress restarts while unfinished progress is retained`() {
        assertEquals(42_000L, resolveTonightStartPosition(42_000L, 180_000L))
        assertEquals(0L, resolveTonightStartPosition(179_000L, 180_000L))
        assertEquals(0L, resolveTonightStartPosition(180_010L, 180_000L))
        assertEquals(42_000L, resolveTonightStartPosition(42_000L, 0L))
    }

    @Test
    fun `playback end must be near the known duration`() {
        assertFalse(isConfirmedTonightEnd(36_000L, 180_000L))
        assertTrue(isConfirmedTonightEnd(179_000L, 180_000L))
        assertTrue(isConfirmedTonightEnd(0L, 0L))
    }
}
