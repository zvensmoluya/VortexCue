package io.github.zvensmoluya.vortexcue.data.db

data class ContentSummaryRow(
    val contentId: String,
    val locatorId: String,
    val uriValue: String,
    val displayName: String,
    val mimeType: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val folder: String?,
    val dateAddedSeconds: Long,
    val origin: String,
    val sourceId: String?,
    val kind: String,
    val sizeBytes: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val availability: String,
    val isCollected: Boolean?,
    val lastOpenedAtMs: Long?,
)

data class SourceSummaryRow(
    val sourceId: String,
    val kind: String,
    val rootUri: String?,
    val displayName: String,
    val hasAccess: Boolean,
    val generation: Long,
    val lastIndexedAtMs: Long?,
    val lastError: String?,
    val itemCount: Int,
)

data class PlaylistSummaryRow(
    val playlistId: String,
    val name: String,
    val itemCount: Int,
    val sortPosition: Long,
    val lastPlayedAtMs: Long?,
    val systemRole: String?,
)

data class QueueItemRow(
    val position: Int,
    val contentId: String,
    val locatorId: String,
    val uriValue: String,
    val displayName: String,
    val mimeType: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val folder: String?,
    val dateAddedSeconds: Long,
    val origin: String,
    val sourceId: String?,
    val kind: String,
    val sizeBytes: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val availability: String,
)
