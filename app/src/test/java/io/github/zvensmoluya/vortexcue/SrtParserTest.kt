package io.github.zvensmoluya.vortexcue

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SrtParserTest {
    @Test
    fun `parses indexed multiline cues and normalizes line endings`() {
        val content =
            "\uFEFF1\r\n" +
                "00:00:01,000 --> 00:00:02,500\r\n" +
                "第一行\r\n第二行\r\n\r\n" +
                "2\r\n" +
                "00:00:03.25 --> 00:00:04.5\r\n" +
                "Next"

        val cues = SrtParser.parse(content)

        assertEquals(2, cues.size)
        assertEquals(1_000, cues[0].startMs)
        assertEquals(2_500, cues[0].endMs)
        assertEquals("第一行\n第二行", cues[0].text)
        assertEquals(3_250, cues[1].startMs)
        assertEquals(4_500, cues[1].endMs)
    }

    @Test
    fun `accepts cues without numeric indexes and sorts them`() {
        val content =
            "00:00:05,000 --> 00:00:06,000\nLater\n\n" +
                "00:00:01,000 --> 00:00:02,000\nEarlier"

        val cues = SrtParser.parse(content)

        assertEquals(listOf("Earlier", "Later"), cues.map { it.text })
    }

    @Test
    fun `removes common presentation markup`() {
        val content =
            "1\n00:00:01,000 --> 00:00:02,000\n" +
                "{\\an8}<i>Hello</i><br><font color=\"#fff\">A &amp; B</font>"

        val cue = SrtParser.parse(content).single()

        assertEquals("Hello\nA & B", cue.text)
    }

    @Test
    fun `decodes UTF-16 little endian with BOM`() {
        val content = "1\n00:00:00,100 --> 00:00:00,900\nHello"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + content.toByteArray(StandardCharsets.UTF_16LE)

        val cues = SrtParser.parse(bytes)

        assertEquals("Hello", cues.single().text)
    }

    @Test
    fun `rejects invalid ranges`() {
        val content = "1\n00:00:02,000 --> 00:00:01,000\nInvalid"

        assertThrows(SrtParseException::class.java) { SrtParser.parse(content) }
    }

    @Test
    fun `rejects undecodable bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            SrtParser.parse(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }
}
