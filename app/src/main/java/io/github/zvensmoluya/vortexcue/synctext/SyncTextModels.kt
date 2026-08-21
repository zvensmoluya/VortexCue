package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue

enum class AsrProvider(
    val displayName: String,
    val defaultEndpoint: String,
    val endpointEditable: Boolean,
) {
    OpenRouter(
        displayName = "OpenRouter",
        defaultEndpoint = "https://openrouter.ai/api/v1",
        endpointEditable = true,
    ),
    OpenAi(
        displayName = "OpenAI",
        defaultEndpoint = "https://api.openai.com/v1",
        endpointEditable = false,
    ),
}

enum class AsrTimelineStrategy {
    Segment,
    Word,
}

enum class AsrProfile(
    val provider: AsrProvider,
    val modelId: String,
    val experimental: Boolean,
    val timelineStrategy: AsrTimelineStrategy,
) {
    OpenRouterWhisperV1(
        provider = AsrProvider.OpenRouter,
        modelId = "openai/whisper-large-v3",
        experimental = false,
        timelineStrategy = AsrTimelineStrategy.Segment,
    ),
    OpenRouterGrokV1(
        provider = AsrProvider.OpenRouter,
        modelId = "x-ai/grok-stt-1.0",
        experimental = true,
        timelineStrategy = AsrTimelineStrategy.Word,
    ),
    OpenAiWhisperV1(
        provider = AsrProvider.OpenAi,
        modelId = "whisper-1",
        experimental = false,
        timelineStrategy = AsrTimelineStrategy.Segment,
    ),
    ;

    companion object {
        fun fromStoredValue(value: String): AsrProfile =
            when (value) {
                // This short-lived profile used OpenRouter's incompatible response normalization.
                // Fall back to the established OpenRouter model instead of reusing an unusable setup.
                "OpenRouterGptTranscribeV1" -> OpenRouterWhisperV1
                // GPT Transcribe does not return the timestamped timeline required by VortexCue.
                "OpenAiGptTranscribeV1" -> OpenAiWhisperV1
                else -> entries.firstOrNull { it.name == value } ?: OpenRouterWhisperV1
            }

        fun forProvider(provider: AsrProvider): List<AsrProfile> =
            entries.filter { it.provider == provider }

        fun defaultFor(provider: AsrProvider): AsrProfile =
            requireNotNull(forProvider(provider).firstOrNull()) { "No ASR profile is registered for $provider" }
    }
}

enum class TranslationProtocol {
    Responses,
    AnthropicMessages,
    ChatCompletions,
    GoogleGenerateContent,
}

enum class TextPresentationMode {
    TargetOnly,
    SourceTarget,
}

enum class CredentialPurpose {
    Asr,
    Translation,
    Shared,
}

data class CredentialMetadata(
    val credentialId: String,
    val name: String,
    val purpose: CredentialPurpose,
    val maskedValue: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class SyncTextScheme(
    val schemeId: String,
    val name: String,
    val asrProfile: AsrProfile,
    val asrCredentialId: String,
    val translationProtocol: TranslationProtocol,
    val translationEndpoint: String?,
    val translationUserAgent: String? = null,
    val translationModel: String?,
    val translationCredentialId: String?,
    val defaultTargetLanguage: String,
    val defaultPresentationMode: TextPresentationMode = TextPresentationMode.TargetOnly,
    val isDefault: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val asrEndpoint: String = OpenRouterAsrEngine.OpenRouterBaseUrl,
)

/** The product-facing single connection profile. Credential values stay in Keystore. */
data class SyncTextConfig(
    val configId: String,
    val asrEndpoint: String,
    val asrProfile: AsrProfile,
    val asrCredentialId: String,
    val translationProtocol: TranslationProtocol,
    val translationEndpoint: String?,
    val translationModel: String?,
    val translationCredentialId: String?,
    val targetLanguage: String,
    val presentationMode: TextPresentationMode,
    val translationUserAgent: String? = null,
)

sealed interface GenerationInput {
    val trackId: String?

    data class ExistingTrack(
        override val trackId: String,
    ) : GenerationInput

    data class EmbeddedTrack(
        override val trackId: String,
        val embeddedTrackId: String,
    ) : GenerationInput

    data class DiscoveredCandidate(
        val candidateId: String,
    ) : GenerationInput {
        override val trackId: String? = null
    }

    data class AudioTrack(
        val audioTrackId: String? = null,
    ) : GenerationInput {
        override val trackId: String? = null
    }
}

sealed interface GenerationOutput {
    data object SourceOnly : GenerationOutput

    data class Translated(
        val targetLanguage: String,
        val presentationMode: TextPresentationMode,
    ) : GenerationOutput
}

data class GenerationRequest(
    val contentId: String,
    val input: GenerationInput,
    val output: GenerationOutput,
    val schemeId: String,
    val sourceLanguage: String? = null,
    val allowMeteredNetwork: Boolean = false,
    val allowAsrFallback: Boolean = false,
)

enum class GenerationTaskState {
    Waiting,
    Running,
    Paused,
    NeedsAttention,
    Completed,
    Deleted,
}

enum class GenerationTaskKind {
    SourceGeneration,
    TranslationDerivation,
}

enum class GeneratedResultCompleteness {
    Partial,
    Final,
}

enum class GenerationStage {
    Queued,
    MaterializingText,
    DecodingAudio,
    RecognizingSpeech,
    SourcePublished,
    Translating,
    Publishing,
    Completed,
}

enum class GenerationErrorCategory {
    Configuration,
    Authentication,
    ModelOrProtocol,
    Policy,
    RateLimited,
    TemporaryNetwork,
    Timeline,
    Media,
    InvalidResponse,
    Storage,
}

data class GenerationTaskSummary(
    val taskId: String,
    val contentId: String,
    val schemeId: String,
    val kind: GenerationTaskKind,
    val queuePosition: Int,
    val state: GenerationTaskState,
    val stage: GenerationStage,
    val progress: Float,
    val targetLanguage: String?,
    val waitingForNetwork: Boolean,
    val paused: Boolean,
    val needsAttention: Boolean,
    val blockedByScheme: Boolean,
    val errorCategory: GenerationErrorCategory? = null,
    val errorMessage: String? = null,
)

data class UsageRecord(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val costUsd: String? = null,
)

data class GeneratedTextResult(
    val resultId: String,
    val slotId: String,
    val contentId: String,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val sourceRevisionId: String,
    val targetRevisionId: String?,
    val sourceSha256: String,
    val targetSha256: String?,
    val cueCount: Int,
    val completeness: GeneratedResultCompleteness,
    val coveredEndMs: Long?,
    val completedWindowCount: Int?,
    val totalWindowCount: Int?,
    val sourceCues: List<SubtitleCue> = emptyList(),
    val targetCues: List<SubtitleCue>? = null,
    val presentationMode: TextPresentationMode,
    val schemeId: String,
    val attemptId: String?,
    val model: String?,
    val policyId: String?,
    val policyVersion: Int?,
    val policyRevision: String?,
    val systemPromptSha256: String?,
    val stylePromptSha256: String?,
    val usage: UsageRecord = UsageRecord(),
    val createdAtMs: Long,
)

data class GeneratedTextPresentation(
    val result: GeneratedTextResult,
    val sourceCues: List<SubtitleCue>,
    val targetCues: List<SubtitleCue>?,
)

fun GenerationOutput.targetLanguageOrNull(): String? =
    (this as? GenerationOutput.Translated)?.targetLanguage
