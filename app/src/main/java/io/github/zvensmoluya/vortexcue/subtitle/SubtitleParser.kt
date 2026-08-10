package io.github.zvensmoluya.vortexcue.subtitle

object SubtitleParser {
    private val lrcTimestamp = Regex("""\[\d{1,3}:[0-5]\d(?:[.:]\d{1,3})?]""")

    fun parse(fileName: String, bytes: ByteArray): List<SubtitleCue> {
        val content = SubtitleTextDecoder.decode(bytes)
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "lrc" -> LrcParser.parse(content)
            "srt" -> SrtParser.parse(content)
            "vtt" -> WebVttParser.parse(content)
            "ass", "ssa" -> AssParser.parse(content)
            else ->
                when {
                    content.trimStart().startsWith("WEBVTT") -> WebVttParser.parse(content)
                    "[Events]" in content && "Dialogue:" in content -> AssParser.parse(content)
                    "-->" in content -> SrtParser.parse(content)
                    lrcTimestamp.containsMatchIn(content) -> LrcParser.parse(content)
                    else ->
                        throw IllegalArgumentException(
                            "无法识别字幕格式，仅支持 SRT、LRC、WebVTT 与 ASS/SSA",
                        )
                }
        }
    }
}
