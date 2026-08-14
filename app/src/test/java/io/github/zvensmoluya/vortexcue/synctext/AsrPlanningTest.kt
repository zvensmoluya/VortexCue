package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrPlanningTest {
    @Test
    fun `fixed windows own the complete timeline exactly once`() {
        val durationMs = 897_000L
        val windows = FixedAsrWindowPlanner().plan(durationMs)

        assertEquals(4, windows.size)
        assertEquals(0L, windows.first().ownedStartMs)
        assertEquals(durationMs, windows.last().ownedEndMs)
        windows.zipWithNext().forEach { (first, second) ->
            assertEquals(first.ownedEndMs, second.ownedStartMs)
            assertTrue(first.uploadEndMs > second.uploadStartMs)
        }
        assertEquals(298_500L, windows[0].ownedEndMs)
        assertEquals(595_500L, windows[1].ownedEndMs)
        assertEquals(892_500L, windows[2].ownedEndMs)
    }

    @Test
    fun `retry split preserves ownership and bounded upload overlap`() {
        val planner = FixedAsrWindowPlanner()
        val parent = planner.plan(300_000L).single()

        val (left, right) = planner.split(parent, mediaDurationMs = 300_000L, childOrdinalStart = 4)

        assertEquals(parent.ownedStartMs, left.ownedStartMs)
        assertEquals(left.ownedEndMs, right.ownedStartMs)
        assertEquals(parent.ownedEndMs, right.ownedEndMs)
        assertTrue(left.uploadEndMs > right.uploadStartMs)
        assertEquals("retry_split_v1", left.cutReason)
        assertEquals(parent.windowId, right.parentWindowId)
    }

    @Test
    fun `overlap segments are retained by midpoint ownership only`() {
        val windows = FixedAsrWindowPlanner().plan(597_000L)
        val fromFirst =
            AsrTimelineMerger.ownedSegments(
                windows[0],
                listOf(AsrTimestampedToken(298_000L, 300_000L, "boundary")),
            )
        val fromSecond =
            AsrTimelineMerger.ownedSegments(
                windows[1],
                listOf(AsrTimestampedToken(1_000L, 3_000L, "boundary")),
            )

        assertTrue(fromFirst.isEmpty())
        assertEquals(listOf("boundary"), fromSecond.map(AsrTimestampedToken::text))
        assertEquals(1, AsrTimelineMerger.mergeSegments(fromFirst + fromSecond).size)
    }

    @Test
    fun `encoded budget enforces byte and request concurrency then degrades to serial`() = runBlocking {
        val budget = EncodedAudioBudget(maxBytes = 32L, maxParallelRequests = 2)
        val first = budget.acquire(20L)
        val waitingForBytes = async { budget.acquire(20L) }
        yield()
        assertFalse(waitingForBytes.isCompleted)

        first.release()
        val second = waitingForBytes.await()
        assertEquals(20L, budget.snapshot().inFlightBytes)
        second.release()

        budget.degradeToSerial()
        val serialFirst = budget.acquire(1L)
        val serialWaiting = async { budget.acquire(1L) }
        yield()
        assertFalse(serialWaiting.isCompleted)
        serialFirst.release()
        serialWaiting.await().release()
        assertEquals(0L, budget.snapshot().inFlightBytes)
        assertEquals(1, budget.snapshot().parallelLimit)
    }
}
