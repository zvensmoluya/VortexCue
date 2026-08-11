package io.github.zvensmoluya.vortexcue.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftSubtitleRequestTest {
    @Test
    fun `current result is accepted for the same media`() {
        assertTrue(
            isCurrentDraftSubtitleRequest(
                requestId = 2L,
                currentRequestId = 2L,
                mediaUriValue = "content://media/current",
                currentMediaUriValue = "content://media/current",
            ),
        )
    }

    @Test
    fun `old result is rejected after request or media changes`() {
        assertFalse(
            isCurrentDraftSubtitleRequest(
                requestId = 1L,
                currentRequestId = 2L,
                mediaUriValue = "content://media/first",
                currentMediaUriValue = "content://media/first",
            ),
        )
        assertFalse(
            isCurrentDraftSubtitleRequest(
                requestId = 2L,
                currentRequestId = 2L,
                mediaUriValue = "content://media/first",
                currentMediaUriValue = "content://media/second",
            ),
        )
    }
}
