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
    val contentId = stableContentId(mediaIdentityKey(media.uri.toString()))
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
                    secondaryTrackId =
                        merged.activeText.secondaryTrackId
                            ?.takeUnless { it == track?.stableId },
                )
            TextTrackSlot.Secondary ->
                merged.activeText.copy(
                    secondaryTrackId =
                        track?.stableId
                            ?.takeUnless { it == merged.activeText.primaryTrackId },
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
    val unavailableIds =
        reconciled.tracks
            .filter {
                it.origin == TimedTextOrigin.Embedded && it.stableId !in discoveredIds
            }.map(TimedTextTrack::stableId)
    unavailableIds.forEach { trackId ->
        reconciled =
            reconciled.withTrackReadiness(
                trackId = trackId,
                readiness = TimedTextReadiness.Unreadable,
                failureMessage = "当前媒体未发现此内嵌文字轨",
            )
    }
    incoming
        .filter {
            it.readiness in
                setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed)
        }.forEach { track ->
            reconciled =
                reconciled.withTrackReadiness(
                    trackId = track.stableId,
                    readiness = track.readiness,
                    failureMessage = track.failureMessage,
                )
        }
    return reconciled
}

internal fun TextTrackCatalogState.restoreAutomaticTrack(
    slot: TextTrackSlot,
): TextTrackCatalogState {
    val selection =
        when (slot) {
            TextTrackSlot.Primary -> {
                val automatic =
                    activeText.copy(
                        primaryTrackId = null,
                        primaryMode = TextSelectionMode.Automatic,
                    )
                automatic.copy(primaryTrackId = chooseAutomaticPrimary(tracks, automatic))
            }
            TextTrackSlot.Secondary ->
                activeText.copy(
                    secondaryTrackId = null,
                    secondaryMode = TextSelectionMode.Automatic,
                )
        }
    return copy(
        tracks = sortTimedTextTracks(tracks, selection),
        activeText = selection,
    )
}

