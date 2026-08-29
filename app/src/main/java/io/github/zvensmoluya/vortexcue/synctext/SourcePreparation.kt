package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.text.SourceAddressingFunction
import io.github.zvensmoluya.vortexcue.text.SourceAliasCluster
import io.github.zvensmoluya.vortexcue.text.SourceContext
import io.github.zvensmoluya.vortexcue.text.SourceEntityMention
import io.github.zvensmoluya.vortexcue.text.SourcePatchOperation
import io.github.zvensmoluya.vortexcue.text.SourceStyleProfile
import io.github.zvensmoluya.vortexcue.text.SourceTextPatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SourcePreparationFlagKind {
    PolicyDrop,
    SuspiciousPattern,
}

enum class SourcePreparationCheckpointState {
    Running,
    Completed,
    SkippedNoModel,
    DegradedError,
}

data class SourcePreparationFlag(
    val cueId: String,
    val kind: SourcePreparationFlagKind,
    val patternId: String,
)

data class SourcePreparationSignals(
    val flags: List<SourcePreparationFlag>,
    val policyPatches: List<SourceTextPatch>,
)

data class ModelSourcePreparation(
    val patches: List<SourceTextPatch>,
    val sourceContext: SourceContext,
)

class SourcePreparationOutputException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal suspend fun requestSourcePreparationWithReasoningFallback(
    request: TranslationProviderRequest,
    onFallback: suspend () -> Unit = {},
    execute: suspend (TranslationProviderRequest) -> TranslationProviderResponse,
): TranslationProviderResponse {
    if (request.reasoningLevel == null) return execute(request)
    return try {
        execute(request)
    } catch (error: TranslationProtocolException) {
        if (!error.rejectsReasoningConfiguration()) throw error
        onFallback()
        execute(request.copy(reasoningLevel = null))
    }
}

private fun TranslationProtocolException.rejectsReasoningConfiguration(): Boolean {
    if (category != GenerationErrorCategory.ModelOrProtocol || httpStatus !in ReasoningRejectionStatuses) return false
    val details =
        buildString {
            append(message.orEmpty())
            rawResponse?.let { bytes ->
                append(' ')
                append(bytes.toString(Charsets.UTF_8).take(MaxReasoningErrorInspectionLength))
            }
        }.lowercase()
    return ReasoningFieldMarkers.any(details::contains) && ReasoningRejectionMarkers.any(details::contains)
}

private val ReasoningRejectionStatuses = setOf(400, 422)
private val ReasoningFieldMarkers = listOf("reasoning", "thinking", "output_config", "effort")
private val ReasoningRejectionMarkers =
    listOf(
        "unsupported",
        "not supported",
        "does not support",
        "unknown",
        "unrecognized",
        "unexpected",
        "not permitted",
        "not allowed",
        "extra input",
        "invalid parameter",
        "invalid argument",
        "cannot",
    )
private const val MaxReasoningErrorInspectionLength = 4_096

object SourcePreparationSignalDetector {
    fun detect(cues: List<SubtitleCue>): SourcePreparationSignals {
        val flags = mutableListOf<SourcePreparationFlag>()
        val policyPatches = mutableListOf<SourceTextPatch>()
        cues.forEach { cue ->
            val cueId = requireNotNull(cue.stableId) { "Source preparation requires stable cue ids" }
            val normalized = normalizeForPattern(cue.text)
            if (isMarkedSoundEvent(cue.text)) {
                flags += SourcePreparationFlag(cueId, SourcePreparationFlagKind.PolicyDrop, "marked_sound_event.v1")
                policyPatches += SourceTextPatch(cueId, SourcePatchOperation.Drop)
            }
            SuspiciousPatterns.firstOrNull { (_, pattern) -> pattern.matches(normalized) }?.let { (id) ->
                flags += SourcePreparationFlag(cueId, SourcePreparationFlagKind.SuspiciousPattern, id)
            }
        }
        return SourcePreparationSignals(flags, policyPatches)
    }

