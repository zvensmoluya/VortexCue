package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "sync_text_credential",
    indices = [Index(value = ["name"], unique = true)],
)
data class CredentialMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "credential_id")
    val credentialId: String,
    val name: String,
    val purpose: String,
    @ColumnInfo(name = "masked_value")
    val maskedValue: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "sync_text_scheme",
    foreignKeys = [
        ForeignKey(
            entity = CredentialMetadataEntity::class,
            parentColumns = ["credential_id"],
            childColumns = ["asr_credential_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CredentialMetadataEntity::class,
            parentColumns = ["credential_id"],
            childColumns = ["translation_credential_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["name"], unique = true),
        Index("asr_credential_id"),
        Index("translation_credential_id"),
    ],
)
data class SyncTextSchemeEntity(
    @PrimaryKey
    @ColumnInfo(name = "scheme_id")
    val schemeId: String,
    val name: String,
    @ColumnInfo(name = "asr_profile")
    val asrProfile: String,
    @ColumnInfo(name = "asr_credential_id")
    val asrCredentialId: String,
    @ColumnInfo(name = "translation_protocol")
    val translationProtocol: String,
    @ColumnInfo(name = "translation_endpoint")
    val translationEndpoint: String?,
    @ColumnInfo(name = "translation_user_agent")
    val translationUserAgent: String? = null,
    @ColumnInfo(name = "translation_model")
    val translationModel: String?,
    @ColumnInfo(name = "translation_credential_id")
    val translationCredentialId: String?,
    @ColumnInfo(name = "default_target_language")
    val defaultTargetLanguage: String,
    @ColumnInfo(name = "default_presentation_mode")
    val defaultPresentationMode: String,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "asr_endpoint")
    val asrEndpoint: String = "https://openrouter.ai/api/v1/audio/transcriptions",
)

@Entity(
    tableName = "generation_task",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SyncTextSchemeEntity::class,
            parentColumns = ["scheme_id"],
            childColumns = ["scheme_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("content_id"),
        Index("scheme_id"),
        Index(value = ["active_dedupe_key"], unique = true),
        Index(value = ["state", "queue_position"]),
    ],
)
data class GenerationTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "scheme_id")
    val schemeId: String,
    @ColumnInfo(defaultValue = "'SourceGeneration'")
    val kind: String,
    @ColumnInfo(name = "active_dedupe_key")
    val activeDedupeKey: String?,
    val state: String,
    val stage: String,
    @ColumnInfo(name = "queue_position")
    val queuePosition: Int,
    val progress: Float,
    @ColumnInfo(name = "target_language")
    val targetLanguage: String?,
    @ColumnInfo(name = "waiting_for_network")
    val waitingForNetwork: Boolean,
    @ColumnInfo(name = "metered_network_allowed", defaultValue = "0")
    val meteredNetworkAllowed: Boolean = false,
    @ColumnInfo(name = "network_wait_reason")
    val networkWaitReason: String? = null,
    val paused: Boolean,
    @ColumnInfo(name = "needs_attention")
    val needsAttention: Boolean,
    @ColumnInfo(name = "scheme_blocked")
    val schemeBlocked: Boolean,
    @ColumnInfo(name = "current_attempt_id")
    val currentAttemptId: String,
    @ColumnInfo(name = "source_result_id")
    val sourceResultId: String? = null,
    @ColumnInfo(name = "error_category")
    val errorCategory: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "generation_task_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = GenerationTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class GenerationTaskSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "input_kind")
    val inputKind: String,
    @ColumnInfo(name = "source_track_id")
    val sourceTrackId: String?,
    @ColumnInfo(name = "embedded_track_id")
    val embeddedTrackId: String?,
    @ColumnInfo(name = "audio_track_id")
    val audioTrackId: String?,
    @ColumnInfo(name = "input_source_result_id")
    val inputSourceResultId: String?,
    @ColumnInfo(name = "source_language")
    val sourceLanguage: String?,
    @ColumnInfo(name = "target_language")
    val targetLanguage: String?,
    @ColumnInfo(name = "presentation_mode")
    val presentationMode: String,
    @ColumnInfo(name = "allow_metered_network")
    val allowMeteredNetwork: Boolean,
    @ColumnInfo(name = "allow_asr_fallback", defaultValue = "0")
    val allowAsrFallback: Boolean,
    @ColumnInfo(name = "submitted_at_ms")
    val submittedAtMs: Long,
)

