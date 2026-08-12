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
import kotlinx.coroutines.CancellationException
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
    private val scanJobs = mutableMapOf<String, Job>()
    private val scanRequestIds = mutableMapOf<String, Long>()
    private var nextScanRequestId = 0L

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

    fun cancelScan(sourceId: String? = null) {
        val sourceIds = sourceId?.let(::setOf) ?: scanJobs.keys.toSet()
        if (sourceIds.isEmpty()) return
        sourceIds.forEach { id ->
            scanRequestIds.remove(id)
            scanJobs.remove(id)?.cancel()
        }
        val remainingSourceIds = state.scanningSourceIds - sourceIds
        state =
            state.copy(
                isLoading = remainingSourceIds.isNotEmpty(),
                scanningSourceIds = remainingSourceIds,
            )
    }

    fun scan(config: LibraryConfig = state.config) {
        startScan(config, LibraryScanScope.All)
    }

    fun refreshSource(sourceId: String) {
        when {
            sourceId == LibrarySourceIds.SystemLibrary ->
                startScan(state.config, LibraryScanScope.SystemLibrary)
            state.config.folders.any { it.id == sourceId } ->
                startScan(state.config, LibraryScanScope.folder(sourceId))
        }
    }

    private fun startScan(
        config: LibraryConfig,
        scope: LibraryScanScope,
    ) {
        val hasAudioAccess = appContext.hasAudioLibraryPermission()
        val videoAccess = appContext.videoLibraryAccess()
        val hasVideoAccess = videoAccess != VideoLibraryAccess.None
        val hasSystemAccess = hasAudioAccess || hasVideoAccess
        val currentSystemSource =
            state.sources.firstOrNull { it.id == LibrarySourceIds.SystemLibrary }
                ?: LibrarySourceState(
                    id = LibrarySourceIds.SystemLibrary,
                    kind = LibrarySourceKind.SystemLibrary,
                    displayName = "设备媒体库",
                    itemCount = config.cachedSourceItems(LibrarySourceIds.SystemLibrary).size,
                    isScanned = false,
                )
        state =
            state.copy(
                config = config,
                hasAudioAccess = hasAudioAccess,
                videoAccess = videoAccess,
                errorMessage = null,
                sources =
                    state.sources.withSourceState(
                        currentSystemSource.copy(hasAccess = hasSystemAccess),
                    ),
            )
        if (scope.includeSystemLibrary) {
            if (hasSystemAccess) {
                startSystemScan(hasAudioAccess, videoAccess)
            } else {
                cancelScan(LibrarySourceIds.SystemLibrary)
            }
        }
        config.folders.filter { scope.includesFolder(it.id) }.forEach(::startFolderScan)
    }

    private fun startSystemScan(
        hasAudioAccess: Boolean,
        videoAccess: VideoLibraryAccess,
    ) {
        val hasVideoAccess = videoAccess != VideoLibraryAccess.None
        launchSourceScan(
            sourceId = LibrarySourceIds.SystemLibrary,
            initialSource =
                state.sources.firstOrNull { it.id == LibrarySourceIds.SystemLibrary }
                    ?: LibrarySourceState(
                        id = LibrarySourceIds.SystemLibrary,
                        kind = LibrarySourceKind.SystemLibrary,
                        displayName = "设备媒体库",
                    ),
        ) {
            val audioResult =
                if (hasAudioAccess) scanSafely { scanner.scanSystemAudio() }
                else Result.success(emptyList())
            val videoResult =
                if (hasVideoAccess) scanSafely { scanner.scanSystemVideo() }
                else Result.success(emptyList())
            val media = audioResult.getOrDefault(emptyList()) + videoResult.getOrDefault(emptyList())
            val authoritative =
                isAuthoritativeSystemScan(
                    hasAudioAccess = hasAudioAccess,
                    videoAccess = videoAccess,
                    audioScanSucceeded = audioResult.isSuccess,
                    videoScanSucceeded = videoResult.isSuccess,
                )
            SourceScanResult(
                source =
                    LibrarySourceState(
                        id = LibrarySourceIds.SystemLibrary,
                        kind = LibrarySourceKind.SystemLibrary,
                        displayName = "设备媒体库",
                        itemCount = media.size,
                        errorMessage =
                            (audioResult.exceptionOrNull() ?: videoResult.exceptionOrNull())
                                ?.let { "无法完整读取系统媒体目录" },
                    ),
                media = media,
                replaceCachedItems = authoritative,
                locatorSourceAvailable = true,
            )
        }
    }

    private fun startFolderScan(folder: LibraryFolderRef) {
        launchSourceScan(
            sourceId = folder.id,
            initialSource =
                state.sources.firstOrNull { it.id == folder.id }
                    ?: LibrarySourceState(
                        id = folder.id,
                        kind = LibrarySourceKind.Folder,
                        displayName = folder.displayName,
                        itemCount = state.config.cachedSourceItems(folder.id).size,
                        isScanned = false,
                    ),
        ) {
            val result = scanSafely { scanner.scanFolder(folder) }
            val batch = result.getOrDefault(LibraryScanBatch())
            SourceScanResult(
                source =
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
                    ),
                media = batch.media,
                subtitleSuggestions = batch.subtitleSuggestions,
                replaceCachedItems = result.isSuccess,
                locatorSourceAvailable = result.isSuccess,
            )
        }
    }

    private fun launchSourceScan(
        sourceId: String,
        initialSource: LibrarySourceState,
        scan: suspend () -> SourceScanResult,
    ) {
        cancelScan(sourceId)
        val requestId = ++nextScanRequestId
        scanRequestIds[sourceId] = requestId
        state =
            state.copy(
                isLoading = true,
                scanningSourceIds = state.scanningSourceIds + sourceId,
                sources = state.sources.withSourceState(initialSource),
            )
        scanJobs[sourceId] =
            coroutineScope.launch {
                val result = scan()
                if (scanRequestIds[sourceId] != requestId) return@launch
                applySourceScanResult(result)
                if (scanRequestIds[sourceId] != requestId) return@launch
                scanRequestIds.remove(sourceId)
                scanJobs.remove(sourceId)
                val remainingSourceIds = state.scanningSourceIds - sourceId
                state =
                    state.copy(
                        isLoading = remainingSourceIds.isNotEmpty(),
                        scanningSourceIds = remainingSourceIds,
                    )
            }
    }

    private fun applySourceScanResult(result: SourceScanResult) {
        val sourceId = result.source.id
        val latestConfig = state.config
        val previousSourceUris =
            latestConfig.catalogItems
                .filter { it.sourceId == sourceId }
                .map { it.uri.toString() }
                .toSet()
        val retainedCatalogItems =
            if (result.replaceCachedItems) {
                latestConfig.catalogItems.filterNot { it.sourceId == sourceId }
            } else {
                latestConfig.catalogItems
            }
        val freshItems =
            result.media
                .distinctBy { stableMediaLocatorId(it.uri.toString(), it.sourceId) }
                .sortedBy { it.displayTitle.lowercase() }
        var updatedConfig =
            latestConfig.copy(
                catalogItems =
                    mergeCatalogItems(
                        preferred = freshItems,
                        fallback = retainedCatalogItems,
                    ),
            ).withMediaSnapshots(freshItems, ContentAvailability.Available)
                .withTimedTextCandidates(result.subtitleSuggestions)
        val cachedSourceUris =
            if (result.replaceCachedItems) {
                emptySet()
            } else {
                updatedConfig.cachedSourceItems(sourceId)
                    .mapTo(linkedSetOf()) { it.uri.toString() }
            }
        updatedConfig =
            updatedConfig.withSourceScanResult(
                sourceId = sourceId,
                seenUriValues = freshItems.map { it.uri.toString() }.toSet(),
                sourceAvailable = result.locatorSourceAvailable,
                retainedUriValues = updatedConfig.retainedContentUriValues() + cachedSourceUris,
            )
        val catalogItems =
            mergeCatalogItems(
                preferred = updatedConfig.catalogItems,
                fallback = updatedConfig.retainedMediaSnapshots(),
            )
        val partition = partitionLibrary(catalogItems, updatedConfig)
        val updatedSource =
            result.source.copy(itemCount = updatedConfig.cachedSourceItems(sourceId).size)
        val updatedSources = state.sources.withSourceState(updatedSource)
        val retainedSuggestions =
            if (result.replaceCachedItems) {
                state.suggestedTextTracks.filterNot {
                    it.mediaUriValue in previousSourceUris
                }
            } else {
                state.suggestedTextTracks
            }
        state =
            state.copy(
                items = partition.included,
                candidates = partition.candidates,
                ignoredItems = partition.ignored,
                config = updatedConfig,
                sources = updatedSources.synchronizedWith(updatedConfig),
                suggestedTextTracks =
                    (retainedSuggestions + result.subtitleSuggestions)
                        .distinctBy { "${it.mediaUriValue}|${it.track.stableId}" },
                errorMessage = updatedSources.firstNotNullOfOrNull { it.errorMessage },
            )
        configWriter.submit(updatedConfig)
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
        updateConfig(updatedConfig)
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
        val existing = config.folders.firstOrNull { it.uri == uri }
        if (existing != null) {
            refreshSource(existing.id)
            return
        }
        val updatedConfig =
            config.copy(folders = listOf(folder) + config.folders)
        updateConfig(updatedConfig)
        startScan(updatedConfig, LibraryScanScope.folder(folder.id))
    }

    fun replaceFolder(
        folderId: String,
        uri: Uri,
    ) {
        val previous = state.config.folders.firstOrNull { it.id == folderId }
        if (previous == null) {
            addFolder(uri)
            return
        }
        contentResolver.persistReadPermission(uri)
        val duplicate =
            state.config.folders.firstOrNull { it.id != folderId && it.uri == uri }
        if (duplicate != null) {
            removeFolder(folderId)
            refreshSource(duplicate.id)
            return
        }
        val replacement =
            LibraryFolderRef(
                uri = uri,
                displayName = contentResolver.treeDisplayName(uri),
                id = previous.id,
            )
        if (previous.uri != uri) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    previous.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        val config = state.config
        val updatedConfig =
            config.copy(
                folders =
                    config.folders.map { folder ->
                        if (folder.id == folderId) replacement else folder
                    },
            )
        updateConfig(updatedConfig)
        startFolderScan(replacement)
    }

    fun removeFolder(folderId: String) {
        cancelScan(folderId)
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
        val configWithoutLooseRecent =
            config.copy(
                recentItems =
                    config.recentItems.filterNot {
                        it.sourceId == LibrarySourceIds.LooseFiles
                    },
            )
        val retainedUris = configWithoutLooseRecent.retainedContentUriValues()
        updateConfig(
            configWithoutLooseRecent.copy(
                catalogItems =
                    configWithoutLooseRecent.catalogItems.filterNot {
                        it.sourceId == LibrarySourceIds.LooseFiles
                    },
                ignoredUris = configWithoutLooseRecent.ignoredUris.filterNot(looseUris::contains),
                homeShortcuts =
                    configWithoutLooseRecent.homeShortcuts.filterNot { shortcut ->
                        shortcut.type == LibraryShortcutType.Folder &&
                            shortcut.key.startsWith("${LibrarySourceIds.LooseFiles}|")
                    },
            ).withSourceRemoved(LibrarySourceIds.LooseFiles, retainedUris),
            repartition = true,
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
                    sources = state.sources.synchronizedWith(config),
                )
            } else {
                state.copy(
                    config = config,
                    sources = state.sources.synchronizedWith(config),
                )
            }
        if (scheduleWrite) configWriter.submit(config)
        if (rescan) scan(config)
    }

    private suspend fun <T> scanSafely(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    private companion object {
        const val MaxRecentItems = 50
    }
}

