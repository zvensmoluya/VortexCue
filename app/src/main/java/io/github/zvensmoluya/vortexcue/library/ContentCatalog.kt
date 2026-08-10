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

internal fun LibraryConfig.withMediaAvailability(
    media: MediaSourceRef,
    availability: ContentAvailability,
): LibraryConfig =
    withMediaSnapshots(listOf(media), availability)

internal fun LibraryConfig.relinkMedia(
    previous: MediaSourceRef,
    replacement: MediaSourceRef,
): LibraryConfig {
    if (previous.uri == replacement.uri) {
        return withMediaSnapshots(listOf(replacement), ContentAvailability.Available)
    }
    val previousUriValue = previous.uri.toString()
    val replacementUriValue = replacement.uri.toString()
    val previousUnit = contentUnitFor(previous)
    val replacementUnit = contentUnitFor(replacement)
    val relocatedUnit =
        when {
            previousUnit != null -> {
                val relocated = previousUnit.relinkTo(replacement)
                replacementUnit?.let { existing ->
                    relocated.withTextCatalogState(
                        relocated.textCatalogState().mergeTracks(existing.timedTextTracks),
                    )
                } ?: relocated
            }
            replacementUnit != null ->
                replacementUnit.copy(
                    media = replacement,
                    sourceId = replacement.sourceId,
                    availability = ContentAvailability.Available,
                )
            else ->
                PlayableContentUnit(
                    stableId = stableContentId(replacementUriValue),
                    media = replacement,
                    availability = ContentAvailability.Available,
                )
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
                it.media.uri == previous.uri || it.media.uri == replacement.uri
            } + relocatedUnit,
        catalogItems = catalogItems.replaceMedia(previous, replacement),
        recentItems = recentItems.replaceMedia(previous, replacement),
        playlists =
            playlists.map { playlist ->
                playlist.copy(items = playlist.items.replaceMedia(previous, replacement))
            },
        subtitleAssociations =
            subtitleAssociations
                .map { association ->
                    if (association.mediaUri == previous.uri) {
                        association.copy(mediaUri = replacement.uri)
                    } else {
                        association
                    }
                }.distinctBy { it.mediaUri.toString() to it.subtitle.uri.toString() },
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
                val tracks = candidatesByMedia[unit.media.uri.toString()]?.map { it.track }
                    ?: return@map unit
                unit.withTextCatalogState(unit.textCatalogState().mergeTracks(tracks))
            },
    )
}

internal fun LibraryConfig.withEmbeddedTextCandidates(
    media: MediaSourceRef,
    candidates: List<TimedTextTrackCandidate>,
): LibraryConfig {
    val withMedia = withMediaSnapshots(listOf(media), ContentAvailability.Available)
    val embeddedTracks =
        candidates
            .filter { it.mediaUriValue == media.uri.toString() }
            .map(TimedTextTrackCandidate::track)
            .filter { it.origin == TimedTextOrigin.Embedded }
    return withMedia.copy(
        contentUnits =
            withMedia.contentUnits.map { unit ->
                if (unit.media.uri != media.uri) return@map unit
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
    val contentId = stableContentId(media.uri.toString())
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

internal fun LibraryConfig.withoutContentUris(uriValues: Set<String>): LibraryConfig =
    copy(contentUnits = contentUnits.filterNot { it.media.uri.toString() in uriValues })

internal fun LibraryConfig.activePrimarySubtitleSource(
    media: MediaSourceRef,
): SubtitleSourceRef? {
    val unit = contentUnitFor(media)
    if (unit != null) {
        if (unit.activeText.primaryTrackId != null) {
            return activeTextTrack(media, TextTrackSlot.Primary)?.toSubtitleSourceRef()
        }
        if (unit.activeText.primaryMode == TextSelectionMode.Manual) return null
    }
    return subtitleAssociations.firstOrNull { it.mediaUri == media.uri }?.subtitle
}

internal fun LibraryConfig.contentUnitFor(media: MediaSourceRef): PlayableContentUnit? =
    contentUnits.firstOrNull { it.media.uri == media.uri }

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

private fun PlayableContentUnit.relinkTo(replacement: MediaSourceRef): PlayableContentUnit {
    val replacementContentId = stableContentId(replacement.uri.toString())
    val remappedCatalog = textCatalogState().rekeyForContent(replacementContentId)
    return copy(
        stableId = replacementContentId,
        media = replacement,
        sourceId = replacement.sourceId,
        timedTextTracks = remappedCatalog.tracks,
        activeText = remappedCatalog.activeText,
        availability = ContentAvailability.Available,
    )
}

internal fun TextTrackCatalogState.rekeyForContent(contentId: String): TextTrackCatalogState {
    val remappedTracks =
        tracks.map { track ->
            track.copy(
                stableId =
                    stableTimedTextTrackId(
                        contentId = contentId,
                        origin = track.origin,
                        locatorIdentity = track.locator.stableIdentity,
                    ),
            )
        }
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
            if (matchBasis in setOf(TimedTextMatchBasis.UserSelected, TimedTextMatchBasis.Legacy)) {
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
