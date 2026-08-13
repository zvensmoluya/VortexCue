package io.github.zvensmoluya.vortexcue.library

import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.PlayableContentUnit
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.core.model.mediaIdentityKey
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
    val lookup = ContentUnitLookup(config.contentUnits)
    return partitionLibrary(discovered, config, lookup)
}

/**
 * Partitions a cached snapshot without repeatedly walking every content unit.
 *
 * A cold start can contain hundreds of cached media items.  Calling
 * [LibraryConfig.contentUnitFor] once for every item (and again for every decision)
 * turns restoration into an accidental O(items × contentUnits × URI parsing) loop.
 * The lookup is deliberately local to the restore operation so the persisted model
 * stays unchanged.
 */
private fun partitionLibrary(
    discovered: List<MediaSourceRef>,
    config: LibraryConfig,
    lookup: ContentUnitLookup,
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
                lookup.find(media)?.stableId ?: "uri:${media.uri}"
            }.values
            .map { locators ->
                lookup.find(locators.first())?.media ?: locators.first()
            }
    val decisions =
        contentItems.map { media ->
            config.decideMedia(
                media = media,
                explicitUris = explicitUris,
                ignoredUris = ignoredUris,
                unit = lookup.find(media),
            )
        }
    return LibraryPartition(
        included = contentItems.filterIndexed { index, _ -> decisions[index] == LibraryDecision.Included },
        candidates = contentItems.filterIndexed { index, _ -> decisions[index] == LibraryDecision.Candidate },
        ignored = contentItems.filterIndexed { index, _ -> decisions[index] == LibraryDecision.Ignored },
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
    val sourceItemCounts = cachedItems.groupingBy { it.sourceId }.eachCount()
    val hasSystemAccess = hasAudioAccess || videoAccess != VideoLibraryAccess.None
    val systemItemCount = sourceItemCounts[LibrarySourceIds.SystemLibrary] ?: 0
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
                        itemCount = sourceItemCounts[folder.id] ?: 0,
                        isScanned = false,
                    ),
                )
            }
            val looseItemCount = sourceItemCounts[LibrarySourceIds.LooseFiles] ?: 0
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
    unit: PlayableContentUnit? = contentUnitFor(media),
): LibraryDecision {
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

private class ContentUnitLookup(units: List<PlayableContentUnit>) {
    private data class IndexedUnit(
        val index: Int,
        val unit: PlayableContentUnit,
    )

    private val byLocatorId = HashMap<String, IndexedUnit>(units.size)
    private val byIdentityKey = HashMap<String, IndexedUnit>(units.size)

    init {
        // Keep the first match to preserve contentUnitFor's existing list-order semantics
        // if a malformed/legacy snapshot contains duplicate identities.
        units.forEachIndexed { index, unit ->
            val indexed = IndexedUnit(index, unit)
            unit.mediaLocators.forEach { locator ->
                byLocatorId.putIfAbsent(locator.stableId, indexed)
                byIdentityKey.putIfAbsent(locator.identityKey, indexed)
            }
        }
    }

    fun find(media: MediaSourceRef): PlayableContentUnit? {
        val locatorId = stableMediaLocatorId(media.uri.toString(), media.sourceId)
        val byLocator = byLocatorId[locatorId]
        val byIdentity = byIdentityKey[mediaIdentityKey(media.uri.toString())]
        return when {
            byLocator == null -> byIdentity?.unit
            byIdentity == null -> byLocator.unit
            byLocator.index <= byIdentity.index -> byLocator.unit
            else -> byIdentity.unit
        }
    }
}

internal fun List<LibraryPlaylist>.withoutItems(
    uriValues: Set<String>,
): List<LibraryPlaylist> =
    map { playlist ->
        playlist.copy(items = playlist.items.filterNot { it.uri.toString() in uriValues })
    }
