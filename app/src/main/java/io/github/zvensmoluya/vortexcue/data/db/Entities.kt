package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "content")
data class ContentEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "subtitle_offset_ms")
    val subtitleOffsetMs: Long = 0L,
)

@Entity(
    tableName = "content_identity",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("content_id")],
)
data class ContentIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
)

@Entity(
    tableName = "content_redirect",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["canonical_content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("canonical_content_id")],
)
data class ContentRedirectEntity(
    @PrimaryKey
    @ColumnInfo(name = "alias_content_id")
    val aliasContentId: String,
    @ColumnInfo(name = "canonical_content_id")
    val canonicalContentId: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

@Entity(
    tableName = "source",
    indices = [Index(value = ["root_uri"], unique = true)],
)
data class SourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    val kind: String,
    @ColumnInfo(name = "root_uri")
    val rootUri: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "has_access")
    val hasAccess: Boolean = true,
    val generation: Long = 0L,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "last_indexed_at_ms")
    val lastIndexedAtMs: Long? = null,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "removed_at_ms")
    val removedAtMs: Long? = null,
)

@Entity(
    tableName = "media_locator",
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
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("content_id"),
        Index("identity_key"),
        Index("source_id"),
        Index(value = ["uri_value", "source_id"], unique = true),
        Index(value = ["content_id", "locator_id"], unique = true),
    ],
)
data class MediaLocatorEntity(
    @PrimaryKey
    @ColumnInfo(name = "locator_id")
    val locatorId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String?,
    @ColumnInfo(name = "uri_value")
    val uriValue: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    val folder: String?,
    @ColumnInfo(name = "date_added_seconds")
    val dateAddedSeconds: Long,
    val origin: String,
    val kind: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "video_width")
    val videoWidth: Int,
    @ColumnInfo(name = "video_height")
    val videoHeight: Int,
    val availability: String,
    @ColumnInfo(name = "seen_generation")
    val seenGeneration: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "active_media_locator",
    foreignKeys = [
        ForeignKey(
            entity = MediaLocatorEntity::class,
            parentColumns = ["content_id", "locator_id"],
            childColumns = ["content_id", "locator_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["content_id", "locator_id"]),
        Index("locator_id"),
    ],
)
data class ActiveMediaLocatorEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "locator_id")
    val locatorId: String,
)

@Entity(
    tableName = "timed_text_track",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GeneratedTextSlotEntity::class,
            parentColumns = ["content_id", "slot_id"],
            childColumns = ["content_id", "generated_slot_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("content_id"),
        Index("generated_slot_id"),
        Index("revision_id"),
        Index(value = ["content_id", "generated_slot_id"]),
        Index(value = ["content_id", "track_id"], unique = true),
        Index(value = ["content_id", "origin", "locator_identity"], unique = true),
    ],
)
data class TimedTextTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_id")
    val trackId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    val origin: String,
    @ColumnInfo(name = "locator_identity")
    val locatorIdentity: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String?,
    @ColumnInfo(name = "embedded_track_id")
    val embeddedTrackId: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    val format: String?,
    val language: String?,
    val label: String?,
    @ColumnInfo(name = "match_basis")
    val matchBasis: String,
    val readiness: String,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
    @ColumnInfo(name = "is_rejected")
    val isRejected: Boolean = false,
    @ColumnInfo(name = "generated_slot_id")
    val generatedSlotId: String? = null,
)

@Entity(
    tableName = "timed_text_track_role",
    primaryKeys = ["track_id", "role"],
    foreignKeys = [
        ForeignKey(
            entity = TimedTextTrackEntity::class,
            parentColumns = ["track_id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class TimedTextTrackRoleEntity(
    @ColumnInfo(name = "track_id")
    val trackId: String,
    val role: String,
)

@Entity(
    tableName = "text_selection",
    primaryKeys = ["content_id", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimedTextTrackEntity::class,
            parentColumns = ["content_id", "track_id"],
            childColumns = ["content_id", "selected_track_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("selected_track_id"),
        Index(value = ["content_id", "selected_track_id"]),
    ],
)
data class TextSelectionEntity(
    @ColumnInfo(name = "content_id")
    val contentId: String,
    val slot: String,
    @ColumnInfo(name = "selected_track_id")
    val selectedTrackId: String?,
    val mode: String,
)

@Entity(
    tableName = "content_disposition",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class ContentDispositionEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "is_collected")
    val isCollected: Boolean,
    @ColumnInfo(name = "decided_at_ms")
    val decidedAtMs: Long,
)

/** A stable, release-authored work imported from a trusted distribution package. */
@Entity(
    tableName = "work",
    indices = [
        Index(value = ["source_key"], unique = true),
        Index("last_played_at_ms"),
    ],
)
data class WorkEntity(
    @PrimaryKey
    @ColumnInfo(name = "work_id")
    val workId: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "release_code")
    val releaseCode: String,
    val title: String,
    @ColumnInfo(name = "manifest_fingerprint")
    val manifestFingerprint: String,
    @ColumnInfo(name = "profile_label")
    val profileLabel: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "last_played_at_ms")
    val lastPlayedAtMs: Long? = null,
)

@Entity(
    tableName = "work_part",
    primaryKeys = ["work_id", "content_id"],
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["work_id"],
            childColumns = ["work_id"],
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
    ],
    indices = [Index("content_id"), Index(value = ["work_id", "sort_position"])],
)
data class WorkPartEntity(
    @ColumnInfo(name = "work_id")
    val workId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "section_name")
    val sectionName: String,
    @ColumnInfo(name = "variant_key")
    val variantKey: String,
    @ColumnInfo(name = "sort_position")
    val sortPosition: Long,
    @ColumnInfo(name = "is_included")
    val isIncluded: Boolean,
)

