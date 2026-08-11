package io.github.zvensmoluya.vortexcue.app

import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftSubtitleRequestTest {
    @Test
    fun `current result is accepted for the same media`() {
        assertTrue(
            isCurrentDraftSubtitleRequest(
                slot = TextTrackSlot.Primary,
                requestId = 2L,
                currentRequestIds = DraftSubtitleRequestIds(primary = 2L),
                mediaUriValue = "content://media/current",
                currentMediaUriValue = "content://media/current",
            ),
        )
    }

    @Test
    fun `old result is rejected after request or media changes`() {
        assertFalse(
            isCurrentDraftSubtitleRequest(
                slot = TextTrackSlot.Primary,
                requestId = 1L,
                currentRequestIds = DraftSubtitleRequestIds(primary = 2L),
                mediaUriValue = "content://media/first",
                currentMediaUriValue = "content://media/first",
            ),
        )
        assertFalse(
            isCurrentDraftSubtitleRequest(
                slot = TextTrackSlot.Secondary,
                requestId = 2L,
                currentRequestIds = DraftSubtitleRequestIds(secondary = 2L),
                mediaUriValue = "content://media/first",
                currentMediaUriValue = "content://media/second",
            ),
        )
    }

    @Test
    fun `invalidating one subtitle slot keeps the other request current`() {
        val initial = DraftSubtitleRequestIds(primary = 3L, secondary = 7L)
        val updated = initial.invalidate(TextTrackSlot.Primary)

        assertEquals(4L, updated.current(TextTrackSlot.Primary))
        assertEquals(7L, updated.current(TextTrackSlot.Secondary))
        assertTrue(
            isCurrentDraftSubtitleRequest(
                slot = TextTrackSlot.Secondary,
                requestId = 7L,
                currentRequestIds = updated,
                mediaUriValue = "content://media/current",
                currentMediaUriValue = "content://media/current",
            ),
        )
    }
}
