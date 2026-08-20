package io.github.zvensmoluya.vortexcue.synctext

import java.io.File

internal data class LiveProviderProbeConfig(
    val enabled: Boolean,
    val asrApiKey: String?,
    val asrEndpoint: String,
    val asrProfile: AsrProfile,
    val translationApiKey: String?,
    val translationEndpoint: String?,
    val translationModel: String?,
    val translationProtocol: TranslationProtocol,
    val targetLanguage: String,
    val userAgent: String?,
    val mediaEnabled: Boolean,
    val mediaFile: File?,
    val mediaStartSeconds: Double,
    val mediaDurationSeconds: Double,
    val mediaSourceLanguage: String,
    val ffmpegCommand: String,
) {
    companion object {
        fun fromEnvironment(): LiveProviderProbeConfig {
            val localValues = loadLocalDotEnv()
            fun value(name: String): String? =
                (System.getenv(name) ?: localValues[name])?.trim()?.takeIf(String::isNotEmpty)

            return LiveProviderProbeConfig(
                enabled = value("VORTEX_LIVE_TEST") == "1",
                asrApiKey = value("VORTEX_LIVE_ASR_API_KEY"),
                asrEndpoint =
                    value("VORTEX_LIVE_ASR_ENDPOINT")
                        ?: OpenRouterAsrEngine.OpenRouterTranscriptionUrl,
                asrProfile =
                    value("VORTEX_LIVE_ASR_PROFILE")?.let { enumValueOrNull<AsrProfile>(it) }
                        ?: AsrProfile.OpenRouterWhisperV1,
                translationApiKey = value("VORTEX_LIVE_TRANSLATION_API_KEY"),
                translationEndpoint = value("VORTEX_LIVE_TRANSLATION_ENDPOINT"),
                translationModel = value("VORTEX_LIVE_TRANSLATION_MODEL"),
                translationProtocol =
                    value("VORTEX_LIVE_TRANSLATION_PROTOCOL")?.let { enumValueOrNull<TranslationProtocol>(it) }
                        ?: TranslationProtocol.Responses,
                targetLanguage = value("VORTEX_LIVE_TARGET_LANGUAGE") ?: "zh-CN",
                userAgent = value("VORTEX_LIVE_USER_AGENT"),
                mediaEnabled = value("VORTEX_LIVE_MEDIA_TEST") == "1",
                mediaFile = value("VORTEX_LIVE_MEDIA_FILE")?.let(::File),
                mediaStartSeconds = value("VORTEX_LIVE_MEDIA_START_SECONDS")?.toDoubleOrNull() ?: 0.0,
                mediaDurationSeconds = value("VORTEX_LIVE_MEDIA_DURATION_SECONDS")?.toDoubleOrNull() ?: 225.0,
                mediaSourceLanguage = value("VORTEX_LIVE_MEDIA_SOURCE_LANGUAGE") ?: "ja",
                ffmpegCommand = value("VORTEX_LIVE_FFMPEG") ?: "ffmpeg",
            )
        }

        private fun loadLocalDotEnv(): Map<String, String> {
            val file =
                sequenceOf(File(".env.local"), File(".env"), File("../.env.local"), File("../.env"))
                    .firstOrNull(File::isFile)
                    ?: return emptyMap()
            return file.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = trimmed.substring(0, separator).trim()
                if (!key.matches(EnvKey)) return@mapNotNull null
                val value = trimmed.substring(separator + 1).trim().removeOptionalQuotes()
                key to value
            }.toMap()
        }

        private val EnvKey = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

internal fun loadLivePromptAsset(id: String): ByteArray {
    val fileName =
        when (id) {
            "system.v1" -> "translation_system_v1.md"
            "style.zh-CN.v1" -> "translation_style_zh_cn_v1.md"
            else -> error("Unexpected prompt asset: $id")
        }
    return requireNotNull(findRepositoryFile("app/src/main/res/raw/$fileName")) {
        "Prompt asset is missing: $fileName"
    }.readBytes()
}

internal fun findRepositoryFile(relativePath: String): File? =
    sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)

private fun String.removeOptionalQuotes(): String =
    if (length >= 2 && first() == last() && (first() == '"' || first() == '\'')) substring(1, lastIndex) else this

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
