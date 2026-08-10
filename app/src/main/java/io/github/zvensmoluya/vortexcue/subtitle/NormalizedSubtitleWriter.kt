package io.github.zvensmoluya.vortexcue.subtitle

import java.util.Locale

object NormalizedSubtitleWriter {
    fun encode(cues: List<SubtitleCue>): ByteArray {
        require(cues.isNotEmpty()) { "cues must not be empty" }
        val content =
            buildString {
                append("WEBVTT\n\n")
                cues.forEachIndexed { index, cue ->
                    append(index + 1)
                    append('\n')
                    append(formatTimestamp(cue.startMs))
                    append(" --> ")
                    append(formatTimestamp(cue.endMs))
                    append('\n')
                    append(escapeText(cue.text))
                    append("\n\n")
                }
            }
        return content.toByteArray(Charsets.UTF_8)
    }

    private fun formatTimestamp(milliseconds: Long): String {
        val hours = milliseconds / 3_600_000L
        val minutes = (milliseconds % 3_600_000L) / 60_000L
        val seconds = (milliseconds % 60_000L) / 1_000L
        val millis = milliseconds % 1_000L
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d.%03d",
            hours,
            minutes,
            seconds,
            millis,
        )
    }

    private fun escapeText(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "<br>")
}
