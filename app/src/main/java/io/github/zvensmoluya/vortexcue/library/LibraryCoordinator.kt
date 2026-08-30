package io.github.zvensmoluya.vortexcue.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.storage.persistReadPermission
import io.github.zvensmoluya.vortexcue.core.storage.treeDisplayName
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.SourceScanDescriptor
import io.github.zvensmoluya.vortexcue.text.TextImportCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class LibraryCoordinatorState(
    val scanningSourceIds: Set<String> = emptySet(),
    val hasAudioAccess: Boolean = false,
    val hasFullStorageAccess: Boolean = false,
    val hasRequestedAudioAccess: Boolean = false,
    val errorMessage: String? = null,
) {
    val isLoading: Boolean
        get() = scanningSourceIds.isNotEmpty()
}

@Stable
class LibraryCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: ContentRepository,
    private val textImportCoordinator: TextImportCoordinator,
    private val scanner: LocalMediaScanner = LocalMediaScanner(context),
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val permissionPreferences =
        appContext.getSharedPreferences(PermissionPreferencesName, Context.MODE_PRIVATE)
    private val jobs = mutableMapOf<String, Job>()
    private var nextRequestId = 0L
    private val requestIds = mutableMapOf<String, Long>()

    var state by mutableStateOf(permissionState())
        private set

    suspend fun inspect(source: MediaSourceRef): MediaSourceRef = scanner.inspect(source)

    fun noteAudioPermissionRequest() {
        permissionPreferences.edit().putBoolean(AudioPermissionRequestedKey, true).apply()
        state = state.copy(hasRequestedAudioAccess = true)
    }

    fun scanDevice() {
        state = permissionState().copy(errorMessage = null)
        startSystemScan()
    }

    fun scanAll() {
        state = permissionState().copy(errorMessage = null)
        startSystemScan()
        if (state.hasFullStorageAccess) return
        scope.launch {
            repository.sourceDescriptors()
                .filter { it.kind == LibrarySourceKind.Folder && it.rootUri != null }
                .forEach(::startFolderScan)
        }
    }

    fun refreshSource(sourceId: String) {
        state = state.copy(errorMessage = null)
        if (sourceId == LibrarySourceIds.SystemLibrary) {
            state = permissionState().copy(errorMessage = null)
            startSystemScan()
            return
        }
        scope.launch {
            repository.sourceDescriptors().firstOrNull { it.sourceId == sourceId }?.let(::startFolderScan)
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
        state = state.copy(errorMessage = null)
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
            )
        }
    }

    fun replaceFolder(sourceId: String, uri: Uri) {
        state = state.copy(errorMessage = null)
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

    private fun startSystemScan() {
        val permissions = permissionState()
        state = permissions.copy(errorMessage = state.errorMessage)
        if (!permissions.hasAudioAccess && !permissions.hasFullStorageAccess) {
            cancelScan(LibrarySourceIds.SystemLibrary)
            return
        }
        launchScan(LibrarySourceIds.SystemLibrary) {
            val scan =
                runCatchingSuspend {
                    if (permissions.hasFullStorageAccess) {
                        scanner.scanFullStorage()
                    } else {
                        LibraryScanBatch(media = scanner.scanSystemAudio())
                    }
                }
            val descriptor =
                SourceScanDescriptor(
                    sourceId = LibrarySourceIds.SystemLibrary,
                    kind = LibrarySourceKind.SystemLibrary,
                    rootUri = null,
                    displayName = "设备媒体库",
                )
            val authoritative =
                if (permissions.hasFullStorageAccess) {
                    scan.isSuccess
                } else {
                    isAuthoritativeSystemAudioScan(permissions.hasAudioAccess, scan.isSuccess)
                }
            when {
                authoritative -> {
                    val batch = scan.getOrDefault(LibraryScanBatch())
                    val candidateIds =
                        repository.applySuccessfulScan(
                            descriptor,
                            batch.media,
                            batch.subtitleSuggestions,
                        )
                    val materializedContentIds = textImportCoordinator.materializeCandidates(candidateIds)
                    repository.reconcileAutomaticTextSelection(materializedContentIds)
                }
                scan.getOrDefault(LibraryScanBatch()).media.isNotEmpty() ->
                    repository.applyIncrementalScan(
                        descriptor = descriptor,
                        media = scan.getOrDefault(LibraryScanBatch()).media,
                        textCandidates = scan.getOrDefault(LibraryScanBatch()).subtitleSuggestions,
                        warning = "系统音频索引不完整，已保留上次结果",
                    )
                else ->
                    repository.recordSourceScanFailure(
                        descriptor = descriptor,
                        message = "无法读取系统媒体目录",
                        hasAccess = true,
                    )
            }
        }
    }

    private fun startFolderScan(descriptor: SourceScanDescriptor) {
        val root = descriptor.rootUri?.let(Uri::parse) ?: return
        launchScan(descriptor.sourceId) {
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
                    val candidateIds =
                        repository.applySuccessfulScan(
                            descriptor = descriptor,
                            media = batch.media,
                            textCandidates = batch.subtitleSuggestions,
                        )
                    val materializedContentIds = textImportCoordinator.materializeCandidates(candidateIds)
                    repository.reconcileAutomaticTextSelection(materializedContentIds)
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

    private fun permissionState(): LibraryCoordinatorState =
        LibraryCoordinatorState(
            hasAudioAccess = appContext.hasAudioLibraryPermission(),
            hasFullStorageAccess = appContext.hasFullStorageAccess(),
            hasRequestedAudioAccess = permissionPreferences.getBoolean(AudioPermissionRequestedKey, false),
        )

    private companion object {
        const val PermissionPreferencesName = "library_permissions"
        const val AudioPermissionRequestedKey = "audio_permission_requested"
    }

    private suspend fun <T> runCatchingSuspend(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
}