    private fun isMarkedSoundEvent(text: String): Boolean {
        val trimmed = text.trim()
        val markerContent =
            when {
                trimmed.length >= 3 && trimmed.startsWith('*') && trimmed.endsWith('*') ->
                    trimmed.substring(1, trimmed.lastIndex)
                trimmed.length >= 3 && trimmed.startsWith('[') && trimmed.endsWith(']') ->
                    trimmed.substring(1, trimmed.lastIndex)
                trimmed.length >= 3 && trimmed.startsWith('【') && trimmed.endsWith('】') ->
                    trimmed.substring(1, trimmed.lastIndex)
                trimmed.length >= 3 && trimmed.startsWith('（') && trimmed.endsWith('）') ->
                    trimmed.substring(1, trimmed.lastIndex)
                else -> return false
            }.trim().lowercase()
        if (markerContent.length !in 1..80) return false
        return SoundEventTerms.any { it in markerContent }
    }

    private fun normalizeForPattern(text: String): String =
        text.trim().lowercase().replace(Whitespace, "").replace(TrailingPunctuation, "")

    private val SuspiciousPatterns =
        listOf(
            "whisper.jp.thanks_for_watching.v1" to Regex("^(ご視聴|ご覧)(いただき)?ありがとうございました$"),
            "whisper.jp.standalone_thanks.v1" to Regex("^(どうも)?ありがとうございました$"),
            "whisper.jp.subscribe.v1" to Regex("^チャンネル登録(を)?(よろしく)?お願いします$"),
            "whisper.subtitle.credit.v1" to Regex("^(字幕|翻訳).*(作成|制作|提供)$"),
        )
    private val SoundEventTerms =
        setOf(
            "sigh", "sighs", "humming", "hum", "music", "applause", "laughter", "laughing",
            "crying", "sobbing", "breathing", "whispering", "phone rings", "footsteps", "door closes",
            "ため息", "溜息", "鼻歌", "音楽", "拍手", "笑い", "泣き", "すすり泣き", "呼吸", "足音",
            "叹气", "哼唱", "音乐", "掌声", "笑声", "哭泣", "抽泣", "呼吸声", "脚步声", "电话铃",
        )
    private val Whitespace = Regex("\\s+")
    private val TrailingPunctuation = Regex("[。．.!！?？…]+$")
}

object SourcePreparationPromptRenderer {
    fun render(
        cues: List<SubtitleCue>,
        sourceLanguage: String?,
        flags: List<SourcePreparationFlag>,
    ): String {
        require(cues.isNotEmpty())
        val knownIds = cues.mapTo(mutableSetOf()) { requireNotNull(it.stableId) }
        require(flags.all { it.cueId in knownIds })
        val flagsByCue = flags.groupBy(SourcePreparationFlag::cueId)
        return buildString {
            appendLine("SOURCE_LANGUAGE: ${sourceLanguage ?: "auto"}")
            appendLine("MEDIA_DURATION_MS: ${cues.maxOf(SubtitleCue::endMs)}")
            appendLine("CUE_COUNT: ${cues.size}")
            appendLine("TIMELINE")
            cues.forEach { cue ->
                val cueId = requireNotNull(cue.stableId)
                append('[')
                append(cueId)
                append("] ")
                append(cue.startMs)
                append(" --> ")
                append(cue.endMs)
                flagsByCue[cueId].orEmpty().forEach { flag ->
                    append(" <")
                    append(flag.kind.wireName)
                    append(':')
                    append(flag.patternId)
                    append('>')
                }
                append(' ')
                appendLine(cue.text.singleLine())
            }
        }.trimEnd()
    }
}

object SourcePreparationResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(response: String, sourceCues: List<SubtitleCue>, policyDropIds: Set<String>): ModelSourcePreparation {
        val sourceById = sourceCues.associateBy { requireNotNull(it.stableId) }
        val root =
            try {
                json.parseToJsonElement(response.singleJsonObject()).jsonObject
            } catch (error: Exception) {
                throw SourcePreparationOutputException("Source preparation output is not valid JSON", error)
            }
        root.requireOnlyKeys(setOf("patches", "source_context"), "root")
        val patches = parsePatches(root["patches"], sourceById, policyDropIds)
        val context = parseContext(root["source_context"], sourceById.keys)
        validatePatchVolume(patches.size, sourceCues.size)
        return ModelSourcePreparation(patches, context)
    }

    private fun parsePatches(
        element: JsonElement?,
        sourceById: Map<String, SubtitleCue>,
        policyDropIds: Set<String>,
    ): List<SourceTextPatch> {
        val array = element.asArrayOrEmpty("patches")
        val seen = mutableSetOf<String>()
        return array.map { item ->
            val value = item.asObject("patch")
            value.requireOnlyKeys(setOf("cue_id", "operation", "replacement_text"), "patch")
            val cueId = value.requiredString("cue_id", MaxCueIdLength)
            if (cueId !in sourceById) throw SourcePreparationOutputException("Patch references an unknown cue id")
            if (cueId in policyDropIds) throw SourcePreparationOutputException("Model patched a policy-dropped cue")
            if (!seen.add(cueId)) throw SourcePreparationOutputException("Patch cue id is duplicated")
            when (value.requiredString("operation", 16).uppercase()) {
                "DROP" -> {
                    if (value.optionalString("replacement_text", MaxReplacementLength) != null) {
                        throw SourcePreparationOutputException("DROP must not include replacement_text")
                    }
                    SourceTextPatch(cueId, SourcePatchOperation.Drop)
                }
                "REPLACE" -> {
                    val replacement = value.requiredString("replacement_text", MaxReplacementLength).trim()
                    if (replacement == sourceById.getValue(cueId).text.trim()) {
                        throw SourcePreparationOutputException("REPLACE must change the source text")
                    }
                    SourceTextPatch(cueId, SourcePatchOperation.Replace, replacement)
                }
                else -> throw SourcePreparationOutputException("Unknown source patch operation")
            }
        }
    }

    private fun parseContext(element: JsonElement?, knownCueIds: Set<String>): SourceContext {
        val context = element?.asObject("source_context") ?: return SourceContext.Empty
        context.requireOnlyKeys(
            setOf("style_profile", "entity_mentions", "alias_clusters", "addressing_functions"),
            "source_context",
        )
        val styleObject = context["style_profile"]?.asObject("style_profile")
        styleObject?.requireOnlyKeys(
            setOf("content_mode", "register", "politeness", "utterance_shape", "preserve_traits"),
            "style_profile",
        )
        val style =
            SourceStyleProfile(
                contentMode = styleObject?.optionalString("content_mode", MaxLabelLength),
                register = styleObject?.optionalString("register", MaxLabelLength),
                politeness = styleObject?.optionalString("politeness", MaxLabelLength),
                utteranceShape = styleObject?.optionalString("utterance_shape", MaxLabelLength),
                preserveTraits = styleObject.stringList("preserve_traits", MaxTraits, MaxLabelLength),
            )
        val entities =
            context["entity_mentions"].boundedArray("entity_mentions", MaxEntities).map { item ->
                val value = item.asObject("entity mention")
                value.requireOnlyKeys(
                    setOf("entity_id", "kind", "observed_forms", "spelling_status", "evidence_cue_ids"),
                    "entity mention",
                )
                SourceEntityMention(
                    entityId = value.requiredString("entity_id", MaxLabelLength),
                    kind = value.requiredString("kind", MaxLabelLength),
                    observedForms = value.stringList("observed_forms", MaxForms, MaxSourceFormLength).requireNotEmpty(),
                    spellingStatus = value.optionalString("spelling_status", MaxLabelLength),
                    evidenceCueIds = value.evidenceIds(knownCueIds),
                )
            }
        val aliases =
            context["alias_clusters"].boundedArray("alias_clusters", MaxAliases).map { item ->
                val value = item.asObject("alias cluster")
                value.requireOnlyKeys(setOf("entity_id", "forms", "evidence_cue_ids"), "alias cluster")
                SourceAliasCluster(
                    entityId = value.requiredString("entity_id", MaxLabelLength),
                    forms = value.stringList("forms", MaxForms, MaxSourceFormLength).also {
                        if (it.size < 2) throw SourcePreparationOutputException("Alias cluster requires two forms")
                    },
                    evidenceCueIds = value.evidenceIds(knownCueIds),
                )
            }
        val addressing =
            context["addressing_functions"].boundedArray("addressing_functions", MaxAddressForms).map { item ->
                val value = item.asObject("addressing function")
                value.requireOnlyKeys(
                    setOf("source_form", "function", "evidence_cue_ids"),
                    "addressing function",
                )
                SourceAddressingFunction(
                    sourceForm = value.requiredString("source_form", MaxSourceFormLength),
                    function = value.requiredString("function", MaxLabelLength),
                    evidenceCueIds = value.evidenceIds(knownCueIds),
                )
            }
        return SourceContext(style, entities, aliases, addressing)
    }

    private fun validatePatchVolume(patchCount: Int, sourceCueCount: Int) {
        val maximum = maxOf(MinimumPatchLimit, (sourceCueCount * MaxPatchRatioNumerator) / MaxPatchRatioDenominator)
        if (patchCount > maximum) throw SourcePreparationOutputException("Source preparation returned too many patches")
    }

    private fun JsonElement?.asArrayOrEmpty(label: String): JsonArray =
        when (this) {
            null -> JsonArray(emptyList())
            is JsonArray -> this
            else -> throw SourcePreparationOutputException("$label must be an array")
        }

    private fun JsonElement?.boundedArray(label: String, limit: Int): JsonArray =
        asArrayOrEmpty(label).also { array ->
            if (array.size > limit) throw SourcePreparationOutputException("$label has too many entries")
        }

    private fun JsonElement.asObject(label: String): JsonObject =
        this as? JsonObject ?: throw SourcePreparationOutputException("$label must be an object")

    private fun JsonObject.requireOnlyKeys(allowed: Set<String>, label: String) {
        if (keys.any { it !in allowed }) {
            throw SourcePreparationOutputException("$label contains an unknown field")
        }
    }

    private fun JsonObject.requiredString(name: String, maxLength: Int): String =
        optionalString(name, maxLength) ?: throw SourcePreparationOutputException("$name is required")

    private fun JsonObject.optionalString(name: String, maxLength: Int): String? {
        val element = this[name] ?: return null
        val primitive = element as? JsonPrimitive ?: throw SourcePreparationOutputException("$name must be a string")
        val value = primitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.length > maxLength) throw SourcePreparationOutputException("$name is too long")
        return value
    }

    private fun JsonObject?.stringList(name: String, limit: Int, maxLength: Int): List<String> {
        if (this == null) return emptyList()
        return this[name].boundedArray(name, limit).map { item ->
            val value = (item as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isBlank() || value.length > maxLength) {
                throw SourcePreparationOutputException("$name contains an invalid value")
            }
            value
        }.distinct()
    }

    private fun JsonObject.evidenceIds(knownCueIds: Set<String>): List<String> =
        stringList("evidence_cue_ids", MaxEvidenceIds, MaxCueIdLength).also { ids ->
            if (ids.any { it !in knownCueIds }) {
                throw SourcePreparationOutputException("Source context references an unknown cue id")
            }
        }

    private fun <T> List<T>.requireNotEmpty(): List<T> =
        also { if (it.isEmpty()) throw SourcePreparationOutputException("Required source context list is empty") }

    private fun String.singleJsonObject(): String {
        val trimmed = trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        val closing = trimmed.lastIndexOf("```")
        if (firstNewline < 0 || closing <= firstNewline) return trimmed
        return trimmed.substring(firstNewline + 1, closing).trim()
    }

    private const val MaxCueIdLength = 128
    private const val MaxReplacementLength = 2_000
    private const val MaxLabelLength = 80
    private const val MaxSourceFormLength = 160
    private const val MaxTraits = 8
    private const val MaxEntities = 32
    private const val MaxAliases = 24
    private const val MaxAddressForms = 32
    private const val MaxForms = 8
    private const val MaxEvidenceIds = 12
    private const val MinimumPatchLimit = 12
    private const val MaxPatchRatioNumerator = 1
    private const val MaxPatchRatioDenominator = 4
}

