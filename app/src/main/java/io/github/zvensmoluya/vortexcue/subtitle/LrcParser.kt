package io.github.zvensmoluya.vortexcue.subtitle

class LrcParseException(message: String) : IllegalArgumentException(message)

object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""^\s*\[offset:([+-]?\d+)]\s*$""", RegexOption.IGNORE_CASE)

    fun parse(bytes: ByteArray): List<SubtitleCue> = parse(SubtitleTextDecoder.decode(bytes))

    fun parse(content: String): List<SubtitleCue> {
        val lines =
            content
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
        val offsetMs =
            lines
                .mapNotNull { line -> offsetTag.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull() }
                .lastOrNull() ?: 0L
        val entries = mutableListOf<Pair<Long, String>>()

        lines.forEach { line ->
            val matches = timestamp.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val text = timestamp.replace(line, "").trim()
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').toLong()
                val rawStartMs = ((minutes * 60) + seconds) * 1_000 + fraction
                entries += (rawStartMs + offsetMs).coerceAtLeast(0L) to text
            }
        }

        if (entries.isEmpty()) throw LrcParseException("LRC 中没有有效的时间标签")

        val grouped = entries.groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val starts = grouped.keys.sorted()
        return starts.mapIndexed { index, startMs ->
            val texts = grouped.getValue(startMs).filter { it.isNotBlank() }.distinct()
            SubtitleCue(
                startMs = startMs,
                endMs = starts.getOrNull(index + 1) ?: Long.MAX_VALUE,
                text = texts.joinToString("\n"),
            )
        }
    }
}
