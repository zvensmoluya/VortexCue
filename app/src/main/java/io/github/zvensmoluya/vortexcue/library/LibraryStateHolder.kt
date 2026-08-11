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
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.core.model.stableMediaLocatorId
import io.github.zvensmoluya.vortexcue.core.storage.displayName
import io.github.zvensmoluya.vortexcue.core.storage.OrderedStateWriter
import io.github.zvensmoluya.vortexcue.core.storage.persistReadPermission
import io.github.zvensmoluya.vortexcue.core.storage.treeDisplayName
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
    private val configWriter = OrderedStateWriter(coroutineScope, store::save)
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
        startScan(config, LibraryScanScope.All)
    }

    private fun startScan(
        config: LibraryConfig,
        scope: LibraryScanScope,
    ) {
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
                if (scope.includeSystemLibrary && hasSystemAccess) {
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
                            itemCount =
                                config.catalogItems.count {
                                    it.sourceId == LibrarySourceIds.SystemLibrary
                                },
                            hasAccess = hasSystemAccess,
                            isScanned = false,
                        )
                }

                val subtitleSuggestions = mutableListOf<TimedTextTrackCandidate>()
                val folderScanResults =
                    mutableMapOf<String, Pair<Boolean, Set<String>>>()
                config.folders.forEach { folder ->
                    if (!scope.includesFolder(folder.id)) {
                        sources +=
                            LibrarySourceState(
                                id = folder.id,
                                kind = LibrarySourceKind.Folder,
                                displayName = folder.displayName,
                                itemCount = config.catalogItems.count { it.sourceId == folder.id },
                                isScanned = false,
                            )
                        return@forEach
                    }
                    val result = runCatching { scanner.scanFolder(folder) }
                    val batch = result.getOrDefault(LibraryScanBatch())
                    scanned += batch.media
                    subtitleSuggestions += batch.subtitleSuggestions
                    folderScanResults[folder.id] =
                        result.isSuccess to batch.media.map { it.uri.toString() }.toSet()
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
                        .distinctBy { stableMediaLocatorId(it.uri.toString(), it.sourceId) }
                        .sortedBy { it.displayTitle.lowercase() }
                val latestConfig = state.config
                val successfullyScannedFolderIds =
                    folderScanResults.filterValues { it.first }.keys
                val previouslySuggestedMediaUris =
                    latestConfig.catalogItems
                        .filter { it.sourceId in successfullyScannedFolderIds }
                        .map { it.uri.toString() }
                        .toSet()
                val retainedCatalogItems =
                    latestConfig.catalogItems.filterNot {
                        it.sourceId?.let(successfullyScannedFolderIds::contains) == true
                    }
                var updatedConfig =
                    latestConfig.copy(
                        catalogItems =
                            mergeCatalogItems(
                                preferred = freshItems,
                                fallback = retainedCatalogItems,
                            ),
                    ).withMediaSnapshots(freshItems, ContentAvailability.Available)
                        .withTimedTextCandidates(subtitleSuggestions)
                folderScanResults.forEach { (sourceId, scanResult) ->
                    updatedConfig =
                        updatedConfig.withSourceScanResult(
                            sourceId = sourceId,
                            seenUriValues = scanResult.second,
                            sourceAvailable = scanResult.first,
                            retainedUriValues = updatedConfig.retainedContentUriValues(),
                        )
                }
                val catalogItems =
                    mergeCatalogItems(
                        preferred = updatedConfig.catalogItems,
                        fallback = updatedConfig.retainedMediaSnapshots(),
                    )
                val partition = partitionLibrary(catalogItems, updatedConfig)
                val updatedSuggestions =
                    (state.suggestedTextTracks.filterNot {
                        it.mediaUriValue in previouslySuggestedMediaUris
                    } + subtitleSuggestions)
                        .distinctBy { "${it.mediaUriValue}|${it.track.stableId}" }
                state =
                    state.copy(
                        items = partition.included,
                        candidates = partition.candidates,
                        ignoredItems = partition.ignored,
                        config = updatedConfig,
                        sources = sources,
                        suggestedTextTracks = updatedSuggestions,
                        isLoading = false,
                        hasAudioAccess = hasAudioAccess,
                        videoAccess = videoAccess,
                        errorMessage = sources.firstNotNullOfOrNull { it.errorMessage },
                    )
                configWriter.submit(updatedConfig)
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
        val withMedia =
            config.withMediaSnapshots(listOf(recentSource), ContentAvailability.Available)
        val contentId = withMedia.contentUnitFor(recentSource)?.stableId
        val updatedConfig =
            withMedia.copy(
                recentItems =
                    (listOf(recentSource) +
                        config.recentItems.filterNot { existing ->
                            existing.uri == recentSource.uri ||
                                (contentId != null &&
                                    withMedia.contentUnitFor(existing)?.stableId == contentId)
                        }).take(MaxRecentItems),
            )
        state = state.copy(config = updatedConfig)
        configWriter.submit(updatedConfig)
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
        val withMedia =
            config.withMediaSnapshots(listOf(collected), ContentAvailability.Available)
        val relatedUris = withMedia.relatedLocatorUriValues(setOf(uriValue))
        val membership =
            LibraryMembershipState(
                recentUriValues = config.recentItems.map { it.uri.toString() },
                includedUriValues = config.includedUris.filterNot(relatedUris::contains),
                ignoredUriValues = config.ignoredUris.filterNot(relatedUris::contains),
            ).collectUri(uriValue)
        updateConfig(
            withMedia.copy(
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
            ),
            repartition = true,
        )
    }

    fun uncollectMedia(source: MediaSourceRef) {
        removeIncluded(setOf(source.uri.toString()))
    }

    fun addFolder(uri: Uri) {
        contentResolver.persistReadPermission(uri)
        val folder = LibraryFolderRef(uri, contentResolver.treeDisplayName(uri))
        val config = state.config
        val updatedConfig =
            config.copy(folders = listOf(folder) + config.folders.filterNot { it.uri == uri })
        updateConfig(updatedConfig)
        startScan(updatedConfig, LibraryScanScope.folder(folder.id))
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
                .distinctBy { stableMediaLocatorId(it.uri.toString(), it.sourceId) }
                .filter { it.sourceId == folderId }
        val removedUris = removedItems.map { it.uri.toString() }.toSet()
        val retainedUris = config.retainedContentUriValues()
        val updatedConfig =
            config.copy(
                folders = config.folders.filterNot { it.id == folderId },
                catalogItems = config.catalogItems.filterNot { it.sourceId == folderId },
                ignoredUris = config.ignoredUris.filterNot(removedUris::contains),
                homeShortcuts =
                    config.homeShortcuts.filterNot { shortcut ->
                        shortcut.type == LibraryShortcutType.Folder &&
                            shortcut.key.startsWith("$folderId|")
                    },
            ).withSourceRemoved(folderId, retainedUris)
        updateConfig(
            updatedConfig,
            repartition = true,
        )
        state =
            state.copy(
                sources = state.sources.filterNot { it.id == folderId },
                errorMessage = null,
            )
    }

    fun clearLooseFiles() {
        val config = state.config
        val looseUris =
            (config.catalogItems + config.recentItems)
                .filter { it.sourceId == LibrarySourceIds.LooseFiles }
                .map { it.uri.toString() }
                .toSet()
        val retainedUris = config.retainedContentUriValues()
        updateConfig(
            config.copy(
                catalogItems =
                    config.catalogItems.filterNot {
                        it.sourceId == LibrarySourceIds.LooseFiles
                    },
                ignoredUris = config.ignoredUris.filterNot(looseUris::contains),
                homeShortcuts =
                    config.homeShortcuts.filterNot { shortcut ->
                        shortcut.type == LibraryShortcutType.Folder &&
                            shortcut.key.startsWith("${LibrarySourceIds.LooseFiles}|")
                    },
            ).withSourceRemoved(LibrarySourceIds.LooseFiles, retainedUris),
            rescan = true,
        )
    }

    fun acceptCandidates(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        val relatedUris = config.relatedLocatorUriValues(uriValues)
        updateConfig(
            config.copy(
                includedUris =
                    (config.includedUris.filterNot(relatedUris::contains) + uriValues).distinct(),
                ignoredUris = config.ignoredUris.filterNot(relatedUris::contains),
            ),
            repartition = true,
        )
    }

    fun ignoreCandidates(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        val relatedUris = config.relatedLocatorUriValues(uriValues)
        updateConfig(
            config.copy(
                includedUris = config.includedUris.filterNot(relatedUris::contains),
                ignoredUris =
                    (config.ignoredUris.filterNot(relatedUris::contains) + uriValues).distinct(),
            ),
            repartition = true,
        )
    }

    fun removeIncluded(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        val relatedUris = config.relatedLocatorUriValues(uriValues)
        val membership =
            LibraryMembershipState(
                recentUriValues = config.recentItems.map { it.uri.toString() },
                includedUriValues = config.includedUris,
                ignoredUriValues = config.ignoredUris,
            ).uncollectUris(relatedUris)
        updateConfig(
            config.copy(
                includedUris = membership.includedUriValues,
                playlists = config.playlists.withoutItems(relatedUris),
            ),
            repartition = true,
        )
    }

    fun restoreIgnored(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        val relatedUris = config.relatedLocatorUriValues(uriValues)
        updateConfig(
            config.copy(ignoredUris = config.ignoredUris.filterNot(relatedUris::contains)),
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
                if (
                    playlist.id != playlistId ||
                        playlist.items.any { config.sameContent(it, item) || it.uri == item.uri }
                ) {
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

    fun updateManualTextSource(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        subtitle: SubtitleSourceRef?,
    ): TimedTextTrack? {
        val config = state.config
        val updatedConfig = config.withManualTextSource(media, slot, subtitle)
        updateConfig(updatedConfig)
        return updatedConfig.activeTextTrack(media, slot)
    }

    suspend fun persistManualTextSource(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        subtitle: SubtitleSourceRef?,
    ): TimedTextTrack? {
        val updatedConfig = state.config.withManualTextSource(media, slot, subtitle)
        updateConfig(updatedConfig, scheduleWrite = false)
        configWriter.submitAndAwait(updatedConfig)
        return updatedConfig.activeTextTrack(media, slot)
    }

    fun selectManualTextTrack(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        trackId: String?,
    ): TimedTextTrack? {
        val config = state.config
        val updatedConfig = config.withManualTextTrack(media, slot, trackId)
        if (updatedConfig === config) return null
        updateConfig(updatedConfig)
        return updatedConfig.activeTextTrack(media, slot)
    }

    fun restoreAutomaticTextTrack(
        media: MediaSourceRef,
        slot: TextTrackSlot,
    ): TimedTextTrack? {
        val config = state.config
        val updatedConfig = config.withAutomaticTextTrack(media, slot)
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
        updateConfig(
            updatedConfig,
        )
    }

    fun updateEmbeddedTextTracks(
        media: MediaSourceRef,
        candidates: List<TimedTextTrackCandidate>,
    ) {
        val config = state.config
        val updatedConfig = config.withEmbeddedTextCandidates(media, candidates)
        if (updatedConfig != config) updateConfig(updatedConfig)
    }

    fun updateMediaAvailability(
        media: MediaSourceRef,
        availability: ContentAvailability,
    ) {
        val config = state.config
        val updatedConfig = config.withMediaAvailability(media, availability)
        if (updatedConfig != config) updateConfig(updatedConfig)
    }

    fun updateSubtitleOffset(
        media: MediaSourceRef,
        offsetMs: Long,
    ) {
        val config = state.config
        val updatedConfig = config.withSubtitleOffset(media, offsetMs)
        if (updatedConfig != config) updateConfig(updatedConfig)
    }

    fun relinkMedia(
        previous: MediaSourceRef,
        replacement: MediaSourceRef,
    ) {
        val config = state.config
        val updatedConfig = config.relinkMedia(previous, replacement)
        if (updatedConfig != config) updateConfig(updatedConfig, repartition = true)
    }

    suspend fun awaitPendingWrites() {
        configWriter.awaitIdle()
    }

    private fun updateConfig(
        config: LibraryConfig,
        rescan: Boolean = false,
        repartition: Boolean = false,
        scheduleWrite: Boolean = true,
    ) {
        state =
            if (repartition) {
                val catalogItems =
                    mergeCatalogItems(
                        preferred = config.catalogItems,
                        fallback = config.retainedMediaSnapshots(),
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
        if (scheduleWrite) configWriter.submit(config)
        if (rescan) scan(config)
    }

    private companion object {
        const val MaxRecentItems = 50
    }
}
