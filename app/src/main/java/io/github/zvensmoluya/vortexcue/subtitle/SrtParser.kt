package io.github.zvensmoluya.vortexcue.subtitle

class SrtParseException(message: String) : IllegalArgumentException(message)

object SrtParser {
    private val blockSeparator = Regex("""\n[\t ]*\n+""")
    private val lineBreakTag = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val formattingTag =
        Regex("""</?(?:i|b|u|font)(?:\s+[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val assOverrideTag = Regex("""\{\\[^}]+\}""")
    private val timingLine =
        Regex(
            """^\s*(\d+):([0-5]\d):([0-5]\d)[,.](\d{1,3})\s*-->\s*""" +
                """(\d+):([0-5]\d):([0-5]\d)[,.](\d{1,3})(?:\s+.*)?\s*$""",
        )

    fun parse(bytes: ByteArray): List<SubtitleCue> = parse(SubtitleTextDecoder.decode(bytes))

    fun parse(content: String): List<SubtitleCue> {
        val normalized =
            content
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()

        if (normalized.isEmpty()) throw SrtParseException("SRT 文件为空")

        val cues =
            blockSeparator
                .split(normalized)
                .mapIndexedNotNull { blockIndex, block -> parseBlock(block, blockIndex + 1) }
                .sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })

        if (cues.isEmpty()) throw SrtParseException("SRT 中没有可显示的字幕")
        return cues
    }

    private fun parseBlock(block: String, blockNumber: Int): SubtitleCue? {
        val lines = block.lines().map { it.trimEnd() }
        val timingIndex = lines.indexOfFirst { "-->" in it }
        if (timingIndex < 0) {
            throw SrtParseException("第 $blockNumber 个字幕块缺少时间轴")
        }

        val match = timingLine.matchEntire(lines[timingIndex])
            ?: throw SrtParseException("第 $blockNumber 个字幕块时间格式无效")
        val startMs = match.toTimestampMs(1)
        val endMs = match.toTimestampMs(5)
        if (endMs <= startMs) {
            throw SrtParseException("第 $blockNumber 个字幕块的结束时间必须晚于开始时间")
        }

        val text = cleanText(lines.drop(timingIndex + 1).joinToString("\n").trim())
        if (text.isEmpty()) return null

        return SubtitleCue(startMs = startMs, endMs = endMs, text = text)
    }

    private fun MatchResult.toTimestampMs(groupOffset: Int): Long {
        val hours = groupValues[groupOffset].toLong()
        val minutes = groupValues[groupOffset + 1].toLong()
        val seconds = groupValues[groupOffset + 2].toLong()
        val milliseconds = groupValues[groupOffset + 3].padEnd(3, '0').toLong()
        return (((hours * 60) + minutes) * 60 + seconds) * 1_000 + milliseconds
    }

    private fun cleanText(text: String): String =
        text
            .replace(lineBreakTag, "\n")
            .replace(formattingTag, "")
            .replace(assOverrideTag, "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .trim()
}