object SourcePatchApplier {
    fun apply(sourceCues: List<SubtitleCue>, patches: List<SourceTextPatch>): List<SubtitleCue> {
        val patchById = patches.associateBy(SourceTextPatch::cueId)
        require(patchById.size == patches.size) { "Source patches must have unique cue ids" }
        val knownIds = sourceCues.mapTo(mutableSetOf()) { requireNotNull(it.stableId) }
        require(patchById.keys.all { it in knownIds }) { "Source patch references an unknown cue id" }
        return sourceCues.mapNotNull { cue ->
            when (val patch = patchById[cue.stableId]) {
                null -> cue
                is SourceTextPatch ->
                    when (patch.operation) {
                        SourcePatchOperation.Drop -> null
                        SourcePatchOperation.Replace -> cue.copy(text = requireNotNull(patch.replacementText))
                    }
            }
        }.also { require(it.isNotEmpty()) { "Source preparation removed every visible cue" } }
    }
}

object SourceContextPromptRenderer {
    fun render(context: SourceContext): String {
        if (context.isEmpty()) return "(none)"
        return buildString {
            appendLine("STYLE_PROFILE")
            context.styleProfile.contentMode?.let { appendLine("content_mode: ${it.singleLine()}") }
            context.styleProfile.register?.let { appendLine("register: ${it.singleLine()}") }
            context.styleProfile.politeness?.let { appendLine("politeness: ${it.singleLine()}") }
            context.styleProfile.utteranceShape?.let { appendLine("utterance_shape: ${it.singleLine()}") }
            if (context.styleProfile.preserveTraits.isNotEmpty()) {
                appendLine("preserve_traits: ${context.styleProfile.preserveTraits.joinToString(", ") { it.singleLine() }}")
            }
            if (context.entityMentions.isNotEmpty()) {
                appendLine("ENTITIES")
                context.entityMentions.forEach { entity ->
                    append(entity.entityId.singleLine())
                    append(" | ")
                    append(entity.kind.singleLine())
                    append(" | forms=")
                    append(entity.observedForms.joinToString(",") { it.singleLine() })
                    entity.spellingStatus?.let { append(" | spelling=${it.singleLine()}") }
                    appendLine()
                }
            }
            if (context.aliasClusters.isNotEmpty()) {
                appendLine("ALIASES")
                context.aliasClusters.forEach { alias ->
                    appendLine("${alias.entityId.singleLine()} | ${alias.forms.joinToString(" = ") { it.singleLine() }}")
                }
            }
            if (context.addressingFunctions.isNotEmpty()) {
                appendLine("ADDRESSING")
                context.addressingFunctions.forEach { addressing ->
                    appendLine("${addressing.sourceForm.singleLine()} | ${addressing.function.singleLine()}")
                }
            }
        }.trimEnd()
    }
}

private fun String.singleLine(): String =
    replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim()

private val SourcePreparationFlagKind.wireName: String
    get() =
        when (this) {
            SourcePreparationFlagKind.PolicyDrop -> "POLICY_DROP"
            SourcePreparationFlagKind.SuspiciousPattern -> "SUSPICIOUS_PATTERN"
        }
