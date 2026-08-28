package io.github.zvensmoluya.vortexcue.synctext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranslationStreamActivityTest {
    @Test
    fun `publishes the first visible delta without waiting for throttle interval`() {
        val tracker = TranslationStreamActivityTracker(nowMs = { 0L }, publishIntervalMs = 250L)

        tracker.beginAttempt("task", "batch")
        tracker.recordDelta("task", "batch", "abcd")

        assertEquals(1L, tracker.tokenEstimates.value["task"])
    }

    @Test
    fun `estimates multilingual deltas across chunk boundaries`() {
        val tracker = TranslationStreamActivityTracker(publishIntervalMs = 0L)

        tracker.beginAttempt("task", "batch")
        listOf("a", "b", "c", "d", "字幕").forEach { tracker.recordDelta("task", "batch", it) }
        tracker.finishAttempt("task")

        assertEquals(3L, tracker.tokenEstimates.value["task"])
    }

    @Test
    fun `retry replaces the discarded estimate for one stream`() {
        val tracker = TranslationStreamActivityTracker(publishIntervalMs = 0L)

        tracker.beginAttempt("task", "batch")
        tracker.recordDelta("task", "batch", "abcdefgh")
        tracker.beginAttempt("task", "batch")
        tracker.recordDelta("task", "batch", "abcd")
        tracker.finishAttempt("task")

        assertEquals(1L, tracker.tokenEstimates.value["task"])
        tracker.clear("task")
        assertFalse("task" in tracker.tokenEstimates.value)
    }
}
