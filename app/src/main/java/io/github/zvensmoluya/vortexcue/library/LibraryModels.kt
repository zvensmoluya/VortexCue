package io.github.zvensmoluya.vortexcue.library

import android.net.Uri
import io.github.zvensmoluya.vortexcue.core.model.*

data class LibraryFolderRef(
    val uri: Uri,
    val displayName: String,
    val id: String = "folder:$uri",
)

enum class LibrarySourceKind {
    SystemLibrary,
    Folder,
    LooseFiles,
}

data class LibrarySourceState(
    val id: String,
    val kind: LibrarySourceKind,
    val displayName: String,
    val itemCount: Int = 0,
    val hasAccess: Boolean = true,
    val errorMessage: String? = null,
    val isScanned: Boolean = true,
)

data class LibraryScanBatch(
    val media: List<MediaSourceRef> = emptyList(),
    val subtitleSuggestions: List<TimedTextTrackCandidate> = emptyList(),
)

internal data class LibraryScanScope(
    val includeSystemLibrary: Boolean,
    val folderIds: Set<String>?,
) {
    fun includesFolder(folderId: String): Boolean = folderIds?.contains(folderId) != false

    companion object {
        val All = LibraryScanScope(includeSystemLibrary = true, folderIds = null)

        val SystemLibrary =
            LibraryScanScope(includeSystemLibrary = true, folderIds = emptySet())

        fun folder(folderId: String) =
            LibraryScanScope(includeSystemLibrary = false, folderIds = setOf(folderId))
    }
}

internal fun isAuthoritativeSystemAudioScan(
    hasAudioAccess: Boolean,
    audioScanSucceeded: Boolean,
): Boolean = hasAudioAccess && audioScanSucceeded

object LibrarySourceIds {
    const val SystemLibrary = "system-library"
    const val LooseFiles = "loose-files"
}

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
            if (looseIndex < 0) this + source else take(looseIndex) + source + drop(looseIndex)
        }
        LibrarySourceKind.LooseFiles -> this + source
    }
}
