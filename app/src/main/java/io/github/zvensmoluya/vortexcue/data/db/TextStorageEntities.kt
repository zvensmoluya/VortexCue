package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "text_artifact",
    indices = [Index("kind"), Index("gc_candidate_since_ms")],
)
data class TextArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "artifact_id")
    val artifactId: String,
    val kind: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "codec_version")
    val codecVersion: Int,
    @ColumnInfo(name = "canonicalizer_version")
    val canonicalizerVersion: Int,
    @ColumnInfo(name = "payload_sha256")
    val payloadSha256: String,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
    @ColumnInfo(name = "cue_count")
    val cueCount: Int?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "gc_candidate_since_ms")
    val gcCandidateSinceMs: Long? = null,
)

@Entity(
    tableName = "timeline_revision",
    foreignKeys = [
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
    ],
    indices = [
        Index("content_id"),
        Index("source_revision_id"),
        Index(value = ["content_id", "role", "content_hash", "completeness"]),
    ],
)
data class TimelineRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    val role: String,
    @ColumnInfo(name = "source_revision_id")
    val sourceRevisionId: String?,
    @ColumnInfo(name = "source_revision_hash")
    val sourceRevisionHash: String?,
    val completeness: String,
    @ColumnInfo(name = "covered_end_ms")
    val coveredEndMs: Long?,
    @ColumnInfo(name = "cue_count")
    val cueCount: Int,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "assembler_version")
    val assemblerVersion: Int,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

@Entity(
    tableName = "timeline_revision_artifact",
    primaryKeys = ["revision_id", "role", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = TimelineRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TextArtifactEntity::class,
            parentColumns = ["artifact_id"],
            childColumns = ["artifact_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artifact_id")],
)
data class TimelineRevisionArtifactEntity(
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "artifact_id")
    val artifactId: String,
    val ordinal: Int,
    val role: String,
)

@Entity(
    tableName = "discovered_text_candidate",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("content_id"),
        Index("source_id"),
        Index(value = ["content_id", "source_id", "document_id"], unique = true),
    ],
)
data class DiscoveredTextCandidateEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    val format: String?,
    val language: String?,
    val label: String?,
    @ColumnInfo(name = "roles_mask")
    val rolesMask: Int,
    @ColumnInfo(name = "match_basis")
    val matchBasis: String,
    @ColumnInfo(name = "source_generation")
    val sourceGeneration: Long,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String? = null,
)

@Entity(
    tableName = "text_import_operation",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("content_id"), Index("candidate_id"), Index("state")],
)
data class TextImportOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "import_id")
    val importId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String?,
    @ColumnInfo(name = "staging_uri")
    val stagingUri: String?,
    @ColumnInfo(name = "release_permission_on_commit")
    val releasePermissionOnCommit: Boolean,
    @ColumnInfo(name = "source_display_name")
    val sourceDisplayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "detected_format")
    val detectedFormat: String?,
    @ColumnInfo(name = "requested_slot")
    val requestedSlot: String?,
    @ColumnInfo(name = "selection_mode")
    val selectionMode: String?,
    val state: String,
    @ColumnInfo(name = "artifact_id")
    val artifactId: String?,
    @ColumnInfo(name = "revision_id")
    val revisionId: String?,
    @ColumnInfo(name = "track_id")
    val trackId: String?,
    @ColumnInfo(name = "failure_category")
    val failureCategory: String?,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)