@Entity(
    tableName = "work_progress",
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["work_id"],
            childColumns = ["work_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("content_id")],
)
data class WorkProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "work_id")
    val workId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String?,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

/** A playlist is independent from filesystem folders and media sources. */
@Entity(
    tableName = "playlist",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["system_role"], unique = true),
    ],
)
data class PlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
    val name: String,
    @ColumnInfo(name = "sort_position")
    val sortPosition: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "last_played_at_ms")
    val lastPlayedAtMs: Long? = null,
    @ColumnInfo(name = "system_role")
    val systemRole: String? = null,
)

/** A playlist entry references either one loose content or one imported work. */
@Entity(
    tableName = "playlist_entry",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["work_id"],
            childColumns = ["work_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("content_id"),
        Index("work_id"),
        Index(value = ["playlist_id", "sort_position"]),
        Index(value = ["playlist_id", "content_id"], unique = true),
        Index(value = ["playlist_id", "work_id"], unique = true),
    ],
)
data class PlaylistEntryEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
    @ColumnInfo(name = "content_id")
    val contentId: String?,
    @ColumnInfo(name = "sort_position")
    val sortPosition: Long,
    @ColumnInfo(name = "added_at_ms")
    val addedAtMs: Long,
    @ColumnInfo(name = "work_id")
    val workId: String? = null,
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String = playlistEntryId(playlistId, contentId, workId),
) {
    init {
        require((contentId == null) != (workId == null))
    }
}

private fun playlistEntryId(playlistId: String, contentId: String?, workId: String?): String =
    when {
        contentId != null -> "content:$playlistId:$contentId"
        workId != null -> "work:$playlistId:$workId"
        else -> error("Playlist entry needs a reference")
    }

typealias PlaylistItemEntity = PlaylistEntryEntity

@Entity(tableName = "import_source_preference")
data class ImportSourcePreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "last_imported_at_ms")
    val lastImportedAtMs: Long,
)

@Entity(
    tableName = "import_source_target",
    primaryKeys = ["source_key", "playlist_id"],
    foreignKeys = [
        ForeignKey(
            entity = ImportSourcePreferenceEntity::class,
            parentColumns = ["source_key"],
            childColumns = ["source_key"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlist_id")],
)
data class ImportSourceTargetEntity(
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
)

@Entity(
    tableName = "recent_entry",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("last_opened_at_ms")],
)
data class RecentEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "last_opened_at_ms")
    val lastOpenedAtMs: Long,
)

@Entity(
    tableName = "current_draft",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("content_id")],
)
data class CurrentDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = 1,
    @ColumnInfo(name = "content_id")
    val contentId: String,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "current_playback_session",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["current_content_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("current_content_id")],
)
data class CurrentPlaybackSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = 1,
    @ColumnInfo(name = "current_content_id")
    val currentContentId: String? = null,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long = 0L,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,
    @ColumnInfo(name = "playback_speed")
    val playbackSpeed: Float = 1f,
    @ColumnInfo(name = "shuffle_enabled")
    val shuffleEnabled: Boolean = false,
    @ColumnInfo(name = "repeat_mode")
    val repeatMode: Int = 0,
    @ColumnInfo(name = "queue_revision")
    val queueRevision: Long = 0L,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "current_queue_entry",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["content_id"],
            childColumns = ["content_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["position"], unique = true)],
)
data class CurrentQueueEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_id")
    val contentId: String,
    val position: Int,
    @ColumnInfo(name = "added_at_ms")
    val addedAtMs: Long,
    @ColumnInfo(name = "origin_work_id")
    val originWorkId: String? = null,
    @ColumnInfo(name = "origin_playlist_entry_id")
    val originPlaylistEntryId: String? = null,
)
