package io.github.zvensmoluya.vortexcue.subtitle

class WebVttParseException(message: String) : IllegalArgumentException(message)

object WebVttParser {
    private val blockSeparator = Regex("""\n[\t ]*\n+""")
    private val timingLine =
        Regex(
            """^((?:\d+:)?[0-5]?\d:[0-5]\d\.\d{3})[\t ]+-->[\t ]+((?:\d+:)?[0-5]?\d:[0-5]\d\.\d{3})(?:[\t ].*)?$""",
        )
    private val timestampTag = Regex("""<\d{2,}:\d{2}:\d{2}\.\d{3}>""")
    private val markupTag = Regex("""</?[^>]+>""")
    private val lineBreakTag = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    fun parse(bytes: ByteArray): List<SubtitleCue> = parse(SubtitleTextDecoder.decode(bytes))

    fun parse(content: String): List<SubtitleCue> {
        val normalized =
            content
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
        if (!normalized.startsWith("WEBVTT")) {
            throw WebVttParseException("WebVTT 文件缺少 WEBVTT 头")
        }

        val cues = mutableListOf<SubtitleCue>()
        normalized
            .split(blockSeparator)
            .forEachIndexed { blockIndex, block ->
                if (blockIndex == 0 && block.startsWith("WEBVTT")) return@forEachIndexed
                val lines = block.lines().filterNot { it.isBlank() }
                if (lines.isEmpty()) return@forEachIndexed
                val first = lines.first().trimStart()
                if (
                    first.startsWith("NOTE") ||
                        first == "STYLE" ||
                        first.startsWith("REGION")
                ) {
                    return@forEachIndexed
                }

                val timingIndex = lines.indexOfFirst { "-->" in it }
                if (timingIndex < 0) {
                    throw WebVttParseException("第 ${blockIndex + 1} 个 WebVTT 块缺少时间轴")
                }
                val match = timingLine.matchEntire(lines[timingIndex].trim())
                    ?: throw WebVttParseException("第 ${blockIndex + 1} 个 WebVTT 块时间格式无效")
                val startMs = parseTimestamp(match.groupValues[1])
                val endMs = parseTimestamp(match.groupValues[2])
                if (endMs <= startMs) {
                    throw WebVttParseException("第 ${blockIndex + 1} 个 WebVTT 块结束时间无效")
                }

                val text =
                    lines
                        .drop(timingIndex + 1)
                        .joinToString("\n")
                        .let(::cleanText)
                if (text.isNotBlank()) {
                    cues +=
                        SubtitleCue(
                            startMs = startMs,
                            endMs = endMs,
                            text = text,
                            stableId = lines.getOrNull(timingIndex - 1)?.trim()?.takeIf(String::isNotBlank),
                        )
                }
            }

        if (cues.isEmpty()) throw WebVttParseException("WebVTT 中没有可显示的字幕")
        return cues.sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
    }

    private fun parseTimestamp(value: String): Long {
        val parts = value.split(':')
        val secondsAndMillis = parts.last().split('.')
        val seconds = secondsAndMillis[0].toLong()
        val milliseconds = secondsAndMillis[1].toLong()
        val minutes = parts[parts.lastIndex - 1].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + milliseconds
    }

    private fun cleanText(text: String): String =
        text
            .replace(lineBreakTag, "\n")
            .replace(timestampTag, "")
            .replace(markupTag, "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
}
