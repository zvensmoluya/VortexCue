package io.github.zvensmoluya.vortexcue

import android.net.Uri

data class LibraryFolderRef(
    val uri: Uri,
    val displayName: String,
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
)

data class MediaLibraryState(
    val items: List<MediaSourceRef> = emptyList(),
    val config: LibraryConfig = LibraryConfig(),
    val isLoading: Boolean = false,
    val hasSystemAccess: Boolean = false,
    val errorMessage: String? = null,
)