private data class SourceScanResult(
    val source: LibrarySourceState,
    val media: List<MediaSourceRef>,
    val subtitleSuggestions: List<TimedTextTrackCandidate> = emptyList(),
    val replaceCachedItems: Boolean,
    val locatorSourceAvailable: Boolean,
)

internal fun List<LibrarySourceState>.withSourceState(
    source: LibrarySourceState,
): List<LibrarySourceState> {
    if (any { it.id == source.id }) {
        return map { existing -> if (existing.id == source.id) source else existing }
    }
    return when (source.kind) {
        LibrarySourceKind.SystemLibrary -> listOf(source) + this
        LibrarySourceKind.Folder -> {
            val looseIndex = indexOfFirst { it.kind == LibrarySourceKind.LooseFiles }
            if (looseIndex < 0) this + source
            else take(looseIndex) + source + drop(looseIndex)
        }
        LibrarySourceKind.LooseFiles -> this + source
    }
}

private fun List<LibrarySourceState>.synchronizedWith(
    config: LibraryConfig,
): List<LibrarySourceState> {
    val current = associateBy(LibrarySourceState::id)
    val system =
        current[LibrarySourceIds.SystemLibrary]
            ?: LibrarySourceState(
                id = LibrarySourceIds.SystemLibrary,
                kind = LibrarySourceKind.SystemLibrary,
                displayName = "设备媒体库",
                itemCount = config.cachedSourceItems(LibrarySourceIds.SystemLibrary).size,
                isScanned = false,
            )
    val folders =
        config.folders.map { folder ->
            current[folder.id]?.copy(displayName = folder.displayName)
                ?: LibrarySourceState(
                    id = folder.id,
                    kind = LibrarySourceKind.Folder,
                    displayName = folder.displayName,
                    itemCount = config.cachedSourceItems(folder.id).size,
                    isScanned = false,
                )
        }
    val looseItemCount = config.cachedSourceItems(LibrarySourceIds.LooseFiles).size
    val loose =
        if (looseItemCount > 0) {
            listOf(
                current[LibrarySourceIds.LooseFiles]?.copy(itemCount = looseItemCount)
                    ?: LibrarySourceState(
                        id = LibrarySourceIds.LooseFiles,
                        kind = LibrarySourceKind.LooseFiles,
                        displayName = "单独打开",
                        itemCount = looseItemCount,
                    ),
            )
        } else {
            emptyList()
        }
    return listOf(system) + folders + loose
}