internal fun TextTrackCatalogState.withTrackReadiness(
    trackId: String,
    readiness: TimedTextReadiness,
    failureMessage: String? = null,
    resolvedSource: SubtitleSourceRef? = null,
): TextTrackCatalogState {
    if (tracks.none { it.stableId == trackId }) return this
    val updatedTracks =
        tracks.map { track ->
            if (track.stableId != trackId) return@map track
            track.copy(
                locator =
                    resolvedSource?.let { source ->
                        track.locator.copy(
                            uriValue = source.uri.toString(),
                            displayName = source.displayName,
                            mimeType = source.mimeType,
                            cachedFileName = source.cachedFileName,
                        )
                    } ?: track.locator,
                readiness = readiness,
                failureMessage =
                    failureMessage?.takeIf {
                        readiness in
                            setOf(
                                TimedTextReadiness.Unreadable,
                                TimedTextReadiness.ParseFailed,
                            )
                    },
            )
        }
    var selection = activeText
    if (
        selection.primaryTrackId == trackId &&
            selection.primaryMode == TextSelectionMode.Automatic &&
            readiness in setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed)
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

internal fun upsertPlayableContentUnits(
    existing: List<PlayableContentUnit>,
    mediaSnapshots: List<MediaSourceRef>,
    availability: ContentAvailability?,
): List<PlayableContentUnit> {
    val units = existing.toMutableList()
    mediaSnapshots
        .distinctBy { stableMediaLocatorId(it.uri.toString(), it.sourceId) }
        .forEach { media ->
            val locatorId = stableMediaLocatorId(media.uri.toString(), media.sourceId)
            val identityKey = mediaIdentityKey(media.uri.toString())
            val priorIndex =
                units.indexOfFirst { unit ->
                    unit.mediaLocators.any { locator ->
                        locator.stableId == locatorId || locator.identityKey == identityKey
                    }
                }
            if (priorIndex < 0) {
                val locator =
                    media.toMediaLocator(
                        availability = availability ?: ContentAvailability.Unchecked,
                    )
                units +=
                    PlayableContentUnit(
                        stableId = stableContentId(identityKey),
                        mediaLocators = listOf(locator),
                        activeMediaLocatorId = locator.stableId,
                    )
            } else {
                val prior = units[priorIndex]
                val priorLocator = prior.mediaLocators.firstOrNull { it.stableId == locatorId }
                units[priorIndex] =
                    prior.withMediaLocator(
                        media.toMediaLocator(
                            availability =
                                availability
                                    ?: priorLocator?.availability
                                    ?: ContentAvailability.Unchecked,
                        ),
                        makeActive = true,
                    )
            }
        }
    return units.distinctBy(PlayableContentUnit::stableId)
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

internal fun LibraryConfig.withMediaAvailability(
    media: MediaSourceRef,
    availability: ContentAvailability,
): LibraryConfig {
    val unit = contentUnitFor(media)
        ?: return withMediaSnapshots(listOf(media), availability)
    return copy(
        contentUnits =
            contentUnits.map { current ->
                if (current.stableId == unit.stableId) {
                    current.withMediaAvailability(media, availability)
                } else {
                    current
                }
            },
    )
}

internal fun LibraryConfig.relinkMedia(
    previous: MediaSourceRef,
    replacement: MediaSourceRef,
): LibraryConfig {
    if (
        stableMediaLocatorId(previous.uri.toString(), previous.sourceId) ==
            stableMediaLocatorId(replacement.uri.toString(), replacement.sourceId)
    ) {
        return withMediaSnapshots(listOf(replacement), ContentAvailability.Available)
    }
    val previousUriValue = previous.uri.toString()
    val replacementUriValue = replacement.uri.toString()
    val previousUnit = contentUnitFor(previous)
    val replacementUnit = contentUnitFor(replacement)
    val relocatedUnit =
        when {
            previousUnit != null -> {
                var relocated =
                    previousUnit.withMediaAvailability(
                        previous,
                        ContentAvailability.Unavailable,
                    )
                if (replacementUnit != null && replacementUnit.stableId != previousUnit.stableId) {
                    replacementUnit.mediaLocators.forEach { locator ->
                        relocated = relocated.withMediaLocator(locator, makeActive = false)
                    }
                    val replacementCatalog =
                        replacementUnit.textCatalogState().rekeyForContent(previousUnit.stableId)
                    relocated =
                        relocated.withTextCatalogState(
                            relocated.textCatalogState().mergeTracks(replacementCatalog.tracks),
                        )
                }
                relocated.withMediaLocator(
                    replacement.toMediaLocator(ContentAvailability.Available),
                    makeActive = true,
                )
            }
            replacementUnit != null ->
                replacementUnit.withMediaLocator(
                    replacement.toMediaLocator(ContentAvailability.Available),
                    makeActive = true,
                )
            else -> {
                val locator = replacement.toMediaLocator(ContentAvailability.Available)
                PlayableContentUnit(
                    stableId = stableContentId(locator.identityKey),
                    mediaLocators = listOf(locator),
                    activeMediaLocatorId = locator.stableId,
                )
            }
        }
    val wasIncluded = previousUriValue in includedUris
    val wasIgnored = previousUriValue in ignoredUris
    val updatedIncluded =
        includedUris
            .replaceUri(previousUriValue, replacementUriValue)
            .let { values ->
                if (wasIncluded) (values + replacementUriValue).distinct() else values
            }
    val updatedIgnored =
        ignoredUris
            .replaceUri(previousUriValue, replacementUriValue)
            .let { values ->
                when {
                    wasIncluded -> values.filterNot { it == replacementUriValue }
                    wasIgnored && replacementUriValue !in updatedIncluded ->
                        (values + replacementUriValue).distinct()
                    else -> values
                }
            }
    return copy(
        contentUnits =
            contentUnits.filterNot {
                it.stableId == previousUnit?.stableId ||
                    it.stableId == replacementUnit?.stableId
            } + relocatedUnit,
        catalogItems = catalogItems.replaceMedia(previous, replacement),
        recentItems = recentItems.replaceMedia(previous, replacement),
        playlists =
            playlists.map { playlist ->
                playlist.copy(items = playlist.items.replaceMedia(previous, replacement))
            },
        includedUris = updatedIncluded,
        ignoredUris = updatedIgnored,
    )
}

internal fun LibraryConfig.withTimedTextCandidates(
    candidates: List<TimedTextTrackCandidate>,
): LibraryConfig {
    if (candidates.isEmpty()) return this
    val candidatesByMedia = candidates.groupBy(TimedTextTrackCandidate::mediaUriValue)
    return copy(
        contentUnits =
            contentUnits.map { unit ->
                val tracks =
                    unit.mediaLocators
                        .flatMap { locator ->
                            candidatesByMedia[locator.media.uri.toString()].orEmpty()
                        }.map { candidate -> candidate.track.rekeyForContent(unit.stableId) }
                        .distinctBy(TimedTextTrack::stableId)
                if (tracks.isEmpty()) return@map unit
                unit.withTextCatalogState(unit.textCatalogState().mergeTracks(tracks))
            },
    )
}

internal fun LibraryConfig.withEmbeddedTextCandidates(
    media: MediaSourceRef,
    candidates: List<TimedTextTrackCandidate>,
): LibraryConfig {
    val withMedia = withMediaSnapshots(listOf(media), ContentAvailability.Available)
    val contentUnit = withMedia.contentUnitFor(media) ?: return withMedia
    val embeddedTracks =
        candidates
            .filter { it.mediaUriValue == media.uri.toString() }
            .map { candidate -> candidate.track.rekeyForContent(contentUnit.stableId) }
            .filter { it.origin == TimedTextOrigin.Embedded }
    return withMedia.copy(
        contentUnits =
            withMedia.contentUnits.map { unit ->
                if (unit.stableId != contentUnit.stableId) return@map unit
                unit.withTextCatalogState(
                    unit.textCatalogState().reconcileEmbeddedTracks(embeddedTracks),
                )
            },
    )
}

internal fun LibraryConfig.withManualTextSource(
    media: MediaSourceRef,
    slot: TextTrackSlot,
    subtitle: SubtitleSourceRef?,
): LibraryConfig {
    val withMedia = withMediaSnapshots(listOf(media), ContentAvailability.Available)
    val contentUnit = withMedia.contentUnitFor(media) ?: return withMedia
    val contentId = contentUnit.stableId
    return withMedia.copy(
        contentUnits =
            withMedia.contentUnits.map { unit ->
                if (unit.stableId != contentId) return@map unit
                val track = subtitle?.let { manualTimedTextTrack(contentId, it) }
                unit.withTextCatalogState(
                    unit.textCatalogState().selectManualTrack(slot, track),
                )
            },
    )
}

internal fun LibraryConfig.withSubtitleOffset(
    media: MediaSourceRef,
    offsetMs: Long,
): LibraryConfig {
    val unit = contentUnitFor(media) ?: return this
    val normalizedOffset = offsetMs.coerceIn(-60_000L, 60_000L)
    return copy(
        contentUnits =
            contentUnits.map { current ->
                if (current.stableId == unit.stableId) {
                    current.copy(subtitleOffsetMs = normalizedOffset)
                } else {
                    current
                }
            },
    )
}

internal fun LibraryConfig.withManualTextTrack(
    media: MediaSourceRef,
    slot: TextTrackSlot,
    trackId: String?,
): LibraryConfig {
    val unit = contentUnitFor(media) ?: return this
    val track = trackId?.let { id -> unit.timedTextTracks.firstOrNull { it.stableId == id } }
    if (trackId != null && track == null) return this
    return copy(
        contentUnits =
            contentUnits.map { current ->
                if (current.stableId != unit.stableId) return@map current
                current.withTextCatalogState(
                    current.textCatalogState().selectManualTrack(slot, track),
                )
            },
    )
}

internal fun LibraryConfig.withAutomaticTextTrack(
    media: MediaSourceRef,
    slot: TextTrackSlot,
): LibraryConfig {
    val unit = contentUnitFor(media) ?: return this
    return copy(
        contentUnits =
            contentUnits.map { current ->
                if (current.stableId != unit.stableId) return@map current
                current.withTextCatalogState(
                    current.textCatalogState().restoreAutomaticTrack(slot),
                )
            },
    )
}

internal fun LibraryConfig.withTextTrackReadiness(
    media: MediaSourceRef,
    trackId: String,
    readiness: TimedTextReadiness,
    failureMessage: String? = null,
    resolvedSource: SubtitleSourceRef? = null,
): LibraryConfig {
    val unit = contentUnitFor(media) ?: return this
    return copy(
        contentUnits =
            contentUnits.map { current ->
                if (current.stableId != unit.stableId) return@map current
                current.withTextCatalogState(
                    current.textCatalogState().withTrackReadiness(
                        trackId = trackId,
                        readiness = readiness,
                        failureMessage = failureMessage,
                        resolvedSource = resolvedSource,
                    ),
                )
            },
    )
}

internal fun LibraryConfig.withSourceScanResult(
    sourceId: String,
    seenUriValues: Set<String>,
    sourceAvailable: Boolean,
    retainedUriValues: Set<String>,
): LibraryConfig =
    copy(
        contentUnits =
            contentUnits.mapNotNull { unit ->
                val affectedLocators =
                    unit.mediaLocators.filter { it.media.sourceId == sourceId }
                if (affectedLocators.isEmpty()) return@mapNotNull unit
                val updated =
                    unit.withSourceLocatorAvailability(
                        sourceId = sourceId,
                        seenUriValues = seenUriValues,
                        sourceAvailable = sourceAvailable,
                    )
                val hasMissingLocator =
                    sourceAvailable &&
                        affectedLocators.any {
                            it.media.uri.toString() !in seenUriValues
                        }
                val isReferenced =
                    unit.mediaLocators.any { it.media.uri.toString() in retainedUriValues }
                val hasAvailableLocator =
                    updated.mediaLocators.any {
                        it.availability == ContentAvailability.Available
                    }
                updated.takeUnless {
                    hasMissingLocator &&
                        !shouldRetainContentAfterLocatorLoss(
                            hasRetainedReference = isReferenced,
                            hasAvailableLocator = hasAvailableLocator,
                        )
                }
            },
    )

internal fun LibraryConfig.withSourceRemoved(
    sourceId: String,
    retainedUriValues: Set<String>,
): LibraryConfig =
    copy(
        contentUnits =
            contentUnits.mapNotNull { unit ->
                if (unit.mediaLocators.none { it.media.sourceId == sourceId }) {
                    return@mapNotNull unit
                }
                val updated =
                    unit.withSourceLocatorAvailability(
                        sourceId = sourceId,
                        seenUriValues = emptySet(),
                        sourceAvailable = false,
                    )
                val isReferenced =
                    unit.mediaLocators.any { it.media.uri.toString() in retainedUriValues }
                val hasAvailableLocator =
                    updated.mediaLocators.any {
                        it.availability == ContentAvailability.Available
                    }
                updated.takeIf {
                    shouldRetainContentAfterLocatorLoss(
                        hasRetainedReference = isReferenced,
                        hasAvailableLocator = hasAvailableLocator,
                    )
                }
            },
    )

internal fun shouldRetainContentAfterLocatorLoss(
    hasRetainedReference: Boolean,
    hasAvailableLocator: Boolean,
): Boolean = hasRetainedReference || hasAvailableLocator

internal fun LibraryConfig.contentUnitFor(media: MediaSourceRef): PlayableContentUnit? =
    contentUnits.firstOrNull { unit ->
        val locatorId = stableMediaLocatorId(media.uri.toString(), media.sourceId)
        val identityKey = mediaIdentityKey(media.uri.toString())
        unit.mediaLocators.any { locator ->
            locator.stableId == locatorId || locator.identityKey == identityKey
        }
    }

internal fun LibraryConfig.resolveActiveMedia(media: MediaSourceRef): MediaSourceRef =
    contentUnitFor(media)?.media ?: media

internal fun LibraryConfig.activeTextTrack(
    media: MediaSourceRef,
    slot: TextTrackSlot,
): TimedTextTrack? {
    val unit = contentUnitFor(media) ?: return null
    val trackId =
        when (slot) {
            TextTrackSlot.Primary -> unit.activeText.primaryTrackId
            TextTrackSlot.Secondary -> unit.activeText.secondaryTrackId
        }
    return unit.timedTextTracks.firstOrNull { it.stableId == trackId }
}

internal fun LibraryConfig.hasTimedTextTracks(media: MediaSourceRef): Boolean =
    contentUnitFor(media)?.timedTextTracks?.isNotEmpty() == true

internal fun LibraryConfig.subtitleOffsetFor(media: MediaSourceRef): Long =
    contentUnitFor(media)?.subtitleOffsetMs ?: 0L

private fun PlayableContentUnit.textCatalogState(): TextTrackCatalogState =
    TextTrackCatalogState(timedTextTracks, activeText)

private fun PlayableContentUnit.withTextCatalogState(
    state: TextTrackCatalogState,
): PlayableContentUnit =
    copy(timedTextTracks = state.tracks, activeText = state.activeText)

private fun MediaSourceRef.toMediaLocator(
    availability: ContentAvailability,
): MediaLocator =
    MediaLocator(
        stableId = stableMediaLocatorId(uri.toString(), sourceId),
        identityKey = mediaIdentityKey(uri.toString()),
        media = this,
        availability = availability,
    )

private fun PlayableContentUnit.withMediaLocator(
    locator: MediaLocator,
    makeActive: Boolean,
): PlayableContentUnit {
    val updatedLocators =
        mediaLocators.filterNot { it.stableId == locator.stableId } + locator
    return withNormalizedMediaLocators(
        locators = updatedLocators,
        preferredActiveId = locator.stableId.takeIf { makeActive },
    )
}

private fun PlayableContentUnit.withMediaAvailability(
    media: MediaSourceRef,
    availability: ContentAvailability,
): PlayableContentUnit {
    val locatorId = stableMediaLocatorId(media.uri.toString(), media.sourceId)
    val exactLocator = mediaLocators.firstOrNull { it.stableId == locatorId }
    val targetLocatorIds =
        exactLocator?.let { setOf(it.stableId) }
            ?: mediaLocators
                .filter { it.media.uri == media.uri }
                .map(MediaLocator::stableId)
                .toSet()
    if (targetLocatorIds.isEmpty()) {
        return withMediaLocator(
            locator = media.toMediaLocator(availability),
            makeActive = availability == ContentAvailability.Available,
        )
    }
    return withNormalizedMediaLocators(
        locators =
            mediaLocators.map { locator ->
                if (locator.stableId in targetLocatorIds) {
                    locator.copy(
                        media = media.takeIf { locator.stableId == locatorId } ?: locator.media,
                        availability = availability,
                    )
                } else {
                    locator
                }
            },
    )
}

private fun PlayableContentUnit.withSourceLocatorAvailability(
    sourceId: String,
    seenUriValues: Set<String>,
    sourceAvailable: Boolean,
): PlayableContentUnit =
    withNormalizedMediaLocators(
        locators =
            mediaLocators.map { locator ->
                if (locator.media.sourceId != sourceId) return@map locator
                val availability =
                    if (sourceAvailable && locator.media.uri.toString() in seenUriValues) {
                        ContentAvailability.Available
                    } else {
                        ContentAvailability.Unavailable
                    }
                locator.copy(availability = availability)
            },
    )

private fun PlayableContentUnit.withNormalizedMediaLocators(
    locators: List<MediaLocator>,
    preferredActiveId: String? = null,
): PlayableContentUnit {
    val distinctLocators = locators.distinctBy(MediaLocator::stableId)
    val activeId =
        resolveActiveMediaLocatorId(
            locatorAvailability =
                distinctLocators.map { it.stableId to it.availability },
            currentActiveId = activeMediaLocatorId,
            preferredActiveId = preferredActiveId,
        )
    return copy(
        mediaLocators = distinctLocators,
        activeMediaLocatorId = activeId,
    )
}

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
    val remappedTracks =
        tracks.map { track -> track.rekeyForContent(contentId) }
    val remappedIds =
        tracks.map(TimedTextTrack::stableId).zip(remappedTracks.map(TimedTextTrack::stableId)).toMap()
    val remappedSelection =
        activeText.copy(
            primaryTrackId = activeText.primaryTrackId?.let(remappedIds::get),
            secondaryTrackId = activeText.secondaryTrackId?.let(remappedIds::get),
            rejectedTrackIds =
                activeText.rejectedTrackIds.map { remappedIds[it] ?: it }.distinct(),
        )
    return TextTrackCatalogState(
        tracks = sortTimedTextTracks(remappedTracks, remappedSelection),
        activeText = remappedSelection,
    )
}

private fun TimedTextTrack.rekeyForContent(contentId: String): TimedTextTrack =
    copy(
        stableId =
            stableTimedTextTrackId(
                contentId = contentId,
                origin = origin,
                locatorIdentity = locator.stableIdentity,
            ),
    )

private fun SubtitleSourceRef.toTimedTextLocator(): TimedTextLocator =
    TimedTextLocator(
        uriValue = uri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        cachedFileName = cachedFileName,
    )

internal fun TimedTextTrack.toSubtitleSourceRef(): SubtitleSourceRef? =
    locator.toSubtitleSourceRef()

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
            if (matchBasis == TimedTextMatchBasis.UserSelected) {
                matchBasis
            } else {
                incoming.matchBasis
            },
        readiness =
            if (incoming.readiness == TimedTextReadiness.Unknown) readiness else incoming.readiness,
        failureMessage =
            if (incoming.readiness == TimedTextReadiness.Unknown) {
                failureMessage
            } else {
                incoming.failureMessage
            },
    )

