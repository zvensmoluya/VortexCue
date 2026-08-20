package io.github.zvensmoluya.vortexcue.data

import io.github.zvensmoluya.vortexcue.core.model.ActiveTextSelection
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaLocator
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.PlayableContentUnit
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextLocator
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextRole
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind

/** Ordering used by the Content tab. The value is persisted in Settings DataStore. */
enum class ContentSort {
    RecentAdded,
    RecentPlayed,
    Title,

    ;

    val displayName: String
        get() =
            when (this) {
                RecentAdded -> "最近加入"
                RecentPlayed -> "最近播放"
                Title -> "标题"
            }
}

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
    val discoveredTextCandidates: List<DiscoveredTextCandidate> = emptyList(),
    val activeText: ActiveTextSelection,
    val subtitleOffsetMs: Long,
    val disposition: ContentDisposition?,
) {
    val availableTextTracks: List<TimedTextTrack>
        get() = timedTextTracks + discoveredTextCandidates.map(DiscoveredTextCandidate::toTrackProjection)

    val contentUnit: PlayableContentUnit
        get() =
            PlayableContentUnit(
                stableId = contentId,
                mediaLocators = mediaLocators,
                activeMediaLocatorId = activeMediaLocatorId,
                timedTextTracks = availableTextTracks,
                activeText = activeText,
                subtitleOffsetMs = subtitleOffsetMs,
            )

    val activeMedia: MediaSourceRef
        get() = contentUnit.media
}

data class DiscoveredTextCandidate(
    val candidateId: String,
    val displayName: String,
    val mimeType: String?,
    val format: String?,
    val language: String?,
    val label: String?,
    val origin: TimedTextOrigin,
    val roles: List<TimedTextRole>,
    val matchBasis: TimedTextMatchBasis,
    val failureMessage: String?,
)

internal fun DiscoveredTextCandidate.toTrackProjection(): TimedTextTrack =
    TimedTextTrack(
        stableId = candidateId,
        origin = origin,
        locator =
            TimedTextLocator.DiscoveredCandidate(
                candidateId = candidateId,
                displayName = displayName,
                mimeType = mimeType,
            ),
        format = format,
        language = language,
        label = label,
        roles = roles,
        matchBasis = matchBasis,
        readiness = if (failureMessage == null) TimedTextReadiness.Unknown else TimedTextReadiness.Unreadable,
        failureMessage = failureMessage,
    )

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

data class ContentFolderSummary(
    val folderId: String,
    val name: String,
    val itemCount: Int = 0,
)

data class SourceScanDescriptor(
    val sourceId: String,
    val kind: LibrarySourceKind,
    val rootUri: String?,
    val displayName: String,
)
