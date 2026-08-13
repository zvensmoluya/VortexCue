package io.github.zvensmoluya.vortexcue

import io.github.zvensmoluya.vortexcue.playback.isConfirmedQueueEnd
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueuePolicyTest {
    @Test
    fun `queue end requires playback near known duration`() {
        assertFalse(isConfirmedQueueEnd(36_000L, 180_000L))
        assertTrue(isConfirmedQueueEnd(179_000L, 180_000L))
        assertTrue(isConfirmedQueueEnd(0L, 0L))
    }
}
