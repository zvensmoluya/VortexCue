package io.github.zvensmoluya.vortexcue

import io.github.zvensmoluya.vortexcue.subtitle.*
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

    @Test
    fun `preserves stable cue ids for generated text`() {
        val cues =
            listOf(
                SubtitleCue(
                    startMs = 100L,
                    endMs = 900L,
                    text = "Source line",
                    stableId = "cue-001",
                ),
                SubtitleCue(
                    startMs = 1_000L,
                    endMs = 2_000L,
                    text = "Target line",
                    stableId = "cue-002",
                ),
            )

        assertEquals(cues, WebVttParser.parse(NormalizedSubtitleWriter.encode(cues)))
    }
}
