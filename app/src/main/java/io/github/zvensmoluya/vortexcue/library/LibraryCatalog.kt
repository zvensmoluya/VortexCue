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
        (
            config.includedUris +
                config.manualItems.map { it.uri.toString() } +
                config.playlists.flatMap { playlist ->
                    playlist.items.map { it.uri.toString() }
                }
        ).toSet()
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

internal fun restoreCachedLibraryState(
    config: LibraryConfig,
    hasAudioAccess: Boolean,
    videoAccess: VideoLibraryAccess,
): MediaLibraryState {
    val cachedItems =
        (config.manualItems + config.playlists.flatMap(LibraryPlaylist::items))
            .distinctBy { it.uri.toString() }
    val partition = partitionLibrary(cachedItems, config)
    val hasSystemAccess = hasAudioAccess || videoAccess != VideoLibraryAccess.None
    val sources =
        buildList {
            add(
                LibrarySourceState(
                    id = LibrarySourceIds.SystemLibrary,
                    kind = LibrarySourceKind.SystemLibrary,
                    displayName = "设备媒体库",
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
                        isScanned = false,
                    ),
                )
            }
            if (config.manualItems.isNotEmpty()) {
                add(
                    LibrarySourceState(
                        id = LibrarySourceIds.LooseFiles,
                        kind = LibrarySourceKind.LooseFiles,
                        displayName = "单独打开",
                        itemCount = config.manualItems.size,
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

internal fun List<LibraryPlaylist>.withoutItems(
    uriValues: Set<String>,
): List<LibraryPlaylist> =
    map { playlist ->
        playlist.copy(items = playlist.items.filterNot { it.uri.toString() in uriValues })
    }
