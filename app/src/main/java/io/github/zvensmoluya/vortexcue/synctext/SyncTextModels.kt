package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue

enum class AsrProfile(
    val modelId: String,
    val experimental: Boolean,
) {
    OpenRouterWhisperV1("openai/whisper-large-v3", false),
    OpenRouterGrokV1("x-ai/grok-stt-1.0", true),
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
    val translationEndpoint: String,
    val translationUserAgent: String? = null,
    val translationModel: String,
    val translationCredentialId: String,
    val defaultTargetLanguage: String,
    val defaultPresentationMode: TextPresentationMode = TextPresentationMode.TargetOnly,
    val isDefault: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
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
)

enum class GenerationTaskState {
    Waiting,
    Running,
    Paused,
    NeedsAttention,
    Completed,
    Deleted,
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
    val sourceAssetFileName: String,
    val targetAssetFileName: String?,
    val sourceSha256: String,
    val targetSha256: String?,
    val cueCount: Int,
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
