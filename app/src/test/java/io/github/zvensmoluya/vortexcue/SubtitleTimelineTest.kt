package io.github.zvensmoluya.vortexcue

import io.github.zvensmoluya.vortexcue.subtitle.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTimelineTest {
    private val first = SubtitleCue(startMs = 1_000, endMs = 2_000, text = "first")
    private val second = SubtitleCue(startMs = 3_000, endMs = 4_000, text = "second")
    private val timeline = SubtitleTimeline(listOf(second, first))

    @Test
    fun `start is inclusive and end is exclusive`() {
        assertNull(timeline.resolve(999))
        assertEquals(first, timeline.resolve(1_000))
        assertEquals(first, timeline.resolve(1_999))
        assertNull(timeline.resolve(2_000))
    }

    @Test
    fun `gap and positions outside the timeline resolve to null`() {
        assertNull(timeline.resolve(-1))
        assertNull(timeline.resolve(2_500))
        assertNull(timeline.resolve(4_000))
    }

    @Test
    fun `seeking in either direction resolves from the requested position`() {
        assertEquals(second, timeline.resolve(3_500))
        assertEquals(first, timeline.resolve(1_500))
    }

    @Test
    fun `latest active cue wins when cues overlap`() {
        val longCue = SubtitleCue(startMs = 0, endMs = 10_000, text = "long")
        val laterCue = SubtitleCue(startMs = 2_000, endMs = 3_000, text = "later")
        val overlapping = SubtitleTimeline(listOf(longCue, laterCue))

        assertEquals(laterCue, overlapping.resolve(2_500))
        assertEquals(longCue, overlapping.resolve(4_000))
    }
}
