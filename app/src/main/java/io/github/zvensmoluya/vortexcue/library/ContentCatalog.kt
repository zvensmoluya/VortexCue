package io.github.zvensmoluya.vortexcue.library

import android.net.Uri
import io.github.zvensmoluya.vortexcue.core.model.*

data class TimedTextTrackCandidate(
    val mediaUriValue: String,
    val track: TimedTextTrack,
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
    val mediaStem = mediaDisplayName.substringBeforeLast('.').lowercase()
    val subtitleStem = subtitleDisplayName.substringBeforeLast('.').lowercase()
    val suffix =
        when {
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
            if (suffix == null) {
                TimedTextMatchBasis.ExactStem
            } else {
                TimedTextMatchBasis.LanguageSuffix
            },
    )
}

internal fun buildSidecarTextTrackCandidate(
    media: MediaSourceRef,
    subtitle: SubtitleSourceRef,
): TimedTextTrackCandidate? {
    val metadata =
        resolveSidecarTrackMetadata(media.displayName, subtitle.displayName) ?: return null
    val contentId = stableContentId(media.uri.toString())
    val locator = subtitle.toTimedTextLocator()
    val origin =
        if (metadata.format == "lrc") TimedTextOrigin.Lyrics else TimedTextOrigin.Sidecar
    return TimedTextTrackCandidate(
        mediaUriValue = media.uri.toString(),
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

internal fun legacyTimedTextTrack(
    contentId: String,
    uriValue: String,
    displayName: String,
    mimeType: String?,
    cachedFileName: String?,
): TimedTextTrack {
    val locator =
        TimedTextLocator(
            uriValue = uriValue,
            displayName = displayName,
            mimeType = mimeType,
            cachedFileName = cachedFileName,
        )
    val format = displayName.substringAfterLast('.', "").lowercase().takeIf(String::isNotBlank)
    val origin = if (format == "lrc") TimedTextOrigin.Lyrics else TimedTextOrigin.Sidecar
    return TimedTextTrack(
        stableId = stableTimedTextTrackId(contentId, origin, locator.stableIdentity),
        origin = origin,
        locator = locator,
        format = format,
        matchBasis = TimedTextMatchBasis.Legacy,
    )
}

internal fun manualTimedTextTrack(
    contentId: String,
    source: SubtitleSourceRef,
): TimedTextTrack {
    val locator = source.toTimedTextLocator()
    val format = source.displayName.substringAfterLast('.', "").lowercase().takeIf(String::isNotBlank)
    val origin = if (format == "lrc") TimedTextOrigin.Lyrics else TimedTextOrigin.Sidecar
    return TimedTextTrack(
        stableId = stableTimedTextTrackId(contentId, origin, locator.stableIdentity),
        origin = origin,
        locator = locator,
        format = format,
        roles = inferRolesFromDisplayName(source.displayName),
        matchBasis = TimedTextMatchBasis.UserSelected,
        readiness = TimedTextReadiness.Ready,
    )
}

internal fun TextTrackCatalogState.mergeTracks(
    incoming: List<TimedTextTrack>,
): TextTrackCatalogState {
    val tracksById = LinkedHashMap<String, TimedTextTrack>()
    tracks.forEach { tracksById[it.stableId] = it }
    incoming.forEach { track ->
        tracksById[track.stableId] =
            tracksById[track.stableId]?.mergeMetadata(track) ?: track
    }
    val merged = tracksById.values.toList()
    val validIds = merged.map(TimedTextTrack::stableId).toSet()
    var selection =
        activeText.copy(
            primaryTrackId = activeText.primaryTrackId?.takeIf(validIds::contains),
            secondaryTrackId = activeText.secondaryTrackId?.takeIf(validIds::contains),
            rejectedTrackIds = activeText.rejectedTrackIds.distinct(),
        )
    if (
        selection.primaryMode == TextSelectionMode.Automatic &&
            selection.primaryTrackId == null
    ) {
        selection = selection.copy(primaryTrackId = chooseAutomaticPrimary(merged, selection))
    }
    return TextTrackCatalogState(
        tracks = sortTimedTextTracks(merged, selection),
        activeText = selection,
    )
}

internal fun TextTrackCatalogState.selectManualPrimary(
    track: TimedTextTrack?,
): TextTrackCatalogState {
    val merged = if (track == null) this else mergeTracks(listOf(track))
    val selection =
        merged.activeText.copy(
            primaryTrackId = track?.stableId,
            primaryMode = TextSelectionMode.Manual,
        )
    return merged.copy(
        tracks = sortTimedTextTracks(merged.tracks, selection),
        activeText = selection,
    )
}

internal fun migratePlayableContentUnits(
    storedUnits: List<PlayableContentUnit>,
    mediaSnapshots: List<MediaSourceRef>,
    legacyAssociations: List<SubtitleAssociation>,
): List<PlayableContentUnit> {
    var units = upsertPlayableContentUnits(storedUnits, mediaSnapshots, availability = null)
    legacyAssociations.groupBy { it.mediaUri.toString() }.forEach { (uriValue, associations) ->
        val index = units.indexOfFirst { it.media.uri.toString() == uriValue }
        if (index < 0) return@forEach
        val unit = units[index]
        val legacyTracks =
            associations.map { association ->
                legacyTimedTextTrack(
                    contentId = unit.stableId,
                    uriValue = association.subtitle.uri.toString(),
                    displayName = association.subtitle.displayName,
                    mimeType = association.subtitle.mimeType,
                    cachedFileName = association.subtitle.cachedFileName,
                )
            }
        var catalogState = unit.textCatalogState().mergeTracks(legacyTracks)
        if (
            catalogState.activeText.primaryTrackId == null &&
                catalogState.activeText.primaryMode == TextSelectionMode.Automatic
        ) {
            catalogState = catalogState.selectManualPrimary(legacyTracks.firstOrNull())
        }
        units = units.toMutableList().also { it[index] = unit.withTextCatalogState(catalogState) }
    }
    return units
}

internal fun upsertPlayableContentUnits(
    existing: List<PlayableContentUnit>,
    mediaSnapshots: List<MediaSourceRef>,
    availability: ContentAvailability?,
): List<PlayableContentUnit> {
    val unitsById = LinkedHashMap<String, PlayableContentUnit>()
    existing.forEach { unitsById[it.stableId] = it }
    mediaSnapshots.distinctBy { it.uri.toString() }.forEach { media ->
        val stableId = stableContentId(media.uri.toString())
        val priorEntry =
            unitsById.entries.firstOrNull {
                it.key == stableId || it.value.media.uri == media.uri
            }
        val prior = priorEntry?.value
        if (priorEntry != null && priorEntry.key != stableId) {
            unitsById.remove(priorEntry.key)
        }
        unitsById[stableId] =
            if (prior == null) {
                PlayableContentUnit(
                    stableId = stableId,
                    media = media,
                    availability = availability ?: ContentAvailability.Unchecked,
                )
            } else {
                prior.copy(
                    stableId = stableId,
                    media = media,
                    sourceId = media.sourceId ?: prior.sourceId,
                    availability = availability ?: prior.availability,
                )
            }
    }
    return unitsById.values.distinctBy { it.stableId }
}

internal fun LibraryConfig.withMediaSnapshots(
    media: List<MediaSourceRef>,
    availability: ContentAvailability,
): LibraryConfig =
    copy(
        contentUnits =
            upsertPlayableContentUnits(
                existing = contentUnits,
                mediaSnapshots = media,
                availability = availability,
            ),
    )

internal fun LibraryConfig.withTimedTextCandidates(
    candidates: List<TimedTextTrackCandidate>,
): LibraryConfig {
    if (candidates.isEmpty()) return this
    val candidatesByMedia = candidates.groupBy(TimedTextTrackCandidate::mediaUriValue)
    return copy(
        contentUnits =
            contentUnits.map { unit ->
                val tracks = candidatesByMedia[unit.media.uri.toString()]?.map { it.track }
                    ?: return@map unit
                unit.withTextCatalogState(unit.textCatalogState().mergeTracks(tracks))
            },
    )
}

internal fun LibraryConfig.withManualPrimaryText(
    media: MediaSourceRef,
    subtitle: SubtitleSourceRef?,
): LibraryConfig {
    val withMedia = withMediaSnapshots(listOf(media), ContentAvailability.Available)
    val contentId = stableContentId(media.uri.toString())
    return withMedia.copy(
        contentUnits =
            withMedia.contentUnits.map { unit ->
                if (unit.stableId != contentId) return@map unit
                val track = subtitle?.let { manualTimedTextTrack(contentId, it) }
                unit.withTextCatalogState(unit.textCatalogState().selectManualPrimary(track))
            },
    )
}

internal fun LibraryConfig.withResolvedPrimaryText(
    media: MediaSourceRef,
    subtitle: SubtitleSourceRef,
): LibraryConfig {
    val withMedia = withMediaSnapshots(listOf(media), ContentAvailability.Available)
    val contentId = stableContentId(media.uri.toString())
    return withMedia.copy(
        contentUnits =
            withMedia.contentUnits.map { unit ->
                if (unit.stableId != contentId) return@map unit
                val existing =
                    unit.timedTextTracks.firstOrNull {
                        it.locator.uriValue == subtitle.uri.toString()
                    }
                val resolved =
                    if (existing == null) {
                        legacyTimedTextTrack(
                            contentId = contentId,
                            uriValue = subtitle.uri.toString(),
                            displayName = subtitle.displayName,
                            mimeType = subtitle.mimeType,
                            cachedFileName = subtitle.cachedFileName,
                        ).copy(readiness = TimedTextReadiness.Ready)
                    } else {
                        existing.copy(
                            locator =
                                existing.locator.copy(
                                    displayName = subtitle.displayName,
                                    mimeType = subtitle.mimeType,
                                    cachedFileName = subtitle.cachedFileName,
                                ),
                            readiness = TimedTextReadiness.Ready,
                        )
                    }
                var state = unit.textCatalogState().mergeTracks(listOf(resolved))
                if (existing == null && state.activeText.primaryTrackId == null) {
                    state = state.selectManualPrimary(resolved)
                }
                unit.withTextCatalogState(state)
            },
    )
}

internal fun LibraryConfig.withoutContentUris(uriValues: Set<String>): LibraryConfig =
    copy(contentUnits = contentUnits.filterNot { it.media.uri.toString() in uriValues })

internal fun LibraryConfig.activePrimarySubtitleSource(
    media: MediaSourceRef,
): SubtitleSourceRef? {
    val unit = contentUnits.firstOrNull { it.media.uri == media.uri }
    if (unit != null) {
        val selected =
            unit.timedTextTracks.firstOrNull {
                it.stableId == unit.activeText.primaryTrackId
            }
        selected?.locator?.toSubtitleSourceRef()?.let { return it }
        if (unit.activeText.primaryMode == TextSelectionMode.Manual) return null
    }
    return subtitleAssociations.firstOrNull { it.mediaUri == media.uri }?.subtitle
}

internal fun LibraryConfig.hasTimedTextTracks(media: MediaSourceRef): Boolean =
    contentUnits
        .firstOrNull { it.media.uri == media.uri }
        ?.timedTextTracks
        ?.isNotEmpty() == true

private fun PlayableContentUnit.textCatalogState(): TextTrackCatalogState =
    TextTrackCatalogState(timedTextTracks, activeText)

private fun PlayableContentUnit.withTextCatalogState(
    state: TextTrackCatalogState,
): PlayableContentUnit =
    copy(timedTextTracks = state.tracks, activeText = state.activeText)

private fun SubtitleSourceRef.toTimedTextLocator(): TimedTextLocator =
    TimedTextLocator(
        uriValue = uri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        cachedFileName = cachedFileName,
    )

private fun TimedTextLocator.toSubtitleSourceRef(): SubtitleSourceRef? =
    uriValue?.let { value ->
        SubtitleSourceRef(
            uri = Uri.parse(value),
            displayName = displayName,
            mimeType = mimeType,
            cachedFileName = cachedFileName,
        )
    }

private fun TimedTextTrack.mergeMetadata(incoming: TimedTextTrack): TimedTextTrack =
    copy(
        locator =
            incoming.locator.copy(
                cachedFileName = incoming.locator.cachedFileName ?: locator.cachedFileName,
            ),
        format = incoming.format ?: format,
        language = incoming.language ?: language,
        label = incoming.label ?: label,
        roles = (roles + incoming.roles).distinct(),
        matchBasis =
            if (matchBasis in setOf(TimedTextMatchBasis.UserSelected, TimedTextMatchBasis.Legacy)) {
                matchBasis
            } else {
                incoming.matchBasis
            },
        readiness =
            if (incoming.readiness == TimedTextReadiness.Unknown) readiness else incoming.readiness,
    )

private fun chooseAutomaticPrimary(
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
): String? {
    val usable =
        tracks.filter {
            it.stableId !in selection.rejectedTrackIds &&
                it.readiness !in
                setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed)
        }
    val embeddedDefaults =
        usable.filter {
            it.origin == TimedTextOrigin.Embedded && TimedTextRole.Default in it.roles
        }
    if (embeddedDefaults.size == 1) return embeddedDefaults.single().stableId
    return usable.singleOrNull { it.matchBasis == TimedTextMatchBasis.ExactStem }?.stableId
        ?.takeIf { usable.size == 1 }
}

private fun sortTimedTextTracks(
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
): List<TimedTextTrack> =
    tracks.sortedWith(
        compareBy<TimedTextTrack>(
            { if (it.stableId == selection.primaryTrackId) 0 else 1 },
            ::timedTextTrackRank,
            { it.language.orEmpty() },
            { it.label.orEmpty() },
            { it.locator.displayName.lowercase() },
        ),
    )

private fun timedTextTrackRank(track: TimedTextTrack): Int =
    when {
        track.matchBasis == TimedTextMatchBasis.UserSelected -> 0
        track.matchBasis == TimedTextMatchBasis.Legacy -> 1
        track.origin == TimedTextOrigin.Embedded && TimedTextRole.Default in track.roles -> 2
        track.matchBasis == TimedTextMatchBasis.ExactStem -> 3
        track.matchBasis == TimedTextMatchBasis.LanguageSuffix -> 4
        else -> 5
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
