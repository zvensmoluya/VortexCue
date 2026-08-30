package io.github.zvensmoluya.vortexcue.library

import android.net.Uri
import io.github.zvensmoluya.vortexcue.core.model.ActiveTextSelection
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextLocator
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextRole
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.mediaIdentityKey
import io.github.zvensmoluya.vortexcue.core.model.stableContentId
import io.github.zvensmoluya.vortexcue.core.model.stableTimedTextTrackId

data class TimedTextTrackCandidate(
    val mediaUriValue: String,
    val track: TimedTextTrack,
    val externalSource: SubtitleSourceRef? = null,
)

internal data class SidecarTrackMetadata(
    val format: String,
    val language: String?,
    val label: String?,
    val roles: List<TimedTextRole>,
    val matchBasis: TimedTextMatchBasis,
)

internal data class TextTrackCatalogState(
    val tracks: List<TimedTextTrack> = emptyList(),
    val activeText: ActiveTextSelection = ActiveTextSelection(),
)

internal fun resolveSidecarTrackMetadata(
    mediaDisplayName: String,
    subtitleDisplayName: String,
): SidecarTrackMetadata? {
    val mediaName = mediaDisplayName.lowercase()
    val mediaStem = mediaName.substringBeforeLast('.')
    val subtitleStem = subtitleDisplayName.substringBeforeLast('.').lowercase()
    val suffix =
        when {
            // Tools commonly append the subtitle extension to the complete media filename,
            // for example `episode.wav.vtt`. This is still an exact sidecar match: `wav`
            // describes the media container and must not be treated as a language label.
            subtitleStem == mediaName -> null
            subtitleStem == mediaStem -> null
            subtitleStem.startsWith("$mediaStem.") -> subtitleStem.removePrefix("$mediaStem.")
            else -> return null
        }
    val parts = suffix?.split('.')?.filter(String::isNotBlank).orEmpty()
    val roles = parts.mapNotNull(::timedTextRole).distinct()
    val language = parts.firstOrNull { timedTextRole(it) == null }
    return SidecarTrackMetadata(
        format = subtitleDisplayName.substringAfterLast('.', "").lowercase(),
        language = language,
        label = suffix,
        roles = roles,
        matchBasis =
            when {
                isLowConfidenceStem(mediaStem) -> TimedTextMatchBasis.FilenameCandidate
                suffix == null -> TimedTextMatchBasis.ExactStem
                else -> TimedTextMatchBasis.LanguageSuffix
            },
    )
}

internal fun buildSidecarTextTrackCandidate(
    media: MediaSourceRef,
    subtitle: SubtitleSourceRef,
): TimedTextTrackCandidate? {
    val metadata = resolveSidecarTrackMetadata(media.displayName, subtitle.displayName) ?: return null
    val contentId = stableContentId(mediaIdentityKey(media.uri.toString()))
    val origin = if (metadata.format == "lrc") TimedTextOrigin.Lyrics else TimedTextOrigin.Sidecar
    val candidateId = stableTimedTextTrackId(contentId, origin, "candidate:${subtitle.uri}")
    val locator =
        TimedTextLocator.DiscoveredCandidate(
            candidateId = candidateId,
            displayName = subtitle.displayName,
            mimeType = subtitle.mimeType,
        )
    return TimedTextTrackCandidate(
        mediaUriValue = media.uri.toString(),
        externalSource = subtitle,
        track =
            TimedTextTrack(
                stableId = stableTimedTextTrackId(contentId, origin, locator.stableIdentity),
                origin = origin,
                locator = locator,
                format = metadata.format,
                language = metadata.language,
                label = metadata.label,
                roles = metadata.roles,
                matchBasis = metadata.matchBasis,
            ),
    )
}

