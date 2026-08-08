package io.github.zvensmoluya.vortexcue

class AssParseException(message: String) : IllegalArgumentException(message)

object AssParser {
    private val timestamp = Regex("""^(\d+):([0-5]\d):([0-5]\d)(?:\.(\d{1,3}))?$""")

    fun parse(bytes: ByteArray): List<SubtitleCue> = parse(SubtitleTextDecoder.decode(bytes))

    fun parse(content: String): List<SubtitleCue> {
        val lines =
            content
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
        var inEvents = false
        var format = emptyList<String>()
        val groupedText = linkedMapOf<TimeRange, MutableList<String>>()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[") && line.endsWith("]") -> {
                    inEvents = line.equals("[Events]", ignoreCase = true)
                    if (!inEvents) format = emptyList()
                }
                !inEvents -> Unit
                line.startsWith("Format:", ignoreCase = true) -> {
                    format =
                        line
                            .substringAfter(':')
                            .split(',')
                            .map { it.trim().lowercase() }
                    requireFields(format)
                }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    if (format.isEmpty()) {
                        throw AssParseException("ASS Events 在 Dialogue 前缺少 Format")
                    }
                    val values = line.substringAfter(':').split(',', limit = format.size)
                    if (values.size != format.size) {
                        throw AssParseException("第 ${index + 1} 行 ASS Dialogue 字段数量无效")
                    }
                    val startMs = parseTimestamp(values[format.indexOf("start")].trim(), index)
                    val endMs = parseTimestamp(values[format.indexOf("end")].trim(), index)
                    if (endMs <= startMs) {
                        throw AssParseException("第 ${index + 1} 行 ASS Dialogue 结束时间无效")
                    }
                    val text = cleanText(values[format.indexOf("text")])
                    if (text.isNotBlank()) {
                        groupedText.getOrPut(TimeRange(startMs, endMs), ::mutableListOf) += text
                    }
                }
            }
        }

        if (groupedText.isEmpty()) throw AssParseException("ASS/SSA 中没有可显示的 Dialogue")
        return groupedText
            .map { (range, text) ->
                SubtitleCue(
                    startMs = range.startMs,
                    endMs = range.endMs,
                    text = text.distinct().joinToString("\n"),
                )
            }
            .sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
    }

    private fun requireFields(format: List<String>) {
        val missing = listOf("start", "end", "text").filterNot(format::contains)
        if (missing.isNotEmpty()) {
            throw AssParseException("ASS Events Format 缺少 ${missing.joinToString()}")
        }
    }

    private fun parseTimestamp(value: String, lineIndex: Int): Long {
        val match = timestamp.matchEntire(value)
            ?: throw AssParseException("第 ${lineIndex + 1} 行 ASS 时间格式无效")
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val milliseconds =
            match.groupValues[4]
                .padEnd(3, '0')
                .take(3)
                .toLongOrNull()
                ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + milliseconds
    }

    private fun cleanText(text: String): String =
        stripOverrideTags(text)
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()

    private fun stripOverrideTags(text: String): String =
        buildString(text.length) {
            var index = 0
            while (index < text.length) {
                if (text[index] == '{') {
                    val tagEnd = text.indexOf('}', startIndex = index + 1)
                    if (tagEnd >= 0) {
                        index = tagEnd + 1
                        continue
                    }
                }
                append(text[index])
                index += 1
            }
        }

    private data class TimeRange(
        val startMs: Long,
        val endMs: Long,
    )
}
