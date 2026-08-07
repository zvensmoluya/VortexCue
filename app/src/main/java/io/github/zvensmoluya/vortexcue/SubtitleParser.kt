package io.github.zvensmoluya.vortexcue

object SubtitleParser {
    private val lrcTimestamp = Regex("""\[\d{1,3}:[0-5]\d(?:[.:]\d{1,3})?]""")

    fun parse(fileName: String, bytes: ByteArray): List<SubtitleCue> {
        val content = SubtitleTextDecoder.decode(bytes)
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "lrc" -> LrcParser.parse(content)
            "srt" -> SrtParser.parse(content)
            else ->
                when {
                    "-->" in content -> SrtParser.parse(content)
                    lrcTimestamp.containsMatchIn(content) -> LrcParser.parse(content)
                    else -> throw IllegalArgumentException("无法识别字幕格式，仅支持 LRC 与 SRT")
                }
        }
    }
}
