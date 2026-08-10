package io.github.zvensmoluya.vortexcue.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.core.storage.displayName
import io.github.zvensmoluya.vortexcue.core.storage.persistReadPermission
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
internal class LibraryStateHolder(
    context: Context,
    private val coroutineScope: CoroutineScope,
    private val store: LibraryStore = LibraryStore(context),
    private val scanner: LocalMediaScanner = LocalMediaScanner(context),
    private val playlistIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private var scanJob: Job? = null
    private var scanRequestId = 0L

    var state by mutableStateOf(MediaLibraryState())
        private set

    suspend fun restoreConfig(): LibraryConfig {
        val config = runCatching { store.load() }.getOrDefault(LibraryConfig())
        state =
            restoreCachedLibraryState(
                config = config,
                hasAudioAccess = appContext.hasAudioLibraryPermission(),
                videoAccess = appContext.videoLibraryAccess(),
            )
        return config
    }

    suspend fun inspectMedia(source: MediaSourceRef): MediaSourceRef = scanner.inspect(source)

    fun cancelScan() {
        scanRequestId += 1L
        scanJob?.cancel()
        scanJob = null
        if (state.isLoading) state = state.copy(isLoading = false)
    }

    fun scan(config: LibraryConfig = state.config) {
        scanRequestId += 1L
        val requestId = scanRequestId
        scanJob?.cancel()
        val hasAudioAccess = appContext.hasAudioLibraryPermission()
        val videoAccess = appContext.videoLibraryAccess()
        val hasVideoAccess = videoAccess != VideoLibraryAccess.None
        val hasSystemAccess = hasAudioAccess || hasVideoAccess
        state =
            state.copy(
                config = config,
                isLoading = true,
                hasAudioAccess = hasAudioAccess,
                videoAccess = videoAccess,
                errorMessage = null,
            )
        scanJob =
            coroutineScope.launch {
                val scanned = mutableListOf<MediaSourceRef>()
                val sources = mutableListOf<LibrarySourceState>()
                if (hasSystemAccess) {
                    val audioResult =
                        if (hasAudioAccess) runCatching { scanner.scanSystemAudio() }
                        else Result.success(emptyList())
                    val videoResult =
                        if (hasVideoAccess) runCatching { scanner.scanSystemVideo() }
                        else Result.success(emptyList())
                    val audioItems = audioResult.getOrDefault(emptyList())
                    val videoItems = videoResult.getOrDefault(emptyList())
                    scanned += audioItems
                    scanned += videoItems
                    sources +=
                        LibrarySourceState(
                            id = LibrarySourceIds.SystemLibrary,
                            kind = LibrarySourceKind.SystemLibrary,
                            displayName = "设备媒体库",
                            itemCount = audioItems.size + videoItems.size,
                            errorMessage =
                                (audioResult.exceptionOrNull() ?: videoResult.exceptionOrNull())
                                    ?.let { "无法完整读取系统媒体目录" },
                        )
                } else {
                    sources +=
                        LibrarySourceState(
                            id = LibrarySourceIds.SystemLibrary,
                            kind = LibrarySourceKind.SystemLibrary,
                            displayName = "设备媒体库",
                            hasAccess = false,
                        )
                }

                val subtitleSuggestions = mutableListOf<TimedTextTrackCandidate>()
                config.folders.forEach { folder ->
                    val result = runCatching { scanner.scanFolder(folder) }
                    val batch = result.getOrDefault(LibraryScanBatch())
                    scanned += batch.media
                    subtitleSuggestions += batch.subtitleSuggestions
                    sources +=
                        LibrarySourceState(
                            id = folder.id,
                            kind = LibrarySourceKind.Folder,
                            displayName = folder.displayName,
                            itemCount = batch.media.size,
                            hasAccess = result.isSuccess,
                            errorMessage =
                                result.exceptionOrNull()?.let {
                                    "文件夹授权已失效或暂时不可用"
                                },
                        )
                }

                val looseItems =
                    config.catalogItems
                        .filter { it.sourceId == LibrarySourceIds.LooseFiles }
                        .map { item ->
                            item.copy(
                                origin = MediaOrigin.OpenDocument,
                                sourceId = LibrarySourceIds.LooseFiles,
                            )
                        }
                scanned += looseItems
                if (looseItems.isNotEmpty()) {
                    sources +=
                        LibrarySourceState(
                            id = LibrarySourceIds.LooseFiles,
                            kind = LibrarySourceKind.LooseFiles,
                            displayName = "单独打开",
                            itemCount = looseItems.size,
                        )
                }

                if (requestId != scanRequestId) return@launch
                val freshItems =
                    scanned
                        .distinctBy { it.uri.toString() }
                        .sortedBy { it.displayTitle.lowercase() }
                val latestConfig = state.config
                val updatedConfig =
                    latestConfig.copy(
                        catalogItems =
                            mergeCatalogItems(
                                preferred = freshItems,
                                fallback = latestConfig.catalogItems,
                            ),
                    ).withMediaSnapshots(freshItems, ContentAvailability.Available)
                        .withTimedTextCandidates(subtitleSuggestions)
                val catalogItems =
                    mergeCatalogItems(
                        preferred = updatedConfig.catalogItems,
                        fallback = updatedConfig.playlists.flatMap(LibraryPlaylist::items),
                    )
                val partition = partitionLibrary(catalogItems, updatedConfig)
                state =
                    state.copy(
                        items = partition.included,
                        candidates = partition.candidates,
                        ignoredItems = partition.ignored,
                        config = updatedConfig,
                        sources = sources,
                        suggestedTextTracks = subtitleSuggestions,
                        isLoading = false,
                        hasAudioAccess = hasAudioAccess,
                        videoAccess = videoAccess,
                        errorMessage = sources.firstNotNullOfOrNull { it.errorMessage },
                    )
                store.save(updatedConfig)
            }
    }

    fun rememberRecentMedia(source: MediaSourceRef) {
        val config = state.config
        val recentSource =
            if (source.origin == MediaOrigin.OpenDocument) {
                source.copy(sourceId = LibrarySourceIds.LooseFiles)
            } else {
                source
            }
        val membership =
            LibraryMembershipState(
                recentUriValues = config.recentItems.map { it.uri.toString() },
                includedUriValues = config.includedUris,
                ignoredUriValues = config.ignoredUris,
            ).recordOpenedUri(
                uriValue = recentSource.uri.toString(),
                maxRecentItems = MaxRecentItems,
            )
        val recentSourcesByUri =
            (config.recentItems + recentSource).associateBy { it.uri.toString() }
        val updatedConfig =
            config.copy(
                recentItems =
                    membership.recentUriValues.mapNotNull(recentSourcesByUri::get),
            ).withMediaSnapshots(listOf(recentSource), ContentAvailability.Available)
        state = state.copy(config = updatedConfig)
        coroutineScope.launch { store.save(updatedConfig) }
    }

    fun collectMedia(source: MediaSourceRef) {
        val collected =
            if (source.origin == MediaOrigin.OpenDocument) {
                source.copy(sourceId = LibrarySourceIds.LooseFiles)
            } else {
                source
            }
        val uriValue = collected.uri.toString()
        val config = state.config
        val membership =
            LibraryMembershipState(
                recentUriValues = config.recentItems.map { it.uri.toString() },
                includedUriValues = config.includedUris,
                ignoredUriValues = config.ignoredUris,
            ).collectUri(uriValue)
        updateConfig(
            config.copy(
                catalogItems =
                    mergeCatalogItems(
                        preferred = listOf(collected),
                        fallback = config.catalogItems,
                    ),
                includedUris = membership.includedUriValues,
                ignoredUris = membership.ignoredUriValues,
                homeShortcuts =
                    if (collected.sourceId == LibrarySourceIds.LooseFiles) {
                        (config.homeShortcuts + collected.toFolderShortcut()).distinct()
                    } else {
                        config.homeShortcuts
                    },
            ).withMediaSnapshots(listOf(collected), ContentAvailability.Available),
            repartition = true,
        )
    }

    fun uncollectMedia(source: MediaSourceRef) {
        removeIncluded(setOf(source.uri.toString()))
    }

    fun addFolder(uri: Uri) {
        contentResolver.persistReadPermission(uri)
        val folder = LibraryFolderRef(uri, contentResolver.displayName(uri))
        val config = state.config
        updateConfig(
            config.copy(folders = listOf(folder) + config.folders.filterNot { it.uri == uri }),
            rescan = true,
        )
    }

    fun removeFolder(folderId: String) {
        val config = state.config
        val folder = config.folders.firstOrNull { it.id == folderId } ?: return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                folder.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val removedItems =
            (config.catalogItems + state.allDiscoveredItems())
                .distinctBy { it.uri.toString() }
                .filter { it.sourceId == folderId }
        val removedUris = removedItems.map { it.uri.toString() }.toSet()
        updateConfig(
            config.copy(
                folders = config.folders.filterNot { it.id == folderId },
                catalogItems = config.catalogItems.filterNot { it.sourceId == folderId },
                recentItems = config.recentItems.filterNot { it.sourceId == folderId },
                includedUris = config.includedUris.filterNot(removedUris::contains),
                ignoredUris = config.ignoredUris.filterNot(removedUris::contains),
                homeShortcuts =
                    config.homeShortcuts.filterNot { shortcut ->
                        shortcut.type == LibraryShortcutType.Folder &&
                            shortcut.key.startsWith("$folderId|")
                    },
                subtitleAssociations =
                    config.subtitleAssociations.filterNot { it.mediaUri.toString() in removedUris },
                playlists = config.playlists.withoutItems(removedUris),
            ).withoutContentUris(removedUris),
            rescan = true,
        )
    }

    fun clearLooseFiles() {
        val config = state.config
        val looseUris =
            (config.catalogItems + config.recentItems)
                .filter { it.sourceId == LibrarySourceIds.LooseFiles }
                .map { it.uri.toString() }
                .toSet()
        updateConfig(
            config.copy(
                catalogItems =
                    config.catalogItems.filterNot {
                        it.sourceId == LibrarySourceIds.LooseFiles
                    },
                recentItems =
                    config.recentItems.filterNot {
                        it.sourceId == LibrarySourceIds.LooseFiles
                    },
                includedUris = config.includedUris.filterNot(looseUris::contains),
                ignoredUris = config.ignoredUris.filterNot(looseUris::contains),
                homeShortcuts =
                    config.homeShortcuts.filterNot { shortcut ->
                        shortcut.type == LibraryShortcutType.Folder &&
                            shortcut.key.startsWith("${LibrarySourceIds.LooseFiles}|")
                    },
                playlists = config.playlists.withoutItems(looseUris),
                subtitleAssociations =
                    config.subtitleAssociations.filterNot {
                        it.mediaUri.toString() in looseUris
                    },
            ).withoutContentUris(looseUris),
            rescan = true,
        )
    }

    fun acceptCandidates(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        updateConfig(
            config.copy(
                includedUris = (config.includedUris + uriValues).distinct(),
                ignoredUris = config.ignoredUris.filterNot(uriValues::contains),
            ),
            repartition = true,
        )
    }

    fun ignoreCandidates(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        updateConfig(
            config.copy(
                includedUris = config.includedUris.filterNot(uriValues::contains),
                ignoredUris = (config.ignoredUris + uriValues).distinct(),
            ),
            repartition = true,
        )
    }

    fun removeIncluded(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        val membership =
            LibraryMembershipState(
                recentUriValues = config.recentItems.map { it.uri.toString() },
                includedUriValues = config.includedUris,
                ignoredUriValues = config.ignoredUris,
            ).uncollectUris(uriValues)
        updateConfig(
            config.copy(
                includedUris = membership.includedUriValues,
                playlists = config.playlists.withoutItems(uriValues),
            ),
            repartition = true,
        )
    }

    fun restoreIgnored(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        updateConfig(
            config.copy(ignoredUris = config.ignoredUris.filterNot(uriValues::contains)),
            repartition = true,
        )
    }

    fun toggleHomeShortcut(shortcut: LibraryShortcut) {
        val config = state.config
        val shortcuts =
            if (shortcut in config.homeShortcuts) {
                config.homeShortcuts - shortcut
            } else {
                config.homeShortcuts + shortcut
            }
        updateConfig(config.copy(homeShortcuts = shortcuts))
    }

    fun createPlaylist(
        name: String,
        initialItem: MediaSourceRef?,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val config = state.config
        val playlist =
            LibraryPlaylist(
                id = playlistIdFactory(),
                name = trimmed,
                items = initialItem?.let(::listOf).orEmpty(),
            )
        val updatedConfig =
            config.copy(
                playlists = config.playlists + playlist,
                homeShortcuts =
                    config.homeShortcuts +
                        LibraryShortcut(LibraryShortcutType.Playlist, playlist.id),
            )
        updateConfig(
            initialItem?.let {
                updatedConfig.withMediaSnapshots(listOf(it), ContentAvailability.Available)
            } ?: updatedConfig,
        )
    }

    fun addToPlaylist(
        playlistId: String,
        item: MediaSourceRef,
    ) {
        val config = state.config
        val playlists =
            config.playlists.map { playlist ->
                if (playlist.id != playlistId || playlist.items.any { it.uri == item.uri }) {
                    playlist
                } else {
                    playlist.copy(items = playlist.items + item)
                }
            }
        updateConfig(
            config.copy(playlists = playlists)
                .withMediaSnapshots(listOf(item), ContentAvailability.Available),
        )
    }

    fun removeFromPlaylist(
        playlistId: String,
        item: MediaSourceRef,
    ) {
        val config = state.config
        val playlists =
            config.playlists.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(items = playlist.items.filterNot { it.uri == item.uri })
                } else {
                    playlist
                }
            }
        updateConfig(config.copy(playlists = playlists))
    }

    fun deletePlaylist(playlistId: String) {
        val config = state.config
        updateConfig(
            config.copy(
                playlists = config.playlists.filterNot { it.id == playlistId },
                homeShortcuts =
                    config.homeShortcuts.filterNot {
                        it.type == LibraryShortcutType.Playlist && it.key == playlistId
                    },
            ),
        )
    }

    fun updateSubtitleAssociation(
        media: MediaSourceRef,
        subtitle: SubtitleSourceRef?,
    ) {
        updateManualTextSource(media, TextTrackSlot.Primary, subtitle)
    }

    fun updateManualTextSource(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        subtitle: SubtitleSourceRef?,
    ): TimedTextTrack? {
        val config = state.config
        var updatedConfig = config.withManualTextSource(media, slot, subtitle)
        if (slot == TextTrackSlot.Primary) {
            updatedConfig =
                updatedConfig.copy(
                    subtitleAssociations =
                        config.subtitleAssociations.filterNot { it.mediaUri == media.uri } +
                            listOfNotNull(
                                subtitle?.let { SubtitleAssociation(media.uri, it) },
                            ),
                )
        }
        updateConfig(updatedConfig)
        return updatedConfig.activeTextTrack(media, slot)
    }

    fun selectManualTextTrack(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        trackId: String?,
    ): TimedTextTrack? {
        val config = state.config
        var updatedConfig = config.withManualTextTrack(media, slot, trackId)
        if (updatedConfig === config) return null
        if (slot == TextTrackSlot.Primary) {
            val source = updatedConfig.activeTextTrack(media, slot)?.toSubtitleSourceRef()
            updatedConfig =
                updatedConfig.copy(
                    subtitleAssociations =
                        config.subtitleAssociations.filterNot { it.mediaUri == media.uri } +
                            listOfNotNull(source?.let { SubtitleAssociation(media.uri, it) }),
                )
        }
        updateConfig(updatedConfig)
        return updatedConfig.activeTextTrack(media, slot)
    }

    fun restoreAutomaticTextTrack(
        media: MediaSourceRef,
        slot: TextTrackSlot,
    ): TimedTextTrack? {
        val config = state.config
        var updatedConfig = config.withAutomaticTextTrack(media, slot)
        if (slot == TextTrackSlot.Primary) {
            updatedConfig =
                updatedConfig.copy(
                    subtitleAssociations =
                        config.subtitleAssociations.filterNot { it.mediaUri == media.uri },
                )
        }
        updateConfig(updatedConfig)
        return updatedConfig.activeTextTrack(media, slot)
    }

    fun updateTextTrackReadiness(
        media: MediaSourceRef,
        trackId: String,
        readiness: TimedTextReadiness,
        failureMessage: String? = null,
        resolvedSource: SubtitleSourceRef? = null,
    ) {
        val config = state.config
        var updatedConfig =
            config.withTextTrackReadiness(
                media = media,
                trackId = trackId,
                readiness = readiness,
                failureMessage = failureMessage,
                resolvedSource = resolvedSource,
            )
        val selectedPrimary = updatedConfig.activeTextTrack(media, TextTrackSlot.Primary)
        if (
            resolvedSource != null &&
                selectedPrimary?.stableId == trackId &&
                updatedConfig.contentUnitFor(media)?.activeText?.primaryMode ==
                    TextSelectionMode.Manual
        ) {
            updatedConfig =
                updatedConfig.copy(
                    subtitleAssociations =
                        config.subtitleAssociations.filterNot { it.mediaUri == media.uri } +
                            SubtitleAssociation(media.uri, resolvedSource),
                )
        }
        updateConfig(
            updatedConfig,
        )
    }

    private fun updateConfig(
        config: LibraryConfig,
        rescan: Boolean = false,
        repartition: Boolean = false,
    ) {
        state =
            if (repartition) {
                val catalogItems =
                    mergeCatalogItems(
                        preferred = config.catalogItems,
                        fallback = config.playlists.flatMap(LibraryPlaylist::items),
                    )
                val partition = partitionLibrary(catalogItems, config)
                state.copy(
                    items = partition.included,
                    candidates = partition.candidates,
                    ignoredItems = partition.ignored,
                    config = config,
                )
            } else {
                state.copy(config = config)
            }
        coroutineScope.launch { store.save(config) }
        if (rescan) scan(config)
    }

    private companion object {
        const val MaxRecentItems = 50
    }
}