internal fun TextTrackCatalogState.mergeTracks(
    incoming: List<TimedTextTrack>,
): TextTrackCatalogState {
    val tracksById = LinkedHashMap<String, TimedTextTrack>()
    tracks.forEach { tracksById[it.stableId] = it }
    incoming.forEach { track ->
        val existingId =
            tracksById.values.firstOrNull {
                it.origin == track.origin && it.locator.stableIdentity == track.locator.stableIdentity
            }?.stableId
        val retainedId = existingId ?: track.stableId
        tracksById[retainedId] = tracksById[retainedId]?.mergeMetadata(track) ?: track
    }
    val merged = tracksById.values.toList()
    val validIds = merged.map(TimedTextTrack::stableId).toSet()
    val primaryTrackId = activeText.primaryTrackId?.takeIf(validIds::contains)
    var selection =
        activeText.copy(
            primaryTrackId = primaryTrackId,
            secondaryTrackId =
                activeText.secondaryTrackId
                    ?.takeIf(validIds::contains)
                    ?.takeUnless { it == primaryTrackId },
            rejectedTrackIds = activeText.rejectedTrackIds.distinct(),
        )
    selection = normalizeEmbeddedSelection(merged, selection, TextTrackSlot.Primary)
    if (selection.primaryMode == TextSelectionMode.Automatic && selection.primaryTrackId == null) {
        selection = selection.copy(primaryTrackId = chooseAutomaticPrimary(merged, selection))
    }
    return TextTrackCatalogState(
        tracks = sortTimedTextTracks(merged, selection),
        activeText = selection,
    )
}

internal fun TextTrackCatalogState.selectManualPrimary(
    track: TimedTextTrack?,
): TextTrackCatalogState = selectManualTrack(TextTrackSlot.Primary, track)

internal fun TextTrackCatalogState.selectManualTrack(
    slot: TextTrackSlot,
    track: TimedTextTrack?,
): TextTrackCatalogState {
    val merged = if (track == null) this else mergeTracks(listOf(track))
    val requestedSelection =
        when (slot) {
            TextTrackSlot.Primary ->
                merged.activeText.copy(
                    primaryTrackId = track?.stableId,
                    primaryMode = TextSelectionMode.Manual,
                    secondaryTrackId = merged.activeText.secondaryTrackId?.takeUnless { it == track?.stableId },
                )
            TextTrackSlot.Secondary ->
                merged.activeText.copy(
                    secondaryTrackId = track?.stableId?.takeUnless { it == merged.activeText.primaryTrackId },
                    secondaryMode = TextSelectionMode.Manual,
                )
        }
    val selection = normalizeEmbeddedSelection(merged.tracks, requestedSelection, slot)
    return merged.copy(
        tracks = sortTimedTextTracks(merged.tracks, selection),
        activeText = selection,
    )
}

internal fun TextTrackCatalogState.reconcileEmbeddedTracks(
    incoming: List<TimedTextTrack>,
): TextTrackCatalogState {
    var reconciled = mergeTracks(incoming)
    val discoveredIds = incoming.map(TimedTextTrack::stableId).toSet()
    reconciled.tracks
        .filter { it.origin == TimedTextOrigin.Embedded && it.stableId !in discoveredIds }
        .map(TimedTextTrack::stableId)
        .forEach { trackId ->
            reconciled =
                reconciled.withTrackReadiness(
                    trackId = trackId,
                    readiness = TimedTextReadiness.Unreadable,
                    failureMessage = "当前媒体未发现此内嵌文字轨",
                )
        }
    incoming
        .filter { it.readiness in setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed) }
        .forEach { track ->
            reconciled =
                reconciled.withTrackReadiness(
                    trackId = track.stableId,
                    readiness = track.readiness,
                    failureMessage = track.failureMessage,
                )
        }
    return reconciled
}

internal fun TextTrackCatalogState.restoreAutomaticTrack(slot: TextTrackSlot): TextTrackCatalogState {
    val selection =
        when (slot) {
            TextTrackSlot.Primary -> {
                val automatic = activeText.copy(primaryTrackId = null, primaryMode = TextSelectionMode.Automatic)
                automatic.copy(primaryTrackId = chooseAutomaticPrimary(tracks, automatic))
            }
            TextTrackSlot.Secondary ->
                activeText.copy(secondaryTrackId = null, secondaryMode = TextSelectionMode.Automatic)
        }
    return copy(tracks = sortTimedTextTracks(tracks, selection), activeText = selection)
}

