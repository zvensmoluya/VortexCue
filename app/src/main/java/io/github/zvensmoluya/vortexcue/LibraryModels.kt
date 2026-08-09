package io.github.zvensmoluya.vortexcue

import android.net.Uri

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
)

enum class LibraryPinType {
    Track,
    Album,
    Artist,
    Folder,
    Playlist,
}

data class LibraryPin(
    val type: LibraryPinType,
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
    val manualItems: List<MediaSourceRef> = emptyList(),
    val folders: List<LibraryFolderRef> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val subtitleAssociations: List<SubtitleAssociation> = emptyList(),
    val pins: List<LibraryPin> = emptyList(),
)

data class MediaLibraryState(
    val items: List<MediaSourceRef> = emptyList(),
    val config: LibraryConfig = LibraryConfig(),
    val sources: List<LibrarySourceState> = emptyList(),
    val isLoading: Boolean = false,
    val hasSystemAccess: Boolean = false,
    val errorMessage: String? = null,
)

object LibrarySourceIds {
    const val SystemLibrary = "system-library"
    const val LooseFiles = "loose-files"
}
