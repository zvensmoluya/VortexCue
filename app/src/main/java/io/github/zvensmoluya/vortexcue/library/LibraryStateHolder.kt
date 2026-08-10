package io.github.zvensmoluya.vortexcue.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
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

                val subtitleSuggestions = mutableListOf<SubtitleAssociation>()
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
                    config.manualItems.map { item ->
                        item.copy(
                            origin = MediaOrigin.OpenDocument,
                            sourceId = LibrarySourceIds.LooseFiles,
                        )
                    }
                scanned += looseItems
                sources +=
                    LibrarySourceState(
                        id = LibrarySourceIds.LooseFiles,
                        kind = LibrarySourceKind.LooseFiles,
                        displayName = "单独打开",
                        itemCount = looseItems.size,
                    )

                if (requestId != scanRequestId) return@launch
                val discovered =
                    scanned
                        .distinctBy { it.uri.toString() }
                        .sortedBy { it.displayTitle.lowercase() }
                val partition = partitionLibrary(discovered, config)
                state =
                    state.copy(
                        items = partition.included,
                        candidates = partition.candidates,
                        ignoredItems = partition.ignored,
                        config = config,
                        sources = sources,
                        suggestedSubtitleAssociations = subtitleSuggestions,
                        isLoading = false,
                        hasAudioAccess = hasAudioAccess,
                        videoAccess = videoAccess,
                        errorMessage = sources.firstNotNullOfOrNull { it.errorMessage },
                    )
            }
    }

    fun rememberManualMedia(source: MediaSourceRef) {
        cancelScan()
        val config = state.config
        val looseSource =
            source.copy(
                origin = MediaOrigin.OpenDocument,
                sourceId = LibrarySourceIds.LooseFiles,
            )
        val updatedConfig =
            config.copy(
                manualItems = listOf(looseSource) + config.manualItems.filterNot { it.uri == source.uri },
                includedUris = (config.includedUris + looseSource.uri.toString()).distinct(),
                ignoredUris = config.ignoredUris - looseSource.uri.toString(),
                homeShortcuts =
                    (config.homeShortcuts + looseSource.toFolderShortcut()).distinct(),
            )
        val discovered =
            (listOf(looseSource) + state.allDiscoveredItems())
                .distinctBy { it.uri.toString() }
        val partition = partitionLibrary(discovered, updatedConfig)
        val looseSourceState =
            LibrarySourceState(
                id = LibrarySourceIds.LooseFiles,
                kind = LibrarySourceKind.LooseFiles,
                displayName = "单独打开",
                itemCount = updatedConfig.manualItems.size,
            )
        state =
            state.copy(
                items = partition.included,
                candidates = partition.candidates,
                ignoredItems = partition.ignored,
                config = updatedConfig,
                sources =
                    state.sources.filterNot { it.id == LibrarySourceIds.LooseFiles } +
                        looseSourceState,
            )
        coroutineScope.launch { store.save(updatedConfig) }
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
        val removedItems = state.allDiscoveredItems().filter { it.sourceId == folderId }
        val removedUris = removedItems.map { it.uri.toString() }.toSet()
        updateConfig(
            config.copy(
                folders = config.folders.filterNot { it.id == folderId },
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
            ),
            rescan = true,
        )
    }

    fun clearLooseFiles() {
        val config = state.config
        val looseUris = config.manualItems.map { it.uri.toString() }.toSet()
        updateConfig(
            config.copy(
                manualItems = emptyList(),
                includedUris = config.includedUris.filterNot(looseUris::contains),
                ignoredUris = config.ignoredUris.filterNot(looseUris::contains),
                homeShortcuts =
                    config.homeShortcuts.filterNot { shortcut ->
                        shortcut.type == LibraryShortcutType.Folder &&
                            shortcut.key.startsWith("${LibrarySourceIds.LooseFiles}|")
                    },
                playlists = config.playlists.withoutItems(looseUris),
            ),
            rescan = true,
        )
    }

    fun acceptCandidates(uriValues: Set<String>) {
        if (uriValues.isEmpty()) return
        val config = state.config
        val suggestions =
            state.suggestedSubtitleAssociations.filter { it.mediaUri.toString() in uriValues }
        val updatedAssociations =
            (config.subtitleAssociations.filterNot { existing ->
                suggestions.any { it.mediaUri == existing.mediaUri }
            } + suggestions).distinctBy { it.mediaUri }
        updateConfig(
            config.copy(
                includedUris = (config.includedUris + uriValues).distinct(),
                ignoredUris = config.ignoredUris.filterNot(uriValues::contains),
                subtitleAssociations = updatedAssociations,
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
        updateConfig(
            config.copy(
                manualItems = config.manualItems.filterNot { it.uri.toString() in uriValues },
                includedUris = config.includedUris.filterNot(uriValues::contains),
                ignoredUris = (config.ignoredUris + uriValues).distinct(),
                subtitleAssociations =
                    config.subtitleAssociations.filterNot { it.mediaUri.toString() in uriValues },
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
        updateConfig(
            config.copy(
                playlists = config.playlists + playlist,
                homeShortcuts =
                    config.homeShortcuts +
                        LibraryShortcut(LibraryShortcutType.Playlist, playlist.id),
            ),
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
        updateConfig(config.copy(playlists = playlists))
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
        mediaUri: Uri,
        subtitle: SubtitleSourceRef?,
    ) {
        val config = state.config
        val associations =
            config.subtitleAssociations.filterNot { it.mediaUri == mediaUri } +
                listOfNotNull(subtitle?.let { SubtitleAssociation(mediaUri, it) })
        updateConfig(config.copy(subtitleAssociations = associations))
    }

    private fun updateConfig(
        config: LibraryConfig,
        rescan: Boolean = false,
        repartition: Boolean = false,
    ) {
        state =
            if (repartition) {
                val partition = partitionLibrary(state.allDiscoveredItems(), config)
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
}
