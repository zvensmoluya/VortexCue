package io.github.zvensmoluya.vortexcue

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LrcParserTest {
    @Test
    fun `parses metadata offset and derives cue ends`() {
        val content =
            "[ar:Artist]\n" +
                "[offset:500]\n" +
                "[00:01.00]First\n" +
                "[00:02.25]Second"

        val cues = LrcParser.parse(content)

        assertEquals(1_500L, cues[0].startMs)
        assertEquals(2_750L, cues[0].endMs)
        assertEquals("First", cues[0].text)
        assertEquals(2_750L, cues[1].startMs)
        assertEquals(Long.MAX_VALUE, cues[1].endMs)
    }

    @Test
    fun `expands repeated timestamps and combines translations`() {
        val content =
            "[00:01.00]Primary\n" +
                "[00:01.00]Secondary\n" +
                "[00:03.00][00:05.00]Again"

        val cues = LrcParser.parse(content)

        assertEquals(listOf(1_000L, 3_000L, 5_000L), cues.map { it.startMs })
        assertEquals("Primary\nSecondary", cues[0].text)
        assertEquals("Again", cues[1].text)
        assertEquals("Again", cues[2].text)
    }

    @Test
    fun `blank timed lines clear the previous lyric`() {
        val cues = LrcParser.parse("[00:01.00]Visible\n[00:02.00]")

        assertEquals("Visible", cues[0].text)
        assertEquals("", cues[1].text)
    }

    @Test
    fun `rejects files without timestamps`() {
        assertThrows(LrcParseException::class.java) { LrcParser.parse("[ar:Artist]") }
    }

    @Test
    fun `subtitle parser detects content when extension is unknown`() {
        val cues = SubtitleParser.parse("lyrics.txt", "[00:01.00]Detected".encodeToByteArray())

        assertEquals("Detected", cues.single().text)
    }

    @Test
    fun `decodes common GB18030 lyrics`() {
        val bytes = "[00:01.00]夜深了".toByteArray(Charset.forName("GB18030"))

        val cues = SubtitleParser.parse("lyrics.lrc", bytes)

        assertEquals("夜深了", cues.single().text)
    }
}
