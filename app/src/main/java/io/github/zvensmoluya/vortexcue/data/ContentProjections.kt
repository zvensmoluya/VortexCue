package io.github.zvensmoluya.vortexcue.data

import io.github.zvensmoluya.vortexcue.core.model.ActiveTextSelection
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaLocator
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.PlayableContentUnit
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.library.LibraryShortcut
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind

enum class ContentDisposition {
    Collected,
    Ignored,
}

data class ContentSummary(
    val contentId: String,
    val locatorId: String,
    val media: MediaSourceRef,
    val availability: ContentAvailability,
    val disposition: ContentDisposition?,
    val lastOpenedAtMs: Long?,
)

data class ContentDetails(
    val contentId: String,
    val mediaLocators: List<MediaLocator>,
    val activeMediaLocatorId: String,
    val timedTextTracks: List<TimedTextTrack>,
    val activeText: ActiveTextSelection,
    val subtitleOffsetMs: Long,
    val disposition: ContentDisposition?,
) {
    val contentUnit: PlayableContentUnit
        get() =
            PlayableContentUnit(
                stableId = contentId,
                mediaLocators = mediaLocators,
                activeMediaLocatorId = activeMediaLocatorId,
                timedTextTracks = timedTextTracks,
                activeText = activeText,
                subtitleOffsetMs = subtitleOffsetMs,
            )

    val activeMedia: MediaSourceRef
        get() = contentUnit.media
}

data class SourceSummary(
    val sourceId: String,
    val kind: LibrarySourceKind,
    val rootUri: String?,
    val displayName: String,
    val hasAccess: Boolean,
    val generation: Long,
    val lastIndexedAtMs: Long?,
    val lastError: String?,
    val itemCount: Int,
)

data class PlaylistSummary(
    val playlistId: String,
    val name: String,
)

data class HomeLibraryProjection(
    val collectedCount: Int = 0,
    val discoveryCount: Int = 0,
    val ignoredCount: Int = 0,
    val recentCount: Int = 0,
    val collectedPreview: List<ContentSummary> = emptyList(),
    val recentPreview: List<ContentSummary> = emptyList(),
    val sources: List<SourceSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val shortcuts: List<LibraryShortcut> = emptyList(),
)

data class SourceScanDescriptor(
    val sourceId: String,
    val kind: LibrarySourceKind,
    val rootUri: String?,
    val displayName: String,
)
