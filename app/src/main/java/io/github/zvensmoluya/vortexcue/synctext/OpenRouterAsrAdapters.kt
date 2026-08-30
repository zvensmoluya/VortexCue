package io.github.zvensmoluya.vortexcue.synctext

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class OpenRouterAsrRequestSpec(
    val modelId: String,
    val language: String?,
    val responseFormat: String,
    val timestampGranularity: String,
)

internal data class OpenRouterParsedAsrResponse(
    val timeline: List<AsrTimestampedToken>,
    val detectedLanguage: String?,
)

internal interface OpenRouterAsrAdapter {
    val profile: AsrProfile

    fun requestSpec(request: AsrRequest): OpenRouterAsrRequestSpec

    fun parseResponse(
        root: JsonObject,
        requestedLanguage: String?,
    ): OpenRouterParsedAsrResponse
}

internal class OpenRouterAsrAdapterRegistry(
    adapters: List<OpenRouterAsrAdapter>,
) {
    private val byProfile = adapters.associateBy(OpenRouterAsrAdapter::profile)

    init {
        require(byProfile.size == adapters.size) { "Duplicate OpenRouter ASR profile adapter" }
    }

    fun adapterFor(profile: AsrProfile): OpenRouterAsrAdapter =
        requireNotNull(byProfile[profile]) {
            "OpenRouter ASR profile $profile has no model adapter"
        }
}

internal fun defaultOpenRouterAsrAdapterRegistry() =
    OpenRouterAsrAdapterRegistry(
        listOf(
            OpenRouterWhisperLargeV3AsrAdapter,
            OpenRouterQwen3Asr17BAdapter,
        ),
    )

internal object OpenRouterWhisperLargeV3AsrAdapter : OpenRouterAsrAdapter {
    override val profile = AsrProfile.OpenRouterWhisperV1

    override fun requestSpec(request: AsrRequest): OpenRouterAsrRequestSpec =
        segmentRequestSpec(request)

    override fun parseResponse(
        root: JsonObject,
        requestedLanguage: String?,
    ) =
        OpenRouterParsedAsrResponse(
            timeline = parseSegmentTimeline(root, "Whisper"),
            detectedLanguage = normalizeLanguageTag(root.detectedLanguage(), requestedLanguage),
        )
}

/** OpenRouter's Qwen3 ASR response uses segment timestamps and spells out detected language names. */
internal object OpenRouterQwen3Asr17BAdapter : OpenRouterAsrAdapter {
    override val profile = AsrProfile.OpenRouterQwen3Asr17BV1

    override fun requestSpec(request: AsrRequest): OpenRouterAsrRequestSpec =
        segmentRequestSpec(request)

    override fun parseResponse(
        root: JsonObject,
        requestedLanguage: String?,
    ) =
        OpenRouterParsedAsrResponse(
            timeline = parseSegmentTimeline(root, "Qwen3 ASR"),
            detectedLanguage = normalizeQwenLanguage(root.detectedLanguage(), requestedLanguage),
        )
}

private fun OpenRouterAsrAdapter.segmentRequestSpec(request: AsrRequest): OpenRouterAsrRequestSpec {
    require(request.profile == profile) { "$profile adapter cannot handle ${request.profile}" }
    return OpenRouterAsrRequestSpec(
        modelId = profile.modelId,
        language = request.sourceLanguage?.let { LanguageTags.normalize(it).substringBefore('-') },
        responseFormat = "verbose_json",
        timestampGranularity = "segment",
    )
}

internal fun parseSegmentTimeline(
    root: JsonObject,
    modelName: String,
): List<AsrTimestampedToken> {
    val values = root["segments"] as? JsonArray
        ?: throw timelineFailure("$modelName response omitted segment timestamps")
    if (values.isEmpty()) return emptyList()
    if (values.containsOnlyExplicitBlankText("text")) return emptyList()
    val parsed = values.mapNotNull(::parseTimestampedText)
    if (parsed.isEmpty()) throw timelineFailure("$modelName response contained no valid segments")
    return parsed
}

private fun parseTimestampedText(element: kotlinx.serialization.json.JsonElement): AsrTimestampedToken? {
    val value = element as? JsonObject ?: return null
    val start = (value["start"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val end = (value["end"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val text = value.string("text")
    if (!start.isFinite() || !end.isFinite() || end <= start || text.isNullOrBlank()) return null
    return AsrTimestampedToken(secondsToMilliseconds(start), secondsToMilliseconds(end), text.trim())
}

private fun timelineFailure(message: String) =
    AsrException(
        category = GenerationErrorCategory.Timeline,
        retryable = false,
        shouldSplit = false,
        message = message,
    )

private fun normalizeLanguageTag(detected: String?, requested: String?): String? =
    detected?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() }
        ?: LanguageTags.normalizeOrNull(requested)

private fun normalizeQwenLanguage(detected: String?, requested: String?): String? {
    val raw = detected?.trim()?.takeIf(String::isNotEmpty)
    val mapped = raw?.lowercase(Locale.ROOT)?.let(QwenLanguageTags::get)
    val normalizedCode =
        raw
            ?.takeIf { it.matches(LanguageCode) }
            ?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() }
    return mapped?.let(LanguageTags::normalize)
        ?: normalizedCode
        ?: LanguageTags.normalizeOrNull(requested)
}

private fun JsonObject.detectedLanguage(): String? =
    string("language")
        ?: (get("languages") as? JsonArray)
            ?.firstNotNullOfOrNull { element -> (element as? JsonObject)?.string("code") }

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

private val LanguageCode = Regex("[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*")

private val QwenLanguageTags =
    mapOf(
        "arabic" to "ar",
        "cantonese" to "yue",
        "chinese" to "zh",
        "czech" to "cs",
        "danish" to "da",
        "dutch" to "nl",
        "english" to "en",
        "filipino" to "fil",
        "finnish" to "fi",
        "french" to "fr",
        "german" to "de",
        "greek" to "el",
        "hindi" to "hi",
        "hungarian" to "hu",
        "indonesian" to "id",
        "italian" to "it",
        "japanese" to "ja",
        "korean" to "ko",
        "macedonian" to "mk",
        "malay" to "ms",
        "mandarin" to "zh",
        "persian" to "fa",
        "polish" to "pl",
        "portuguese" to "pt",
        "romanian" to "ro",
        "russian" to "ru",
        "spanish" to "es",
        "swedish" to "sv",
        "thai" to "th",
        "turkish" to "tr",
        "vietnamese" to "vi",
    )