internal fun TextTrackCatalogState.withTrackReadiness(
    trackId: String,
    readiness: TimedTextReadiness,
    failureMessage: String? = null,
): TextTrackCatalogState {
    if (tracks.none { it.stableId == trackId }) return this
    val updatedTracks =
        tracks.map { track ->
            if (track.stableId != trackId) return@map track
            track.copy(
                locator = track.locator,
                readiness = readiness,
                failureMessage =
                    failureMessage?.takeIf {
                        readiness in
                            setOf(
                                TimedTextReadiness.Unreadable,
                                TimedTextReadiness.ParseFailed,
                                TimedTextReadiness.MissingArtifact,
                            )
                    },
            )
        }
    var selection = activeText
    if (
        selection.primaryTrackId == trackId &&
            selection.primaryMode == TextSelectionMode.Automatic &&
            readiness in
                setOf(
                    TimedTextReadiness.Unreadable,
                    TimedTextReadiness.ParseFailed,
                    TimedTextReadiness.MissingArtifact,
                )
    ) {
        selection = selection.copy(primaryTrackId = null)
        selection = selection.copy(primaryTrackId = chooseAutomaticPrimary(updatedTracks, selection))
    }
    if (
        selection.secondaryTrackId == trackId &&
            selection.secondaryMode == TextSelectionMode.Automatic &&
            readiness in setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed)
    ) {
        selection = selection.copy(secondaryTrackId = null)
    }
    return TextTrackCatalogState(
        tracks = sortTimedTextTracks(updatedTracks, selection),
        activeText = selection,
    )
}

internal fun shouldRetainContentAfterLocatorLoss(
    hasRetainedReference: Boolean,
    hasAvailableLocator: Boolean,
): Boolean = hasRetainedReference || hasAvailableLocator

internal fun resolveActiveMediaLocatorId(
    locatorAvailability: List<Pair<String, ContentAvailability>>,
    currentActiveId: String,
    preferredActiveId: String?,
): String {
    require(locatorAvailability.isNotEmpty())
    val preferred =
        locatorAvailability.firstOrNull { it.first == preferredActiveId }
            ?.takeUnless { it.second == ContentAvailability.Unavailable }
    val current = locatorAvailability.firstOrNull { it.first == currentActiveId }
    return preferred?.first
        ?: current?.takeUnless { it.second == ContentAvailability.Unavailable }?.first
        ?: locatorAvailability.firstOrNull { it.second == ContentAvailability.Available }?.first
        ?: current?.first
        ?: locatorAvailability.first().first
}

internal fun TextTrackCatalogState.rekeyForContent(contentId: String): TextTrackCatalogState {
    val remappedTracks = tracks.map { it.rekeyForContent(contentId) }
    val remappedIds =
        tracks.map(TimedTextTrack::stableId)
            .zip(remappedTracks.map(TimedTextTrack::stableId))
            .toMap()
    val remappedSelection =
        activeText.copy(
            primaryTrackId = activeText.primaryTrackId?.let(remappedIds::get),
            secondaryTrackId = activeText.secondaryTrackId?.let(remappedIds::get),
            rejectedTrackIds = activeText.rejectedTrackIds.map { remappedIds[it] ?: it }.distinct(),
        )
    return TextTrackCatalogState(
        tracks = sortTimedTextTracks(remappedTracks, remappedSelection),
        activeText = remappedSelection,
    )
}

private fun TimedTextTrack.rekeyForContent(contentId: String): TimedTextTrack =
    copy(stableId = stableTimedTextTrackId(contentId, origin, locator.stableIdentity))

private fun TimedTextTrack.mergeMetadata(incoming: TimedTextTrack): TimedTextTrack =
    copy(
        locator = incoming.locator,
        format = incoming.format ?: format,
        language = incoming.language ?: language,
        label = incoming.label ?: label,
        roles = (roles + incoming.roles).distinct(),
        matchBasis = if (matchBasis == TimedTextMatchBasis.UserSelected) matchBasis else incoming.matchBasis,
        readiness = if (incoming.readiness == TimedTextReadiness.Unknown) readiness else incoming.readiness,
        failureMessage =
            if (incoming.readiness == TimedTextReadiness.Unknown) failureMessage else incoming.failureMessage,
    )

