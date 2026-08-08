package io.github.zvensmoluya.vortexcue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssParserTest {
    @Test
    fun `groups TransVortex source and target dialogue`() {
        val content =
            """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:01.80,Source,,0,0,76,,Good evening.
            Dialogue: 1,0:00:00.00,0:00:01.80,Target,,0,0,128,,晚上好。
            Dialogue: 1,0:00:02.10,0:00:05.40,Target,,0,0,142,,带有逗号, 也应该保留。
            """.trimIndent()

        val cues = AssParser.parse(content)

        assertEquals(2, cues.size)
        assertEquals("Good evening.\n晚上好。", cues[0].text)
        assertEquals("带有逗号, 也应该保留。", cues[1].text)
        assertEquals(2_100L, cues[1].startMs)
    }

    @Test
    fun `accepts SSA marked field and cleans overrides`() {
        val content =
            """
            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:01.25,0:00:03.50,Default,,0,0,0,,{\i1}First\NSecond\hline
            """.trimIndent()

        val cue = AssParser.parse(content).single()

        assertEquals(1_250L, cue.startMs)
        assertEquals("First\nSecond line", cue.text)
    }

    @Test
    fun `rejects dialogue without an events format`() {
        val content = "[Events]\nDialogue: 0,0:00:00.00,0:00:01.00,Default,text"

        assertThrows(AssParseException::class.java) { AssParser.parse(content) }
    }
}
