package io.github.zvensmoluya.vortexcue.synctext

import com.github.houbb.opencc4j.util.ZhConverterUtil
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue

internal object AsrSourceTextNormalizer {
    fun normalize(
        cues: List<SubtitleCue>,
        requestedSourceLanguage: String?,
    ): List<SubtitleCue> {
        if (!requestsSimplifiedChinese(requestedSourceLanguage)) return cues
        return cues.map { cue ->
            val converted = ZhConverterUtil.toSimple(cue.text)
            if (converted == cue.text) cue else cue.copy(text = converted)
        }
    }

    private fun requestsSimplifiedChinese(sourceLanguage: String?): Boolean =
        sourceLanguage != null && LanguageTags.isSimplifiedChinese(sourceLanguage)
}
