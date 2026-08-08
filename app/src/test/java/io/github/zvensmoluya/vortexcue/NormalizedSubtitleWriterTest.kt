package io.github.zvensmoluya.vortexcue

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizedSubtitleWriterTest {
    @Test
    fun `round trips normalized cues through WebVTT`() {
        val cues =
            listOf(
                SubtitleCue(
                    startMs = 1_234L,
                    endMs = 4_567L,
                    text = "First <line> & detail\n第二行",
                ),
                SubtitleCue(
                    startMs = 360_012_345L,
                    endMs = 360_015_000L,
                    text = "Long recording",
                ),
            )

        val encoded = NormalizedSubtitleWriter.encode(cues)

        assertEquals(cues, WebVttParser.parse(encoded))
    }
}
