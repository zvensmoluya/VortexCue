package io.github.zvensmoluya.vortexcue.data

import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef

data class PlaybackItem(
    val contentId: String,
    val locatorId: String,
    val media: MediaSourceRef,
    val availability: ContentAvailability,
    val originWorkId: String? = null,
    val originPlaylistEntryId: String? = null,
)

data class QueueContentRef(
    val contentId: String,
    val originWorkId: String? = null,
    val originPlaylistEntryId: String? = null,
)

data class PlaybackRecoveryProjection(
    val queue: List<PlaybackItem> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val queueRevision: Long = 0L,
) {
    val currentItem: PlaybackItem?
        get() = queue.getOrNull(currentIndex)
}
