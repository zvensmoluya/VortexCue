package io.github.zvensmoluya.vortexcue.library

import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef

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
    return LibraryPartition(
        included =
            discovered.filter {
                decideLibraryUri(it.uri.toString(), explicitUris, ignoredUris) ==
                    LibraryDecision.Included
            },
        candidates =
            discovered.filter {
                decideLibraryUri(it.uri.toString(), explicitUris, ignoredUris) ==
                    LibraryDecision.Candidate
            },
        ignored =
            discovered.filter {
                decideLibraryUri(it.uri.toString(), explicitUris, ignoredUris) ==
                    LibraryDecision.Ignored
            },
    )
}

internal fun LibraryConfig.isCollected(media: MediaSourceRef): Boolean =
    media.uri.toString() in
        resolveCollectedUriValues(
            includedUriValues = includedUris,
            playlistUriValues = playlists.flatMap { it.items }.map { it.uri.toString() },
        )

internal fun restoreCachedLibraryState(
    config: LibraryConfig,
    hasAudioAccess: Boolean,
    videoAccess: VideoLibraryAccess,
): MediaLibraryState {
    val cachedItems =
        mergeCatalogItems(
            preferred = config.catalogItems,
            fallback = config.playlists.flatMap(LibraryPlaylist::items),
        )
    val partition = partitionLibrary(cachedItems, config)
    val hasSystemAccess = hasAudioAccess || videoAccess != VideoLibraryAccess.None
    val systemItemCount = cachedItems.count { it.sourceId == LibrarySourceIds.SystemLibrary }
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
                        itemCount = cachedItems.count { it.sourceId == folder.id },
                        isScanned = false,
                    ),
                )
            }
            val looseItemCount = cachedItems.count { it.sourceId == LibrarySourceIds.LooseFiles }
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
    (preferred + fallback).distinctBy { it.uri.toString() }

internal fun List<LibraryPlaylist>.withoutItems(
    uriValues: Set<String>,
): List<LibraryPlaylist> =
    map { playlist ->
        playlist.copy(items = playlist.items.filterNot { it.uri.toString() in uriValues })
    }
