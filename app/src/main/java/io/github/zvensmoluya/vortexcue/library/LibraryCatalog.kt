package io.github.zvensmoluya.vortexcue.library

import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.PlayableContentUnit
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.core.model.stableMediaLocatorId

internal data class LibraryPartition(
    val included: List<MediaSourceRef>,
    val candidates: List<MediaSourceRef>,
    val ignored: List<MediaSourceRef>,
)

internal enum class LibraryDecision {
    Included,
    Candidate,
    Ignored,
}

internal data class LibraryMembershipState(
    val recentUriValues: List<String> = emptyList(),
    val includedUriValues: List<String> = emptyList(),
    val ignoredUriValues: List<String> = emptyList(),
)

internal fun LibraryMembershipState.recordOpenedUri(
    uriValue: String,
    maxRecentItems: Int,
): LibraryMembershipState {
    require(uriValue.isNotBlank())
    require(maxRecentItems > 0)
    return copy(
        recentUriValues =
            (listOf(uriValue) + recentUriValues.filterNot { it == uriValue })
                .take(maxRecentItems),
    )
}

internal fun LibraryMembershipState.collectUri(uriValue: String): LibraryMembershipState {
    require(uriValue.isNotBlank())
    return copy(
        includedUriValues = (includedUriValues + uriValue).distinct(),
        ignoredUriValues = ignoredUriValues.filterNot { it == uriValue },
    )
}

internal fun LibraryMembershipState.uncollectUris(
    uriValues: Set<String>,
): LibraryMembershipState =
    copy(includedUriValues = includedUriValues.filterNot(uriValues::contains))

internal fun resolveCollectedUriValues(
    includedUriValues: List<String>,
    playlistUriValues: List<String>,
): Set<String> =
    (includedUriValues + playlistUriValues)
        .filter(String::isNotBlank)
        .toSet()

internal fun decideLibraryUri(
    uriValue: String,
    includedUris: Set<String>,
    ignoredUris: Set<String>,
): LibraryDecision =
    when (uriValue) {
        in includedUris -> LibraryDecision.Included
        in ignoredUris -> LibraryDecision.Ignored
        else -> LibraryDecision.Candidate
    }

internal fun partitionLibrary(
    discovered: List<MediaSourceRef>,
    config: LibraryConfig,
): LibraryPartition {
    val explicitUris =
        resolveCollectedUriValues(
            includedUriValues = config.includedUris,
            playlistUriValues =
                config.playlists.flatMap { playlist ->
                    playlist.items.map { it.uri.toString() }
                },
        )
    val ignoredUris = config.ignoredUris.toSet()
    val contentItems =
        discovered
            .groupBy { media ->
                config.contentUnitFor(media)?.stableId ?: "uri:${media.uri}"
            }.values
            .map { locators ->
                config.contentUnitFor(locators.first())?.media ?: locators.first()
            }
    return LibraryPartition(
        included =
            contentItems.filter {
                config.decideMedia(it, explicitUris, ignoredUris) == LibraryDecision.Included
            },
        candidates =
            contentItems.filter {
                config.decideMedia(it, explicitUris, ignoredUris) == LibraryDecision.Candidate
            },
        ignored =
            contentItems.filter {
                config.decideMedia(it, explicitUris, ignoredUris) == LibraryDecision.Ignored
            },
    )
}

internal fun LibraryConfig.isCollected(media: MediaSourceRef): Boolean {
    return decisionFor(media) == LibraryDecision.Included
}

internal fun LibraryConfig.decisionFor(media: MediaSourceRef): LibraryDecision {
    val explicitUris =
        resolveCollectedUriValues(
            includedUriValues = includedUris,
            playlistUriValues = playlists.flatMap { it.items }.map { it.uri.toString() },
        )
    return decideMedia(media, explicitUris, ignoredUris.toSet())
}

internal fun LibraryConfig.retainedContentUriValues(): Set<String> =
    resolveCollectedUriValues(
        includedUriValues = recentItems.map { it.uri.toString() } + includedUris,
        playlistUriValues = playlists.flatMap { it.items }.map { it.uri.toString() },
    )

internal fun LibraryConfig.retainedMediaSnapshots(): List<MediaSourceRef> {
    val retainedUris = retainedContentUriValues()
    val locatorSnapshots =
        contentUnits.flatMap { unit ->
            unit.mediaLocators
                .filter { locator -> locator.media.uri.toString() in retainedUris }
                .map { locator -> locator.media }
        }
    return (recentItems + playlists.flatMap(LibraryPlaylist::items) + locatorSnapshots)
        .distinctBy { it.uri.toString() }
}