private fun chooseAutomaticPrimary(
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
): String? {
    val secondaryIsEmbedded =
        tracks.firstOrNull { it.stableId == selection.secondaryTrackId }?.origin ==
            TimedTextOrigin.Embedded
    val usable =
        tracks.filter {
            it.stableId !in selection.rejectedTrackIds &&
                it.stableId != selection.secondaryTrackId &&
                (!secondaryIsEmbedded || it.origin != TimedTextOrigin.Embedded) &&
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

private fun normalizeEmbeddedSelection(
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
    preferredSlot: TextTrackSlot,
): ActiveTextSelection {
    val tracksById = tracks.associateBy(TimedTextTrack::stableId)
    val primaryIsEmbedded =
        tracksById[selection.primaryTrackId]?.origin == TimedTextOrigin.Embedded
    val secondaryIsEmbedded =
        tracksById[selection.secondaryTrackId]?.origin == TimedTextOrigin.Embedded
    if (!primaryIsEmbedded || !secondaryIsEmbedded) return selection

    return when (preferredSlot) {
        TextTrackSlot.Primary -> selection.copy(secondaryTrackId = null)
        TextTrackSlot.Secondary -> {
            var normalized = selection.copy(primaryTrackId = null)
            if (normalized.primaryMode == TextSelectionMode.Automatic) {
                normalized =
                    normalized.copy(
                        primaryTrackId = chooseAutomaticPrimary(tracks, normalized),
                    )
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
        track.origin == TimedTextOrigin.Embedded && TimedTextRole.Default in track.roles -> 1
        track.matchBasis == TimedTextMatchBasis.ExactStem -> 2
        track.matchBasis == TimedTextMatchBasis.LanguageSuffix -> 3
        else -> 4
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

private fun List<MediaSourceRef>.replaceMedia(
    previous: MediaSourceRef,
    replacement: MediaSourceRef,
): List<MediaSourceRef> =
    map { source -> if (source.uri == previous.uri) replacement else source }
        .distinctBy { it.uri.toString() }

private fun List<String>.replaceUri(
    previousUriValue: String,
    replacementUriValue: String,
): List<String> =
    map { value -> if (value == previousUriValue) replacementUriValue else value }.distinct()
