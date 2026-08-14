package io.github.zvensmoluya.vortexcue.synctext

import java.util.IllformedLocaleException
import java.util.Locale

object LanguageTags {
    fun normalize(value: String): String {
        val input = value.trim().replace('_', '-')
        require(input.isNotBlank()) { "Language tag must not be blank" }
        val locale =
            try {
                Locale.Builder().setLanguageTag(input).build()
            } catch (error: IllformedLocaleException) {
                throw IllegalArgumentException("Invalid BCP-47 language tag: $value", error)
            }
        val normalized = locale.toLanguageTag()
        require(normalized != "und" || input.equals("und", ignoreCase = true)) {
            "Invalid BCP-47 language tag: $value"
        }
        return if (normalized.equals("zh", ignoreCase = true)) "zh-CN" else normalized
    }

    fun normalizeOrNull(value: String?): String? =
        value?.takeIf(String::isNotBlank)?.let(::normalize)

    fun isSimplifiedChinese(value: String): Boolean =
        when (val normalized = normalize(value)) {
            "zh-CN", "zh-Hans", "zh-Hans-CN", "zh-SG", "zh-Hans-SG" -> true
            else -> normalized.startsWith("zh-Hans-")
        }
}