internal fun LibraryConfig.cachedSourceItems(sourceId: String): List<MediaSourceRef> =
    (catalogItems + recentItems + retainedMediaSnapshots())
        .filter { it.sourceId == sourceId }
        .distinctBy { stableMediaLocatorId(it.uri.toString(), it.sourceId) }
        .sortedBy { it.displayTitle.lowercase() }

internal fun LibraryConfig.relatedLocatorUriValues(uriValues: Set<String>): Set<String> {
    val relatedContentIds =
        contentUnits
            .filter { unit ->
                unit.mediaLocators.any { it.media.uri.toString() in uriValues }
            }.map(PlayableContentUnit::stableId)
            .toSet()
    return uriValues +
        contentUnits
            .filter { it.stableId in relatedContentIds }
            .flatMap { unit -> unit.mediaLocators.map { it.media.uri.toString() } }
}

internal fun LibraryConfig.sameContent(
    first: MediaSourceRef,
    second: MediaSourceRef,
): Boolean {
    if (first.uri == second.uri) return true
    val firstUnit = contentUnitFor(first)
    val secondUnit = contentUnitFor(second)
    return firstUnit != null && firstUnit.stableId == secondUnit?.stableId
}

internal fun restoreCachedLibraryState(
    config: LibraryConfig,
    hasAudioAccess: Boolean,
    videoAccess: VideoLibraryAccess,
): MediaLibraryState {
    val cachedItems =
        mergeCatalogItems(
            preferred = config.catalogItems,
            fallback = config.retainedMediaSnapshots(),
        )
    val partition = partitionLibrary(cachedItems, config)
    val hasSystemAccess = hasAudioAccess || videoAccess != VideoLibraryAccess.None
    val systemItemCount = config.cachedSourceItems(LibrarySourceIds.SystemLibrary).size
    val sources =
        buildList {
            add(
                LibrarySourceState(
                    id = LibrarySourceIds.SystemLibrary,
                    kind = LibrarySourceKind.SystemLibrary,
                    displayName = "设备媒体库",
                    itemCount = systemItemCount,
                    hasAccess = hasSystemAccess,
                    isScanned = false,
                ),
            )
            config.folders.forEach { folder ->
                add(
                    LibrarySourceState(
                        id = folder.id,
                        kind = LibrarySourceKind.Folder,
                        displayName = folder.displayName,
                        itemCount = config.cachedSourceItems(folder.id).size,
                        isScanned = false,
                    ),
                )
            }
            val looseItemCount = config.cachedSourceItems(LibrarySourceIds.LooseFiles).size
            if (looseItemCount > 0) {
                add(
                    LibrarySourceState(
                        id = LibrarySourceIds.LooseFiles,
                        kind = LibrarySourceKind.LooseFiles,
                        displayName = "单独打开",
                        itemCount = looseItemCount,
                    ),
                )
            }
        }
    return MediaLibraryState(
        items = partition.included,
        candidates = partition.candidates,
        ignoredItems = partition.ignored,
        config = config,
        sources = sources,
        hasAudioAccess = hasAudioAccess,
        videoAccess = videoAccess,
    )
}

internal fun MediaLibraryState.allDiscoveredItems(): List<MediaSourceRef> =
    (items + candidates + ignoredItems).distinctBy { it.uri.toString() }

internal fun mergeCatalogItems(
    preferred: List<MediaSourceRef>,
    fallback: List<MediaSourceRef>,
): List<MediaSourceRef> =
    (preferred + fallback).distinctBy {
        stableMediaLocatorId(it.uri.toString(), it.sourceId)
    }

private fun LibraryConfig.decideMedia(
    media: MediaSourceRef,
    explicitUris: Set<String>,
    ignoredUris: Set<String>,
): LibraryDecision {
    val unit = contentUnitFor(media)
    val isIncluded =
        media.uri.toString() in explicitUris ||
            unit?.mediaLocators?.any { it.media.uri.toString() in explicitUris } == true
    val isIgnored =
        media.uri.toString() in ignoredUris ||
            unit?.mediaLocators?.any { it.media.uri.toString() in ignoredUris } == true
    return when {
        isIncluded -> LibraryDecision.Included
        isIgnored -> LibraryDecision.Ignored
        else -> LibraryDecision.Candidate
    }
}

internal fun List<LibraryPlaylist>.withoutItems(
    uriValues: Set<String>,
): List<LibraryPlaylist> =
    map { playlist ->
        playlist.copy(items = playlist.items.filterNot { it.uri.toString() in uriValues })
    }