private fun chooseAutomaticPrimary(
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
): String? {
    val secondaryIsEmbedded =
        tracks.firstOrNull { it.stableId == selection.secondaryTrackId }?.origin == TimedTextOrigin.Embedded
    val usable =
        tracks.filter {
            it.stableId !in selection.rejectedTrackIds &&
                it.stableId != selection.secondaryTrackId &&
                (!secondaryIsEmbedded || it.origin != TimedTextOrigin.Embedded) &&
                it.readiness !in setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed)
        }
    val embeddedDefaults = usable.filter { it.origin == TimedTextOrigin.Embedded && TimedTextRole.Default in it.roles }
    if (embeddedDefaults.size == 1) return embeddedDefaults.single().stableId
    return usable.singleOrNull { it.matchBasis == TimedTextMatchBasis.ExactStem }?.stableId
        ?.takeIf { usable.size == 1 }
}

private fun normalizeEmbeddedSelection(
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
    preferredSlot: TextTrackSlot,
): ActiveTextSelection {
    val tracksById = tracks.associateBy(TimedTextTrack::stableId)
    val primaryIsEmbedded = tracksById[selection.primaryTrackId]?.origin == TimedTextOrigin.Embedded
    val secondaryIsEmbedded = tracksById[selection.secondaryTrackId]?.origin == TimedTextOrigin.Embedded
    if (!primaryIsEmbedded || !secondaryIsEmbedded) return selection
    return when (preferredSlot) {
        TextTrackSlot.Primary -> selection.copy(secondaryTrackId = null)
        TextTrackSlot.Secondary -> {
            var normalized = selection.copy(primaryTrackId = null)
            if (normalized.primaryMode == TextSelectionMode.Automatic) {
                normalized = normalized.copy(primaryTrackId = chooseAutomaticPrimary(tracks, normalized))
            }
            normalized
        }
    }
}

private fun sortTimedTextTracks(
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
): List<TimedTextTrack> =
    tracks.sortedWith(
        compareBy<TimedTextTrack>(
            {
                when (it.stableId) {
                    selection.primaryTrackId -> 0
                    selection.secondaryTrackId -> 1
                    else -> 2
                }
            },
            ::timedTextTrackRank,
            { it.language.orEmpty() },
            { it.label.orEmpty() },
            { it.locator.displayName.lowercase() },
        ),
    )

private fun timedTextTrackRank(track: TimedTextTrack): Int =
    when {
        track.matchBasis == TimedTextMatchBasis.UserSelected -> 0
        track.matchBasis == TimedTextMatchBasis.GeneratedResult -> 1
        track.origin == TimedTextOrigin.Embedded && TimedTextRole.Default in track.roles -> 2
        track.matchBasis == TimedTextMatchBasis.ExactStem -> 3
        track.matchBasis == TimedTextMatchBasis.LanguageSuffix -> 4
        track.matchBasis == TimedTextMatchBasis.FilenameCandidate -> 5
        else -> 6
    }

private fun isLowConfidenceStem(stem: String): Boolean {
    val normalized = stem.lowercase().filter(Char::isLetterOrDigit)
    return normalized.length <= 2 ||
        normalized in setOf("audio", "video", "track", "subtitle", "sub", "caption", "captions")
}

private fun timedTextRole(value: String): TimedTextRole? =
    when (value.lowercase()) {
        "default" -> TimedTextRole.Default
        "forced" -> TimedTextRole.Forced
        "sdh" -> TimedTextRole.Sdh
        "cc" -> TimedTextRole.ClosedCaptions
        else -> null
    }

private fun inferRolesFromDisplayName(displayName: String): List<TimedTextRole> =
    displayName
        .substringBeforeLast('.')
        .split('.')
        .mapNotNull(::timedTextRole)
        .distinct()
