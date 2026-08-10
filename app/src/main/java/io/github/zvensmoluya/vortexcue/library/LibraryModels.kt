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

enum class LibraryShortcutType {
    Folder,
    Playlist,
}

data class LibraryShortcut(
    val type: LibraryShortcutType,
    val key: String,
)

data class LibraryPlaylist(
    val id: String,
    val name: String,
    val items: List<MediaSourceRef>,
)

data class SubtitleAssociation(
    val mediaUri: Uri,
    val subtitle: SubtitleSourceRef,
)

data class LibraryConfig(
    val catalogItems: List<MediaSourceRef> = emptyList(),
    val recentItems: List<MediaSourceRef> = emptyList(),
    val folders: List<LibraryFolderRef> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val subtitleAssociations: List<SubtitleAssociation> = emptyList(),
    val includedUris: List<String> = emptyList(),
    val ignoredUris: List<String> = emptyList(),
    val homeShortcuts: List<LibraryShortcut> = emptyList(),
)

data class MediaLibraryState(
    val items: List<MediaSourceRef> = emptyList(),
    val candidates: List<MediaSourceRef> = emptyList(),
    val ignoredItems: List<MediaSourceRef> = emptyList(),
    val config: LibraryConfig = LibraryConfig(),
    val sources: List<LibrarySourceState> = emptyList(),
    val suggestedSubtitleAssociations: List<SubtitleAssociation> = emptyList(),
    val isLoading: Boolean = false,
    val hasAudioAccess: Boolean = false,
    val videoAccess: VideoLibraryAccess = VideoLibraryAccess.None,
    val errorMessage: String? = null,
) {
    val hasVideoAccess: Boolean
        get() = videoAccess != VideoLibraryAccess.None

    val hasSystemAccess: Boolean
        get() = hasAudioAccess || hasVideoAccess

    val hasCompleteSystemAccess: Boolean
        get() = hasAudioAccess && videoAccess == VideoLibraryAccess.Full
}

data class LibraryScanBatch(
    val media: List<MediaSourceRef> = emptyList(),
    val subtitleSuggestions: List<SubtitleAssociation> = emptyList(),
)

object LibrarySourceIds {
    const val SystemLibrary = "system-library"
    const val LooseFiles = "loose-files"
}

fun MediaSourceRef.libraryFolderKey(): String =
    "${sourceId.orEmpty()}|${folder.orEmpty()}"

val MediaSourceRef.libraryFolderName: String
    get() =
        folder
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: if (sourceId == LibrarySourceIds.LooseFiles) "单独打开" else "未分类"

fun MediaSourceRef.toFolderShortcut(): LibraryShortcut =
    LibraryShortcut(LibraryShortcutType.Folder, libraryFolderKey())
