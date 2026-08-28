package io.github.zvensmoluya.vortexcue.synctext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTextSchedulingTest {
    @Test
    fun `failed UIDT scheduling retries when app reaches foreground`() {
        var submissions = 0
        val gate =
            UidtScheduleGate(
                hasPendingJob = { false },
                submit = {
                    submissions += 1
                    if (submissions == 1) 0 else 1
                },
                successCode = 1,
            )

        assertEquals(UidtScheduleOutcome.DeferredUntilForeground, gate.schedule())
        assertEquals(UidtScheduleOutcome.Scheduled, gate.retryDeferred())
        assertNull(gate.retryDeferred())
        assertEquals(2, submissions)
    }

    @Test
    fun `pending UIDT job satisfies a deferred retry without resubmission`() {
        var pending = false
        var submissions = 0
        val gate =
            UidtScheduleGate(
                hasPendingJob = { pending },
                submit = {
                    submissions += 1
                    0
                },
                successCode = 1,
            )

        assertEquals(UidtScheduleOutcome.DeferredUntilForeground, gate.schedule())
        pending = true
        assertEquals(UidtScheduleOutcome.AlreadyScheduled, gate.retryDeferred())
        assertNull(gate.retryDeferred())
        assertEquals(1, submissions)
    }

    @Test
    fun `UIDT scheduler exceptions remain retryable from foreground`() {
        var shouldThrow = true
        val gate =
            UidtScheduleGate(
                hasPendingJob = {
                    if (shouldThrow) error("background scheduling rejected")
                    false
                },
                submit = { 1 },
                successCode = 1,
            )

        assertEquals(UidtScheduleOutcome.DeferredUntilForeground, gate.schedule())
        shouldThrow = false
        assertEquals(UidtScheduleOutcome.Scheduled, gate.retryDeferred())
    }

    @Test
    fun `foreground reconciliation replaces a job that disappeared after being observed`() {
        var pending = true
        var submissions = 0
        val gate =
            UidtScheduleGate(
                hasPendingJob = { pending },
                submit = {
                    submissions += 1
                    1
                },
                successCode = 1,
            )

        assertEquals(UidtScheduleOutcome.AlreadyScheduled, gate.schedule())
        pending = false
        assertEquals(UidtScheduleOutcome.Scheduled, gate.schedule())
        assertEquals(1, submissions)
    }
}