@Entity(
    tableName = "generation_attempt",
    foreignKeys = [
        ForeignKey(
            entity = GenerationTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SyncTextSchemeEntity::class,
            parentColumns = ["scheme_id"],
            childColumns = ["scheme_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CredentialMetadataEntity::class,
            parentColumns = ["credential_id"],
            childColumns = ["asr_credential_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CredentialMetadataEntity::class,
            parentColumns = ["credential_id"],
            childColumns = ["translation_credential_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("task_id"),
        Index("scheme_id"),
        Index("asr_credential_id"),
        Index("translation_credential_id"),
        Index(value = ["task_id", "ordinal"], unique = true),
    ],
)
data class GenerationAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    val ordinal: Int,
    @ColumnInfo(name = "scheme_id")
    val schemeId: String,
    @ColumnInfo(name = "asr_profile")
    val asrProfile: String,
    @ColumnInfo(name = "asr_credential_id")
    val asrCredentialId: String,
    @ColumnInfo(name = "translation_protocol")
    val translationProtocol: String,
    @ColumnInfo(name = "translation_endpoint")
    val translationEndpoint: String?,
    @ColumnInfo(name = "translation_user_agent")
    val translationUserAgent: String? = null,
    @ColumnInfo(name = "translation_model")
    val translationModel: String?,
    @ColumnInfo(name = "translation_credential_id")
    val translationCredentialId: String?,
    @ColumnInfo(name = "policy_id")
    val policyId: String,
    @ColumnInfo(name = "policy_version")
    val policyVersion: Int,
    @ColumnInfo(name = "policy_revision")
    val policyRevision: String,
    @ColumnInfo(name = "system_prompt_sha256")
    val systemPromptSha256: String,
    @ColumnInfo(name = "style_prompt_sha256")
    val stylePromptSha256: String?,
    @ColumnInfo(name = "request_schema_version")
    val requestSchemaVersion: Int,
    @ColumnInfo(name = "asr_window_ms")
    val asrWindowMs: Long,
    @ColumnInfo(name = "asr_overlap_ms")
    val asrOverlapMs: Long,
    @ColumnInfo(name = "asr_max_encoded_bytes")
    val asrMaxEncodedBytes: Long,
    @ColumnInfo(name = "source_result_id")
    val sourceResultId: String?,
    val state: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long? = null,
    @ColumnInfo(name = "asr_endpoint")
    val asrEndpoint: String = "https://openrouter.ai/api/v1/audio/transcriptions",
)

@Entity(
    tableName = "asr_window",
    foreignKeys = [
        ForeignKey(
            entity = GenerationAttemptEntity::class,
            parentColumns = ["attempt_id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TextArtifactEntity::class,
            parentColumns = ["artifact_id"],
            childColumns = ["transcript_artifact_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("attempt_id"),
        Index(value = ["attempt_id", "ordinal"], unique = true),
        Index("parent_window_id"),
        Index("transcript_artifact_id"),
    ],
)
data class AsrWindowEntity(
    @PrimaryKey
    @ColumnInfo(name = "window_id")
    val windowId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    val ordinal: Int,
    @ColumnInfo(name = "owned_start_ms")
    val ownedStartMs: Long,
    @ColumnInfo(name = "owned_end_ms")
    val ownedEndMs: Long,
    @ColumnInfo(name = "upload_start_ms")
    val uploadStartMs: Long,
    @ColumnInfo(name = "upload_end_ms")
    val uploadEndMs: Long,
    @ColumnInfo(name = "pcm_start_frame")
    val pcmStartFrame: Long,
    @ColumnInfo(name = "pcm_end_frame")
    val pcmEndFrame: Long,
    @ColumnInfo(name = "cut_reason")
    val cutReason: String,
    @ColumnInfo(name = "planner_version")
    val plannerVersion: String,
    @ColumnInfo(name = "parent_window_id")
    val parentWindowId: String?,
    val state: String,
    @ColumnInfo(name = "encoded_bytes")
    val encodedBytes: Long?,
    @ColumnInfo(name = "transcript_artifact_id")
    val transcriptArtifactId: String?,
    @ColumnInfo(name = "transcript_sha256")
    val transcriptSha256: String? = null,
    @ColumnInfo(name = "cue_count")
    val cueCount: Int? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "detected_language")
    val detectedLanguage: String? = null,
    @ColumnInfo(name = "input_tokens")
    val inputTokens: Long? = null,
    @ColumnInfo(name = "output_tokens")
    val outputTokens: Long? = null,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Long? = null,
    @ColumnInfo(name = "cost_usd")
    val costUsd: String? = null,
)

@Entity(
    tableName = "translation_batch",
    foreignKeys = [
        ForeignKey(
            entity = GenerationAttemptEntity::class,
            parentColumns = ["attempt_id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TextArtifactEntity::class,
            parentColumns = ["artifact_id"],
            childColumns = ["result_artifact_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("attempt_id"),
        Index(value = ["attempt_id", "ordinal"], unique = true),
        Index("parent_batch_id"),
        Index("result_artifact_id"),
    ],
)
data class TranslationBatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "batch_id")
    val batchId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    val ordinal: Int,
    @ColumnInfo(name = "first_cue_index")
    val firstCueIndex: Int,
    @ColumnInfo(name = "last_cue_index")
    val lastCueIndex: Int,
    @ColumnInfo(name = "parent_batch_id")
    val parentBatchId: String?,
    val state: String,
    @ColumnInfo(name = "format_recovery_count")
    val formatRecoveryCount: Int,
    @ColumnInfo(name = "repair_count")
    val repairCount: Int,
    @ColumnInfo(name = "result_artifact_id")
    val resultArtifactId: String?,
    @ColumnInfo(name = "result_sha256")
    val resultSha256: String? = null,
    @ColumnInfo(name = "cue_count")
    val cueCount: Int? = null,
    @ColumnInfo(name = "input_tokens")
    val inputTokens: Long?,
    @ColumnInfo(name = "output_tokens")
    val outputTokens: Long?,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Long?,
    @ColumnInfo(name = "cost_usd")
    val costUsd: String?,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
)

@Entity(
    tableName = "sync_text_request_attempt",
    foreignKeys = [
        ForeignKey(
            entity = GenerationAttemptEntity::class,
            parentColumns = ["attempt_id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attempt_id"), Index(value = ["attempt_id", "sequence"], unique = true)],
)
data class RequestAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_attempt_id")
    val requestAttemptId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    val sequence: Int,
    val stage: String,
    val provider: String,
    @ColumnInfo(name = "request_bytes")
    val requestBytes: Long?,
    @ColumnInfo(name = "response_bytes")
    val responseBytes: Long?,
    @ColumnInfo(name = "http_status")
    val httpStatus: Int?,
    @ColumnInfo(name = "input_tokens")
    val inputTokens: Long?,
    @ColumnInfo(name = "output_tokens")
    val outputTokens: Long?,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Long?,
    @ColumnInfo(name = "cost_usd")
    val costUsd: String?,
    @ColumnInfo(name = "raw_response_file_name")
    val rawResponseFileName: String?,
    @ColumnInfo(name = "error_category")
    val errorCategory: String?,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,
    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long?,
    @ColumnInfo(name = "retain_until_ms")
    val retainUntilMs: Long?,
)

@Entity(
    tableName = "generated_text_slot",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("content_id"),
        Index(value = ["content_id", "target_key"], unique = true),
        Index(value = ["content_id", "slot_id"], unique = true),
        Index(value = ["current_result_id"], unique = true),
    ],
)
data class GeneratedTextSlotEntity(
    @PrimaryKey
    @ColumnInfo(name = "slot_id")
    val slotId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    @ColumnInfo(name = "target_language")
    val targetLanguage: String?,
    @ColumnInfo(name = "presentation_mode")
    val presentationMode: String,
    @ColumnInfo(name = "current_result_id")
    val currentResultId: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "generated_text_result",
    foreignKeys = [
        ForeignKey(
            entity = GeneratedTextSlotEntity::class,
            parentColumns = ["slot_id"],
            childColumns = ["slot_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["source_revision_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["target_revision_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("slot_id"),
        Index("content_id"),
        Index("task_id"),
        Index("attempt_id"),
        Index("source_revision_id"),
        Index("target_revision_id"),
    ],
)
data class GeneratedTextResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "result_id")
    val resultId: String,
    @ColumnInfo(name = "slot_id")
    val slotId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "task_id")
    val taskId: String?,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String?,
    @ColumnInfo(name = "source_language")
    val sourceLanguage: String?,
    @ColumnInfo(name = "target_language")
    val targetLanguage: String?,
    @ColumnInfo(name = "presentation_mode")
    val presentationMode: String,
    @ColumnInfo(name = "source_revision_id")
    val sourceRevisionId: String,
    @ColumnInfo(name = "target_revision_id")
    val targetRevisionId: String?,
    @ColumnInfo(name = "source_sha256")
    val sourceSha256: String,
    @ColumnInfo(name = "target_sha256")
    val targetSha256: String?,
    @ColumnInfo(name = "cue_count")
    val cueCount: Int,
    @ColumnInfo(defaultValue = "'Final'")
    val completeness: String,
    @ColumnInfo(name = "covered_end_ms")
    val coveredEndMs: Long?,
    @ColumnInfo(name = "completed_window_count")
    val completedWindowCount: Int?,
    @ColumnInfo(name = "total_window_count")
    val totalWindowCount: Int?,
    @ColumnInfo(name = "scheme_id")
    val schemeId: String,
    val model: String?,
    @ColumnInfo(name = "policy_id")
    val policyId: String?,
    @ColumnInfo(name = "policy_version")
    val policyVersion: Int?,
    @ColumnInfo(name = "policy_revision")
    val policyRevision: String?,
    @ColumnInfo(name = "system_prompt_sha256")
    val systemPromptSha256: String?,
    @ColumnInfo(name = "style_prompt_sha256")
    val stylePromptSha256: String?,
    @ColumnInfo(name = "input_tokens")
    val inputTokens: Long?,
    @ColumnInfo(name = "output_tokens")
    val outputTokens: Long?,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Long?,
    @ColumnInfo(name = "cost_usd")
    val costUsd: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

@Entity(tableName = "sync_text_runner_lease")
data class SyncTextRunnerLeaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "lease_key")
    val leaseKey: String,
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,
    val generation: Long,
)

@Entity(tableName = "sync_text_queue_control")
data class SyncTextQueueControlEntity(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "pause_all")
    val pauseAll: Boolean,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)
