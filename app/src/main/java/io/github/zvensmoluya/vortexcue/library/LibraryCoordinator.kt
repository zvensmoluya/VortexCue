package io.github.zvensmoluya.vortexcue.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.MediaKind
import io.github.zvensmoluya.vortexcue.core.storage.persistReadPermission
import io.github.zvensmoluya.vortexcue.core.storage.treeDisplayName
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.SourceScanDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class LibraryCoordinatorState(
    val scanningSourceIds: Set<String> = emptySet(),
    val hasAudioAccess: Boolean = false,
    val videoAccess: VideoLibraryAccess = VideoLibraryAccess.None,
    val errorMessage: String? = null,
    val pendingImport: PendingImportBatch? = null,
) {
    val isLoading: Boolean
        get() = scanningSourceIds.isNotEmpty()
}

data class PendingImportBatch(
    val items: List<PendingImportItem>,
    val defaultSelectedContentIds: Set<String>,
) {
    val contentIds: List<String>
        get() = items.map { it.contentId }
}

data class PendingImportItem(
    val contentId: String,
    val title: String,
    val kind: MediaKind,
)

@Stable
class LibraryCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: ContentRepository,
    private val scanner: LocalMediaScanner = LocalMediaScanner(context),
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val jobs = mutableMapOf<String, Job>()
    private var nextRequestId = 0L
    private val requestIds = mutableMapOf<String, Long>()

    var state by mutableStateOf(permissionState())
        private set

    suspend fun inspect(source: MediaSourceRef): MediaSourceRef = scanner.inspect(source)

    fun scanDevice(admitNew: Boolean = true, audioOnly: Boolean = true) {
        state = permissionState().copy(errorMessage = null, pendingImport = null)
        startSystemScan(admitNew = admitNew, audioOnly = audioOnly)
    }

    fun scanAll(admitNew: Boolean = true, audioOnly: Boolean = true) {
        state = permissionState().copy(errorMessage = null, pendingImport = null)
        startSystemScan(admitNew = admitNew, audioOnly = audioOnly)
        scope.launch {
            repository.sourceDescriptors()
                .filter { it.kind == LibrarySourceKind.Folder && it.rootUri != null }
                .forEach { startFolderScan(it, admitNew = admitNew) }
        }
    }

    fun refreshSource(sourceId: String) {
        state = state.copy(pendingImport = null, errorMessage = null)
        if (sourceId == LibrarySourceIds.SystemLibrary) {
            state = permissionState().copy(errorMessage = null, pendingImport = null)
            startSystemScan(admitNew = true, audioOnly = true)
            return
        }
        scope.launch {
            repository.sourceDescriptors().firstOrNull { it.sourceId == sourceId }?.let { startFolderScan(it, admitNew = true) }
        }
    }

    fun cancelScan(sourceId: String? = null) {
        val ids = sourceId?.let(::setOf) ?: jobs.keys.toSet()
        ids.forEach { id ->
            requestIds.remove(id)
            jobs.remove(id)?.cancel()
        }
        state = state.copy(scanningSourceIds = state.scanningSourceIds - ids)
    }

    fun addFolder(uri: Uri) {
        state = state.copy(pendingImport = null, errorMessage = null)
        resolver.persistReadPermission(uri)
        scope.launch {
            val rootUri = uri.toString()
            val previous = repository.sourceDescriptorForRoot(rootUri)
            startFolderScan(
                SourceScanDescriptor(
                    sourceId = previous?.sourceId ?: "folder:$uri",
                    kind = LibrarySourceKind.Folder,
                    rootUri = rootUri,
                    displayName = resolver.treeDisplayName(uri),
                ),
                admitNew = true,
            )
        }
    }

    fun replaceFolder(sourceId: String, uri: Uri) {
        state = state.copy(pendingImport = null, errorMessage = null)
        resolver.persistReadPermission(uri)
        scope.launch {
            val existing = repository.sourceDescriptorForRoot(uri.toString())
            if (existing != null && existing.sourceId != sourceId) {
                state = state.copy(errorMessage = "该文件夹已经作为另一个来源存在")
                return@launch
            }
            repository.sourceDescriptors().firstOrNull { it.sourceId == sourceId }?.rootUri?.let { old ->
                if (old != uri.toString()) {
                    runCatching {
                        resolver.releasePersistableUriPermission(
                            Uri.parse(old),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
            startFolderScan(
                SourceScanDescriptor(
                    sourceId = sourceId,
                    kind = LibrarySourceKind.Folder,
                    rootUri = uri.toString(),
                    displayName = resolver.treeDisplayName(uri),
                ),
                admitNew = true,
            )
        }
    }

    fun removeSource(sourceId: String) {
        cancelScan(sourceId)
        scope.launch {
            repository.sourceDescriptors().firstOrNull { it.sourceId == sourceId }?.rootUri?.let { root ->
                runCatching {
                    resolver.releasePersistableUriPermission(
                        Uri.parse(root),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            repository.removeSource(sourceId)
        }
    }

    fun clearPendingImport() {
        state = state.copy(pendingImport = null)
    }

    private fun startSystemScan(admitNew: Boolean, audioOnly: Boolean) {
        val permissions = permissionState()
        state = permissions.copy(errorMessage = state.errorMessage)
        if (!permissions.hasAudioAccess && permissions.videoAccess == VideoLibraryAccess.None) {
            cancelScan(LibrarySourceIds.SystemLibrary)
            return
        }
        launchScan(LibrarySourceIds.SystemLibrary) {
            val undecidedBefore =
                if (admitNew) repository.undecidedContentIdsForSource(LibrarySourceIds.SystemLibrary).toSet()
                else emptySet()
            val audio =
                if (permissions.hasAudioAccess) runCatchingSuspend { scanner.scanSystemAudio() }
                else Result.success(emptyList())
            val video =
                if (permissions.videoAccess != VideoLibraryAccess.None) runCatchingSuspend { scanner.scanSystemVideo() }
                else Result.success(emptyList())
            val media = audio.getOrDefault(emptyList()) + video.getOrDefault(emptyList())
            val descriptor =
                SourceScanDescriptor(
                    sourceId = LibrarySourceIds.SystemLibrary,
                    kind = LibrarySourceKind.SystemLibrary,
                    rootUri = null,
                    displayName = "设备媒体库",
                )
            val authoritative =
                isAuthoritativeSystemScan(
                    hasAudioAccess = permissions.hasAudioAccess,
                    videoAccess = permissions.videoAccess,
                    audioScanSucceeded = audio.isSuccess,
                    videoScanSucceeded = video.isSuccess,
                )
            when {
                authoritative -> {
                    repository.applySuccessfulScan(descriptor, media, emptyList())
                    if (admitNew) publishNewDiscoveries(descriptor.sourceId, undecidedBefore, media, audioOnly)
                }
                media.isNotEmpty() ->
                    repository.applyIncrementalScan(
                        descriptor = descriptor,
                        media = media,
                        warning = "系统媒体授权不完整，已保留上次完整索引",
                    ).also {
                        if (admitNew) publishNewDiscoveries(descriptor.sourceId, undecidedBefore, media, audioOnly)
                    }
                else ->
                    repository.recordSourceScanFailure(
                        descriptor = descriptor,
                        message = "无法读取系统媒体目录",
                        hasAccess = true,
                    )
            }
        }
    }

    private fun startFolderScan(descriptor: SourceScanDescriptor, admitNew: Boolean) {
        val root = descriptor.rootUri?.let(Uri::parse) ?: return
        launchScan(descriptor.sourceId) {
            val undecidedBefore =
                if (admitNew) repository.undecidedContentIdsForSource(descriptor.sourceId).toSet()
                else emptySet()
            val result = runCatchingSuspend {
                scanner.scanFolder(
                    LibraryFolderRef(
                        uri = root,
                        displayName = descriptor.displayName,
                        id = descriptor.sourceId,
                    ),
                )
            }
            result.fold(
                onSuccess = { batch ->
                    repository.applySuccessfulScan(
                        descriptor = descriptor,
                        media = batch.media,
                        textCandidates = batch.subtitleSuggestions,
                    )
                    if (admitNew) publishNewDiscoveries(descriptor.sourceId, undecidedBefore, batch.media, audioOnly = false)
                },
                onFailure = { error ->
                    repository.recordSourceScanFailure(
                        descriptor = descriptor,
                        message = "文件夹授权已失效或暂时不可用",
                        hasAccess = false,
                    )
                    state = state.copy(errorMessage = error.message)
                },
            )
        }
    }

    private fun launchScan(sourceId: String, block: suspend () -> Unit) {
        cancelScan(sourceId)
        val requestId = ++nextRequestId
        requestIds[sourceId] = requestId
        state = state.copy(scanningSourceIds = state.scanningSourceIds + sourceId)
        jobs[sourceId] =
            scope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    state = state.copy(errorMessage = error.message ?: "扫描失败")
                } finally {
                    if (requestIds[sourceId] == requestId) {
                        requestIds.remove(sourceId)
                        jobs.remove(sourceId)
                        state = state.copy(scanningSourceIds = state.scanningSourceIds - sourceId)
                    }
                }
            }
    }

    private suspend fun publishNewDiscoveries(
        sourceId: String,
        undecidedBefore: Set<String>,
        media: List<MediaSourceRef>,
        audioOnly: Boolean,
    ) {
        val undecidedAfter = repository.undecidedContentIdsForSource(sourceId).toSet()
        val discovered =
            media.mapNotNull { item ->
                repository.contentIdFor(item)?.let { contentId ->
                    PendingImportItem(
                        contentId = contentId,
                        title = item.title?.takeIf(String::isNotBlank) ?: item.displayName,
                        kind = item.kind,
                    )
                }
            }.distinctBy { it.contentId }
                .filter { it.contentId in undecidedAfter && it.contentId !in undecidedBefore }
        if (discovered.isEmpty()) return
        val previous = state.pendingImport
        val items = (previous?.items.orEmpty() + discovered).distinctBy { it.contentId }
        val selected =
            previous?.defaultSelectedContentIds.orEmpty() +
                discovered.filter { !audioOnly || it.kind == MediaKind.Audio }.map { it.contentId }
        state = state.copy(pendingImport = PendingImportBatch(items, selected))
    }

    private fun permissionState(): LibraryCoordinatorState =
        LibraryCoordinatorState(
            hasAudioAccess = appContext.hasAudioLibraryPermission(),
            videoAccess = appContext.videoLibraryAccess(),
        )

    private suspend fun <T> runCatchingSuspend(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
}
