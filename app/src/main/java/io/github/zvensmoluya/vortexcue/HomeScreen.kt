package io.github.zvensmoluya.vortexcue

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

internal enum class HomeSection {
    Library,
    Sources,
    Display,
}

private enum class LibraryView(val label: String) {
    Albums("专辑"),
    Artists("歌手"),
    Songs("歌曲"),
    Folders("文件夹"),
    Playlists("播放列表"),
}

private data class ResolvedLibraryPin(
    val pin: LibraryPin,
    val title: String,
    val detail: String,
    val items: List<MediaSourceRef>,
    val view: LibraryView? = null,
    val groupKey: String? = null,
)

@Composable
internal fun HomeScreen(
    section: HomeSection,
    onSectionChanged: (HomeSection) -> Unit,
    draft: SessionDraft,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    playback: PlaybackSnapshot,
    displayProfile: DisplayProfile,
    library: MediaLibraryState,
    onPickMedia: () -> Unit,
    onPickSubtitle: () -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onClearLooseFiles: () -> Unit,
    onTogglePin: (LibraryPin) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onPlayNext: (MediaSourceRef) -> Unit,
    onAddToQueue: (MediaSourceRef) -> Unit,
    onCreatePlaylist: (String, MediaSourceRef?) -> Unit,
    onAddToPlaylist: (String, MediaSourceRef) -> Unit,
    onRemoveFromPlaylist: (String, MediaSourceRef) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveSubtitle: () -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var libraryView by rememberSaveable { mutableStateOf<LibraryView?>(null) }
    var selectedGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var importVisible by rememberSaveable { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var mediaActionTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var createPlaylistVisible by rememberSaveable { mutableStateOf(false) }
    var createPlaylistName by rememberSaveable { mutableStateOf("") }
    var createPlaylistSeed by remember { mutableStateOf<MediaSourceRef?>(null) }
    var folderRemovalTarget by remember { mutableStateOf<LibraryFolderRef?>(null) }
    var clearLooseFilesConfirmation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(libraryView) {
        selectedGroupKey = null
    }
    LaunchedEffect(section) {
        if (section != HomeSection.Library) {
            libraryView = null
            selectedGroupKey = null
            searchVisible = false
        }
    }

    val resumableSession = activeSession ?: lastSession?.session
    val miniPlayerSession = resumableSession?.takeIf { section == HomeSection.Library }
    val showMiniPlayer = miniPlayerSession != null

    BackHandler(
        enabled =
            section == HomeSection.Library &&
                (selectedGroupKey != null || libraryView != null || searchVisible),
    ) {
        when {
            selectedGroupKey != null -> selectedGroupKey = null
            libraryView != null -> libraryView = null
            searchVisible -> {
                searchVisible = false
                searchQuery = ""
            }
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeHeader(
            section = section,
            libraryView = libraryView,
            searchVisible = searchVisible,
            onBackToLibrary = {
                when {
                    section != HomeSection.Library -> onSectionChanged(HomeSection.Library)
                    selectedGroupKey != null -> selectedGroupKey = null
                    libraryView != null -> libraryView = null
                    searchVisible -> {
                        searchVisible = false
                        searchQuery = ""
                    }
                }
            },
            onSearch = {
                libraryView = null
                selectedGroupKey = null
                searchVisible = true
            },
            onSources = { onSectionChanged(HomeSection.Sources) },
            onDisplay = { onSectionChanged(HomeSection.Display) },
        )

        when (section) {
            HomeSection.Library ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        searchVisible ->
                            LibrarySearch(
                                query = searchQuery,
                                onQueryChanged = { searchQuery = it },
                                library = library,
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                onOpenGroup = { view, key ->
                                    searchVisible = false
                                    searchQuery = ""
                                    libraryView = view
                                    selectedGroupKey = key
                                },
                                modifier = Modifier.fillMaxSize(),
                                bottomPadding = if (showMiniPlayer) 88.dp else 8.dp,
                            )
                        libraryView != null ->
                            LibraryCategory(
                                view = libraryView!!,
                                selectedGroupKey = selectedGroupKey,
                                library = library,
                                onSelectedGroupChanged = { selectedGroupKey = it },
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                onCreateEmptyPlaylist = {
                                    createPlaylistSeed = null
                                    createPlaylistName = ""
                                    createPlaylistVisible = true
                                },
                                onRemoveFromPlaylist = onRemoveFromPlaylist,
                                onDeletePlaylist = onDeletePlaylist,
                                onTogglePin = onTogglePin,
                                modifier = Modifier.fillMaxSize(),
                                bottomPadding = if (showMiniPlayer) 88.dp else 8.dp,
                            )
                        else ->
                            LibraryLanding(
                                library = library,
                                onOpenView = { libraryView = it },
                                onOpenPin = { resolved ->
                                    if (resolved.view == null) {
                                        if (resolved.items.isNotEmpty()) {
                                            onPlayLibraryItems(resolved.items, 0)
                                        }
                                    } else {
                                        libraryView = resolved.view
                                        selectedGroupKey = resolved.groupKey
                                    }
                                },
                                onTogglePin = onTogglePin,
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                onRequestLibraryAccess = onRequestLibraryAccess,
                                onAddFolder = onAddFolder,
                                onOpenFile = {
                                    importVisible = true
                                    onPickMedia()
                                },
                                onOpenSources = { onSectionChanged(HomeSection.Sources) },
                                modifier = Modifier.fillMaxSize(),
                                bottomPadding = if (showMiniPlayer) 88.dp else 8.dp,
                            )
                    }

                    miniPlayerSession?.let { session ->
                        NowPlayingBar(
                            session = session,
                            activeSession = activeSession,
                            lastSession = lastSession,
                            playback = playback,
                            onContinue = onContinue,
                            onPlayPause = onPlayPause,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            HomeSection.Sources ->
                ContentSources(
                    library = library,
                    onRequestLibraryAccess = onRequestLibraryAccess,
                    onRefreshLibrary = onRefreshLibrary,
                    onAddFolder = onAddFolder,
                    onOpenFile = {
                        importVisible = true
                        onPickMedia()
                    },
                    onRemoveFolder = { folderId ->
                        folderRemovalTarget =
                            library.config.folders.firstOrNull { it.id == folderId }
                    },
                    onClearLooseFiles = { clearLooseFilesConfirmation = true },
                    modifier = Modifier.weight(1f),
                )
            HomeSection.Display ->
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    DisplaySettings(
                        profile = displayProfile,
                        onProfileChanged = onDisplayProfileChanged,
                    )
                }
        }
    }

    playlistTarget?.let { item ->
        PlaylistChooserDialog(
            item = item,
            playlists = library.config.playlists,
            onDismiss = { playlistTarget = null },
            onAdd = { playlistId ->
                onAddToPlaylist(playlistId, item)
                playlistTarget = null
            },
            onCreate = {
                createPlaylistSeed = item
                createPlaylistName = ""
                playlistTarget = null
                createPlaylistVisible = true
            },
        )
    }

    mediaActionTarget?.let { item ->
        val pin = LibraryPin(LibraryPinType.Track, item.uri.toString())
        MediaActionDialog(
            item = item,
            isPinned = pin in library.config.pins,
            onDismiss = { mediaActionTarget = null },
            onPlayNext = {
                onPlayNext(item)
                mediaActionTarget = null
            },
            onAddToQueue = {
                onAddToQueue(item)
                mediaActionTarget = null
            },
            onChoosePlaylist = {
                mediaActionTarget = null
                playlistTarget = item
            },
            onTogglePin = {
                onTogglePin(pin)
                mediaActionTarget = null
            },
        )
    }

    if (createPlaylistVisible) {
        CreatePlaylistDialog(
            name = createPlaylistName,
            onNameChanged = { createPlaylistName = it },
            onDismiss = { createPlaylistVisible = false },
            onConfirm = {
                onCreatePlaylist(createPlaylistName, createPlaylistSeed)
                createPlaylistVisible = false
                createPlaylistName = ""
                createPlaylistSeed = null
            },
        )
    }

    if (importVisible) {
        ImportContentDialog(
            draft = draft,
            activeSession = activeSession,
            onPickMedia = onPickMedia,
            onPickSubtitle = onPickSubtitle,
            onRemoveSubtitle = onRemoveSubtitle,
            onClearDraft = onClearDraft,
            onApplyDraft = {
                importVisible = false
                onApplyDraft()
            },
            onDismiss = { importVisible = false },
        )
    }

    folderRemovalTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderRemovalTarget = null },
            title = { Text("移除内容来源？", color = AppPrimaryText) },
            text = {
                Text(
                    "将停止扫描“${folder.displayName}”，不会删除其中的任何文件。",
                    color = AppSecondaryText,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveFolder(folder.id)
                        folderRemovalTarget = null
                    },
                ) {
                    Text("移除", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderRemovalTarget = null }) {
                    Text("取消", color = AppSecondaryText)
                }
            },
            containerColor = AppSurfaceRaised,
        )
    }

    if (clearLooseFilesConfirmation) {
        AlertDialog(
            onDismissRequest = { clearLooseFilesConfirmation = false },
            title = { Text("清空单独打开记录？", color = AppPrimaryText) },
            text = {
                Text(
                    "只会从资料库移除这些记录，不会删除原文件。",
                    color = AppSecondaryText,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearLooseFiles()
                        clearLooseFilesConfirmation = false
                    },
                ) {
                    Text("清空", color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearLooseFilesConfirmation = false }) {
                    Text("取消", color = AppSecondaryText)
                }
            },
            containerColor = AppSurfaceRaised,
        )
    }
}

@Composable
private fun HomeHeader(
    section: HomeSection,
    libraryView: LibraryView?,
    searchVisible: Boolean,
    onBackToLibrary: () -> Unit,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onDisplay: () -> Unit,
) {
    val isLibraryRoot =
        section == HomeSection.Library && libraryView == null && !searchVisible
    val title =
        when {
            section == HomeSection.Sources -> "内容来源"
            section == HomeSection.Display -> "显示"
            searchVisible -> "搜索"
            libraryView != null -> libraryView.label
            else -> "资料库"
        }

    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isLibraryRoot) {
                TextButton(onClick = onBackToLibrary, contentPadding = PaddingValues(end = 12.dp)) {
                    Text("返回", color = AppSecondaryText, fontSize = 12.sp)
                }
            }
            Text(
                text = title,
                color = AppPrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLibraryRoot) {
                TextButton(onClick = onSearch) {
                    Text("搜索", color = AppSecondaryText, fontSize = 12.sp)
                }
                TextButton(onClick = onSources) {
                    Text("来源", color = AppSecondaryText, fontSize = 12.sp)
                }
                TextButton(onClick = onDisplay) {
                    Text("设置", color = AppSecondaryText, fontSize = 12.sp)
                }
            } else if (section == HomeSection.Library && !searchVisible) {
                TextButton(onClick = onSearch) {
                    Text("搜索", color = AppSecondaryText, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LibraryLanding(
    library: MediaLibraryState,
    onOpenView: (LibraryView) -> Unit,
    onOpenPin: (ResolvedLibraryPin) -> Unit,
    onTogglePin: (LibraryPin) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenSources: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val pins = remember(library.items, library.config) { resolvePins(library) }
    val recentItems =
        remember(library.items) {
            library.items
                .filter { it.dateAddedSeconds > 0L }
                .sortedByDescending(MediaSourceRef::dateAddedSeconds)
                .take(6)
        }
    val isEmpty = library.items.isEmpty() && library.config.playlists.isEmpty()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (library.isLoading) {
            item(key = "library-loading") { StatusText("正在整理本地内容…") }
        }
        library.errorMessage?.let { message ->
            item(key = "library-error") { StatusText(message, AppError) }
        }

        if (isEmpty && !library.isLoading) {
            item(key = "empty-library") {
                EmptyLibrary(
                    hasSystemAccess = library.hasSystemAccess,
                    onRequestLibraryAccess = onRequestLibraryAccess,
                    onAddFolder = onAddFolder,
                    onOpenFile = onOpenFile,
                )
            }
        } else {
            if (pins.isNotEmpty()) {
                item(key = "pins-heading") { SectionHeading("固定", "${pins.size} 项") }
                items(pins, key = { "pin-${it.pin.type}-${it.pin.key}" }) { pin ->
                    PinnedRow(
                        pin = pin,
                        onOpen = { onOpenPin(pin) },
                        onPlay = {
                            if (pin.items.isNotEmpty()) onPlayLibraryItems(pin.items, 0)
                        },
                        onRemove = { onTogglePin(pin.pin) },
                    )
                }
            }

            if (recentItems.isNotEmpty()) {
                item(key = "recent-heading") {
                    SectionHeading("最近加入", "${recentItems.size} 项")
                }
                itemsIndexed(
                    items = recentItems,
                    key = { _, item -> "recent-${item.uri}" },
                ) { index, media ->
                    TrackRow(
                        media = media,
                        onPlay = { onPlayLibraryItems(recentItems, index) },
                        onOpenMediaActions = { onOpenMediaActions(media) },
                    )
                }
            }

            item(key = "browse-heading") { SectionHeading("浏览", "${library.items.size} 首") }
            item(key = "browse-shortcuts") {
                BrowseShortcuts(library = library, onOpenView = onOpenView)
            }
            item(key = "source-status") {
                SourceStatusEntry(library = library, onOpen = onOpenSources)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    hasSystemAccess: Boolean,
    onRequestLibraryAccess: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("资料库为空", color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(
            "选择一种方式开始。之后可以在内容来源中继续添加或移除。",
            color = AppSecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        QuietButton(
            label = if (hasSystemAccess) "重新扫描设备" else "扫描设备音乐",
            onClick = onRequestLibraryAccess,
            emphasized = true,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietButton("添加文件夹", onAddFolder, Modifier.fillMaxWidth())
        TextButton(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) {
            Text("只打开一个文件", color = AppSecondaryText)
        }
    }
}

@Composable
private fun BrowseShortcuts(
    library: MediaLibraryState,
    onOpenView: (LibraryView) -> Unit,
) {
    val albumCount = groupsFor(LibraryView.Albums, library.items).size
    val artistCount = groupsFor(LibraryView.Artists, library.items).size
    val folderCount = groupsFor(LibraryView.Folders, library.items).size
    val shortcuts =
        listOf(
            Triple(LibraryView.Albums, albumCount, "张"),
            Triple(LibraryView.Artists, artistCount, "位"),
            Triple(LibraryView.Songs, library.items.size, "首"),
            Triple(LibraryView.Folders, folderCount, "个"),
            Triple(LibraryView.Playlists, library.config.playlists.size, "个"),
        )

    Column {
        shortcuts.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                rowItems.forEach { (view, count, unit) ->
                    BrowseShortcut(
                        label = view.label,
                        detail = "$count $unit",
                        onClick = { onOpenView(view) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            if (rowIndex < shortcuts.chunked(2).lastIndex) Hairline()
        }
    }
}

@Composable
private fun BrowseShortcut(
    label: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AppPrimaryText, fontSize = 15.sp)
        Text(detail, color = AppMutedText, fontSize = 11.sp)
    }
}

@Composable
private fun SourceStatusEntry(
    library: MediaLibraryState,
    onOpen: () -> Unit,
) {
    val warningCount = library.sources.count { !it.hasAccess || it.errorMessage != null }
    val activeCount =
        library.sources.count {
            it.kind != LibrarySourceKind.LooseFiles || it.itemCount > 0
        }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("内容来源", color = AppPrimaryText, fontSize = 14.sp)
            Text(
                if (warningCount == 0) "$activeCount 个来源" else "$warningCount 个来源需要处理",
                color = if (warningCount == 0) AppMutedText else AppError,
                fontSize = 11.sp,
            )
        }
        Text("管理", color = AppSecondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun PinnedRow(
    pin: ResolvedLibraryPin,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                pin.title,
                color = AppPrimaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(pin.detail, color = AppMutedText, fontSize = 11.sp)
        }
        TextButton(onClick = onPlay, enabled = pin.items.isNotEmpty()) {
            Text("播放", color = AppSecondaryText, fontSize = 11.sp)
        }
        TextButton(onClick = onRemove) {
            Text("取消", color = AppMutedText, fontSize = 11.sp)
        }
    }
    Hairline()
}

@Composable
private fun LibraryCategory(
    view: LibraryView,
    selectedGroupKey: String?,
    library: MediaLibraryState,
    onSelectedGroupChanged: (String?) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onCreateEmptyPlaylist: () -> Unit,
    onRemoveFromPlaylist: (String, MediaSourceRef) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onTogglePin: (LibraryPin) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val groups = remember(view, library.items) { groupsFor(view, library.items) }
    val selectedPlaylist =
        if (view == LibraryView.Playlists) {
            library.config.playlists.firstOrNull { it.id == selectedGroupKey }
        } else {
            null
        }
    val selectedGroupItems =
        when {
            selectedPlaylist != null -> selectedPlaylist.items
            selectedGroupKey != null ->
                groups.firstOrNull { it.first == selectedGroupKey }?.second.orEmpty()
            else -> emptyList()
        }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectedGroupKey != null) {
            val pin = pinForGroup(view, selectedGroupKey)
            val selectedGroupTitle = selectedPlaylist?.name ?: selectedGroupKey
            item(key = "group-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(selectedGroupTitle, "${selectedGroupItems.size} 首")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onPlayLibraryItems(selectedGroupItems, 0) },
                            enabled = selectedGroupItems.isNotEmpty(),
                        ) {
                            Text("播放全部", color = AppSecondaryText)
                        }
                        if (pin != null) {
                            TextButton(onClick = { onTogglePin(pin) }) {
                                Text(
                                    if (pin in library.config.pins) "取消固定" else "固定",
                                    color = AppMutedText,
                                )
                            }
                        }
                        if (selectedPlaylist != null) {
                            TextButton(
                                onClick = {
                                    onDeletePlaylist(selectedPlaylist.id)
                                    onSelectedGroupChanged(null)
                                },
                            ) {
                                Text("删除列表", color = AppMutedText)
                            }
                        }
                    }
                }
            }
            itemsIndexed(
                items = selectedGroupItems,
                key = { _, item -> "selected-${item.uri}" },
            ) { index, media ->
                TrackRow(
                    media = media,
                    onPlay = { onPlayLibraryItems(selectedGroupItems, index) },
                    onOpenMediaActions = { onOpenMediaActions(media) },
                    onRemove =
                        selectedPlaylist?.let { playlist ->
                            { onRemoveFromPlaylist(playlist.id, media) }
                        },
                )
            }
            if (selectedGroupItems.isEmpty()) {
                item(key = "selected-empty") { StatusText("这里还没有内容") }
            }
        } else {
            when (view) {
                LibraryView.Songs -> {
                    item(key = "songs-heading") {
                        SectionHeading("全部歌曲", "${library.items.size} 首")
                    }
                    itemsIndexed(
                        items = library.items,
                        key = { _, item -> "song-${item.uri}" },
                    ) { index, media ->
                        TrackRow(
                            media = media,
                            onPlay = { onPlayLibraryItems(library.items, index) },
                            onOpenMediaActions = { onOpenMediaActions(media) },
                        )
                    }
                }
                LibraryView.Playlists -> {
                    item(key = "playlist-heading") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionHeading("播放列表", "${library.config.playlists.size} 个")
                        }
                    }
                    item(key = "new-playlist") {
                        QuietButton(
                            label = "新建播放列表",
                            onClick = onCreateEmptyPlaylist,
                            emphasized = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(
                        items = library.config.playlists,
                        key = { "playlist-${it.id}" },
                    ) { playlist ->
                        val pin = LibraryPin(LibraryPinType.Playlist, playlist.id)
                        GroupRow(
                            name = playlist.name,
                            count = playlist.items.size,
                            isPinned = pin in library.config.pins,
                            onOpen = { onSelectedGroupChanged(playlist.id) },
                            onPlay = {
                                if (playlist.items.isNotEmpty()) {
                                    onPlayLibraryItems(playlist.items, 0)
                                }
                            },
                            onTogglePin = { onTogglePin(pin) },
                        )
                    }
                }
                else -> {
                    item(key = "group-list-heading") {
                        SectionHeading(view.label, "${groups.size} 项")
                    }
                    items(
                        items = groups,
                        key = { "group-${view.name}-${it.first}" },
                    ) { group ->
                        val pin = pinForGroup(view, group.first)
                        GroupRow(
                            name = group.first,
                            count = group.second.size,
                            isPinned = pin != null && pin in library.config.pins,
                            onOpen = { onSelectedGroupChanged(group.first) },
                            onPlay = { onPlayLibraryItems(group.second, 0) },
                            onTogglePin = pin?.let { { onTogglePin(it) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySearch(
    query: String,
    onQueryChanged: (String) -> Unit,
    library: MediaLibraryState,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onOpenGroup: (LibraryView, String) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val normalized = query.trim()
    val matchedTracks =
        if (normalized.isEmpty()) {
            emptyList()
        } else {
            library.items.filter { media -> media.matches(normalized) }
        }
    val matchedAlbums = matchingGroups(LibraryView.Albums, library.items, normalized)
    val matchedArtists = matchingGroups(LibraryView.Artists, library.items, normalized)
    val matchedFolders = matchingGroups(LibraryView.Folders, library.items, normalized)
    val matchedPlaylists =
        if (normalized.isEmpty()) emptyList()
        else library.config.playlists.filter { it.name.contains(normalized, ignoreCase = true) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "search-field") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("标题、歌手、专辑、文件夹或播放列表", color = AppMutedText) },
                singleLine = true,
            )
        }
        if (normalized.isEmpty()) {
            item(key = "search-hint") { StatusText("输入内容开始搜索") }
        } else {
            if (matchedTracks.isNotEmpty()) {
                item(key = "search-songs-heading") {
                    SectionHeading("歌曲", "${matchedTracks.size} 首")
                }
                itemsIndexed(
                    items = matchedTracks.take(8),
                    key = { _, item -> "search-song-${item.uri}" },
                ) { index, media ->
                    TrackRow(
                        media = media,
                        onPlay = { onPlayLibraryItems(matchedTracks, index) },
                        onOpenMediaActions = { onOpenMediaActions(media) },
                    )
                }
            }
            SearchGroups("专辑", LibraryView.Albums, matchedAlbums, onOpenGroup)
            SearchGroups("歌手", LibraryView.Artists, matchedArtists, onOpenGroup)
            SearchGroups("文件夹", LibraryView.Folders, matchedFolders, onOpenGroup)
            if (matchedPlaylists.isNotEmpty()) {
                item(key = "search-playlists-heading") {
                    SectionHeading("播放列表", "${matchedPlaylists.size} 个")
                }
                items(matchedPlaylists.take(6), key = { "search-playlist-${it.id}" }) { playlist ->
                    GroupRow(
                        name = playlist.name,
                        count = playlist.items.size,
                        onOpen = { onOpenGroup(LibraryView.Playlists, playlist.id) },
                        onPlay = {
                            if (playlist.items.isNotEmpty()) onPlayLibraryItems(playlist.items, 0)
                        },
                    )
                }
            }
            if (
                matchedTracks.isEmpty() && matchedAlbums.isEmpty() && matchedArtists.isEmpty() &&
                    matchedFolders.isEmpty() && matchedPlaylists.isEmpty()
            ) {
                item(key = "search-empty") { StatusText("没有找到相关内容") }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.SearchGroups(
    title: String,
    view: LibraryView,
    groups: List<Pair<String, List<MediaSourceRef>>>,
    onOpenGroup: (LibraryView, String) -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "search-${view.name}-heading") { SectionHeading(title, "${groups.size} 项") }
    items(groups.take(6), key = { "search-${view.name}-${it.first}" }) { group ->
        GroupRow(
            name = group.first,
            count = group.second.size,
            onOpen = { onOpenGroup(view, group.first) },
            onPlay = {},
            showPlay = false,
        )
    }
}

@Composable
private fun ContentSources(
    library: MediaLibraryState,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onClearLooseFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "source-intro") {
            Text(
                "资料库会合并这些来源；移除来源不会删除原文件。",
                color = AppSecondaryText,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
        if (library.isLoading) {
            item(key = "source-loading") { StatusText("正在同步内容来源…") }
        }
        items(
            items =
                library.sources.filter {
                    it.kind != LibrarySourceKind.LooseFiles || it.itemCount > 0
                },
            key = { "source-${it.id}" },
        ) { source ->
            LibrarySourceRow(
                source = source,
                isLoading = library.isLoading,
                onPrimaryAction =
                    when (source.kind) {
                        LibrarySourceKind.SystemLibrary ->
                            if (source.hasAccess) onRefreshLibrary else onRequestLibraryAccess
                        LibrarySourceKind.Folder -> null
                        LibrarySourceKind.LooseFiles -> onClearLooseFiles
                    },
                primaryActionLabel =
                    when (source.kind) {
                        LibrarySourceKind.SystemLibrary ->
                            if (source.hasAccess) "刷新" else "授权"
                        LibrarySourceKind.Folder -> null
                        LibrarySourceKind.LooseFiles -> "清空记录"
                    },
                onRemove =
                    if (source.kind == LibrarySourceKind.Folder) {
                        { onRemoveFolder(source.id) }
                    } else {
                        null
                    },
            )
        }
        item(key = "source-actions-divider") { Hairline() }
        item(key = "source-actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton(
                    label = if (library.hasSystemAccess) "重新扫描设备" else "扫描设备音乐",
                    onClick = onRequestLibraryAccess,
                    modifier = Modifier.fillMaxWidth(),
                    emphasized = !library.hasSystemAccess,
                )
                QuietButton("添加文件夹", onAddFolder, Modifier.fillMaxWidth())
                TextButton(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) {
                    Text("打开单个文件", color = AppSecondaryText)
                }
            }
        }
    }
}

@Composable
private fun LibrarySourceRow(
    source: LibrarySourceState,
    isLoading: Boolean,
    onPrimaryAction: (() -> Unit)?,
    primaryActionLabel: String?,
    onRemove: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(source.displayName, color = AppPrimaryText, fontSize = 15.sp)
                Text(
                    when {
                        isLoading -> "正在扫描"
                        !source.hasAccess -> "需要授权"
                        source.errorMessage != null -> "暂时不可用"
                        else -> "${source.itemCount} 项"
                    },
                    color =
                        if (!source.hasAccess || source.errorMessage != null) AppError else AppMutedText,
                    fontSize = 11.sp,
                )
            }
            if (primaryActionLabel != null && onPrimaryAction != null) {
                TextButton(onClick = onPrimaryAction) {
                    Text(primaryActionLabel, color = AppSecondaryText, fontSize = 11.sp)
                }
            }
            if (onRemove != null) {
                TextButton(onClick = onRemove) {
                    Text("移除", color = AppMutedText, fontSize = 11.sp)
                }
            }
        }
        source.errorMessage?.let { StatusText(it, AppError) }
        Hairline()
    }
}

@Composable
private fun NowPlayingBar(
    session: ActiveSession,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    playback: PlaybackSnapshot,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val availability =
        if (activeSession != null) MediaAvailability.Ready else lastSession?.mediaAvailability
    Column(modifier = modifier.fillMaxWidth().background(Color.Black)) {
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp).clickable(onClick = onContinue),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    session.media.displayTitle,
                    color = AppPrimaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (availability) {
                        MediaAvailability.Checking -> "正在验证"
                        is MediaAvailability.Unavailable -> "需要重新定位"
                        else ->
                            session.media.displayArtist
                                ?: if (activeSession == null) "继续播放" else formatTime(playback.positionMs)
                    },
                    color =
                        if (availability is MediaAvailability.Unavailable) AppError else AppMutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = if (activeSession == null) onContinue else onPlayPause,
                enabled = availability != MediaAvailability.Checking,
            ) {
                Text(
                    when {
                        activeSession == null -> "继续"
                        playback.isPlaying -> "暂停"
                        else -> "播放"
                    },
                    color = AppAccent,
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    media: MediaSourceRef,
    onPlay: () -> Unit,
    onOpenMediaActions: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                media.displayTitle,
                color = AppPrimaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(media.displayArtist, media.album, media.folder).firstOrNull()
                    ?: sourceLabel(media.origin),
                color = AppMutedText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (media.durationMs > 0L) {
            Text(formatTime(media.durationMs), color = AppMutedText, fontSize = 11.sp)
        }
        TextButton(onClick = onRemove ?: onOpenMediaActions) {
            Text(if (onRemove == null) "操作" else "移除", color = AppMutedText, fontSize = 11.sp)
        }
    }
    Hairline()
}

@Composable
private fun GroupRow(
    name: String,
    count: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    showPlay: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name,
                color = AppPrimaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$count 项", color = AppMutedText, fontSize = 11.sp)
        }
        if (onTogglePin != null) {
            TextButton(onClick = onTogglePin) {
                Text(if (isPinned) "已固定" else "固定", color = AppMutedText, fontSize = 11.sp)
            }
        }
        if (showPlay) {
            TextButton(onClick = onPlay, enabled = count > 0) {
                Text("播放", color = if (count > 0) AppSecondaryText else AppMutedText)
            }
        }
    }
    Hairline()
}

@Composable
private fun ImportContentDialog(
    draft: SessionDraft,
    activeSession: ActiveSession?,
    onPickMedia: () -> Unit,
    onPickSubtitle: () -> Unit,
    onRemoveSubtitle: () -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开单个内容", color = AppPrimaryText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceRow(
                    label = "播放内容",
                    value =
                        when (val media = draft.media) {
                            DraftMedia.None -> "尚未选择"
                            is DraftMedia.Checking -> "正在验证 ${media.source.displayName}"
                            is DraftMedia.Ready -> media.source.displayTitle
                            is DraftMedia.Unavailable -> media.source.displayName
                        },
                    action = if (draft.media == DraftMedia.None) "选择" else "更换",
                    onClick = onPickMedia,
                )
                if (draft.media is DraftMedia.Unavailable) StatusText(draft.media.message, AppError)
                Hairline()
                SourceRow(
                    label = "同步字幕",
                    value =
                        when (val subtitle = draft.subtitle) {
                            DraftSubtitle.None -> "可选 · SRT / LRC / VTT / ASS"
                            is DraftSubtitle.Loading -> "正在读取 ${subtitle.source.displayName}"
                            is DraftSubtitle.Ready -> subtitle.subtitle.source.displayName
                            is DraftSubtitle.Failed -> subtitle.source.displayName
                        },
                    action = if (draft.subtitle == DraftSubtitle.None) "选择" else "更换",
                    onClick = onPickSubtitle,
                )
                when (val subtitle = draft.subtitle) {
                    is DraftSubtitle.Failed -> StatusText(subtitle.message, AppError)
                    is DraftSubtitle.Ready ->
                        StatusText("已读取 ${subtitle.subtitle.timeline.cues.size} 条字幕")
                    else -> Unit
                }
                if (draft.subtitle != DraftSubtitle.None) {
                    TextButton(onClick = onRemoveSubtitle) {
                        Text("移除字幕", color = AppMutedText)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClearDraft) { Text("清空", color = AppMutedText) }
                    QuietButton(
                        label =
                            when {
                                draft.media is DraftMedia.Checking -> "正在验证"
                                draft.media is DraftMedia.Unavailable -> "需要重新定位"
                                draft.subtitle is DraftSubtitle.Failed -> "忽略字幕并播放"
                                activeSession == null -> "开始播放"
                                else -> "应用并播放"
                            },
                        onClick = onApplyDraft,
                        enabled =
                            draft.media is DraftMedia.Ready &&
                                draft.subtitle !is DraftSubtitle.Loading,
                        modifier = Modifier.weight(1f),
                        emphasized = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = AppSecondaryText) }
        },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun PlaylistChooserDialog(
    item: MediaSourceRef,
    playlists: List<LibraryPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到播放列表", color = AppPrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.displayTitle,
                    color = AppSecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                playlists.forEach { playlist ->
                    TextButton(onClick = { onAdd(playlist.id) }) {
                        Text("${playlist.name} · ${playlist.items.size} 项", color = AppPrimaryText)
                    }
                }
                if (playlists.isEmpty()) StatusText("还没有播放列表")
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) { Text("新建播放列表", color = AppAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppSecondaryText) }
        },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun MediaActionDialog(
    item: MediaSourceRef,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onChoosePlaylist: () -> Unit,
    onTogglePin: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                item.displayTitle,
                color = AppPrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onPlayNext) { Text("下一项播放", color = AppPrimaryText) }
                TextButton(onClick = onAddToQueue) {
                    Text("添加到队列末尾", color = AppPrimaryText)
                }
                TextButton(onClick = onChoosePlaylist) {
                    Text("添加到播放列表", color = AppPrimaryText)
                }
                TextButton(onClick = onTogglePin) {
                    Text(if (isPinned) "取消固定" else "固定到资料库顶部", color = AppPrimaryText)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppSecondaryText) }
        },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun CreatePlaylistDialog(
    name: String,
    onNameChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建播放列表", color = AppPrimaryText) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChanged,
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) {
                Text("创建", color = if (name.isNotBlank()) AppAccent else AppMutedText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppSecondaryText) }
        },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun DisplaySettings(
    profile: DisplayProfile,
    onProfileChanged: (DisplayProfile) -> Unit,
) {
    GlowPreview(profile)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(title = "预设", detail = profile.name)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DisplayProfile.Presets.forEach { preset ->
                QuietButton(
                    label = preset.name,
                    onClick = { onProfileChanged(preset) },
                    modifier = Modifier.weight(1f),
                    emphasized = preset == profile,
                )
            }
        }
    }
    Hairline()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(title = "微光校准", detail = "窗口与发光强度")
        DisplaySettingSlider(
            label = "环境亮度",
            valueLabel = windowBrightnessLabel(profile.glowWindowBrightness),
            value = profile.glowWindowBrightness,
            valueRange = 0.01f..0.30f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(name = DisplayProfile.CustomName, glowWindowBrightness = it),
                )
            },
        )
        DisplaySettingSlider(
            label = "文字亮度",
            valueLabel = luminanceLabel(profile.glowSubtitleLevel, 4, 96),
            value = profile.glowSubtitleLevel.toFloat(),
            valueRange = 4f..96f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(name = DisplayProfile.CustomName, glowSubtitleLevel = it.roundToInt()),
                )
            },
        )
        DisplaySettingSlider(
            label = "控制亮度",
            valueLabel = luminanceLabel(profile.glowControlLevel, 24, 160),
            value = profile.glowControlLevel.toFloat(),
            valueRange = 24f..160f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(name = DisplayProfile.CustomName, glowControlLevel = it.roundToInt()),
                )
            },
        )
    }
    Hairline()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(title = "字幕外观", detail = "独立于屏幕亮度")
        DisplaySettingSlider(
            label = "字号",
            valueLabel = subtitleSizeLabel(profile.glowSubtitleSizeSp),
            value = profile.glowSubtitleSizeSp,
            valueRange = 18f..36f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(name = DisplayProfile.CustomName, glowSubtitleSizeSp = it),
                )
            },
        )
        DisplaySettingSlider(
            label = "垂直位置",
            valueLabel = verticalPositionLabel(profile.glowSubtitleVerticalBias),
            value = profile.glowSubtitleVerticalBias,
            valueRange = -0.60f..0.60f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(name = DisplayProfile.CustomName, glowSubtitleVerticalBias = it),
                )
            },
        )
    }
    Hairline()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(title = "微光交互", detail = "锁定态")
        Text(
            "左右区域双击快退或快进，中央双击播放或暂停，长按任意位置显示临时控制。普通单击不会打断沉浸。",
            color = AppSecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun GlowPreview(profile: DisplayProfile) {
    val subtitleLevel = profile.glowSubtitleLevel.coerceIn(0, 255)
    val controlLevel = profile.glowControlLevel.coerceIn(0, 255)
    Box(modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.Black)) {
        Text(
            "声音继续，画面退场",
            modifier =
                Modifier
                    .align(
                        BiasAlignment(
                            0f,
                            profile.glowSubtitleVerticalBias.coerceIn(-0.60f, 0.60f),
                        ),
                    )
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            color = Color(subtitleLevel, subtitleLevel, subtitleLevel),
            fontSize = profile.glowSubtitleSizeSp.coerceIn(18f, 36f).sp,
            lineHeight = (profile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 1.4f).sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "长按解锁",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            color = Color(controlLevel, controlLevel, controlLevel),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SectionHeading(
    title: String,
    detail: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = AppPrimaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        detail?.let { Text(it, color = AppMutedText, fontSize = 11.sp) }
    }
}

@Composable
private fun SourceRow(
    label: String,
    value: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = AppMutedText, fontSize = 11.sp)
            Text(
                value,
                color = AppPrimaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onClick) { Text(action, color = AppSecondaryText) }
    }
}

@Composable
private fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, if (emphasized) AppAccent else AppHairline),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = if (emphasized) AppAccent else AppPrimaryText,
                disabledContentColor = AppMutedText,
            ),
    ) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun DisplaySettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = AppSecondaryText, fontSize = 12.sp)
            Text(valueLabel, color = AppPrimaryText, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.semantics { contentDescription = "$label，$valueLabel" },
            colors =
                SliderDefaults.colors(
                    thumbColor = AppAccent,
                    activeTrackColor = AppSecondaryText,
                    inactiveTrackColor = AppHairline,
                ),
        )
    }
}

@Composable
private fun Hairline() {
    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppHairline))
}

@Composable
private fun StatusText(
    text: String,
    color: Color = AppMutedText,
) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 16.sp)
}

private fun groupsFor(
    view: LibraryView,
    items: List<MediaSourceRef>,
): List<Pair<String, List<MediaSourceRef>>> {
    val grouped =
        when (view) {
            LibraryView.Albums -> items.groupBy { it.album ?: "未知专辑" }
            LibraryView.Artists -> items.groupBy { it.displayArtist ?: "未知歌手" }
            LibraryView.Folders -> items.groupBy { it.folder ?: "未分类文件夹" }
            else -> emptyMap()
        }
    return grouped.entries
        .sortedBy { it.key.lowercase() }
        .map { entry -> entry.key to entry.value.sortedBy { media -> media.displayTitle.lowercase() } }
}

private fun matchingGroups(
    view: LibraryView,
    items: List<MediaSourceRef>,
    query: String,
): List<Pair<String, List<MediaSourceRef>>> =
    if (query.isEmpty()) emptyList()
    else groupsFor(view, items).filter { it.first.contains(query, ignoreCase = true) }

private fun MediaSourceRef.matches(query: String): Boolean =
    listOf(displayTitle, displayArtist, album, folder, displayName)
        .any { value -> value?.contains(query, ignoreCase = true) == true }

private fun pinForGroup(
    view: LibraryView,
    key: String,
): LibraryPin? =
    when (view) {
        LibraryView.Albums -> LibraryPin(LibraryPinType.Album, key)
        LibraryView.Artists -> LibraryPin(LibraryPinType.Artist, key)
        LibraryView.Folders -> LibraryPin(LibraryPinType.Folder, key)
        LibraryView.Playlists -> LibraryPin(LibraryPinType.Playlist, key)
        LibraryView.Songs -> null
    }

private fun resolvePins(library: MediaLibraryState): List<ResolvedLibraryPin> {
    val albums = groupsFor(LibraryView.Albums, library.items).toMap()
    val artists = groupsFor(LibraryView.Artists, library.items).toMap()
    val folders = groupsFor(LibraryView.Folders, library.items).toMap()
    return library.config.pins.mapNotNull { pin ->
        when (pin.type) {
            LibraryPinType.Track ->
                library.items.firstOrNull { it.uri.toString() == pin.key }?.let { media ->
                    ResolvedLibraryPin(
                        pin = pin,
                        title = media.displayTitle,
                        detail = listOfNotNull("歌曲", media.displayArtist).joinToString(" · "),
                        items = listOf(media),
                    )
                }
            LibraryPinType.Album ->
                albums[pin.key]?.let {
                    ResolvedLibraryPin(pin, pin.key, "专辑 · ${it.size} 首", it, LibraryView.Albums, pin.key)
                }
            LibraryPinType.Artist ->
                artists[pin.key]?.let {
                    ResolvedLibraryPin(pin, pin.key, "歌手 · ${it.size} 首", it, LibraryView.Artists, pin.key)
                }
            LibraryPinType.Folder ->
                folders[pin.key]?.let {
                    ResolvedLibraryPin(pin, pin.key, "文件夹 · ${it.size} 首", it, LibraryView.Folders, pin.key)
                }
            LibraryPinType.Playlist ->
                library.config.playlists.firstOrNull { it.id == pin.key }?.let { playlist ->
                    ResolvedLibraryPin(
                        pin,
                        playlist.name,
                        "播放列表 · ${playlist.items.size} 首",
                        playlist.items,
                        LibraryView.Playlists,
                        playlist.id,
                    )
                }
        }
    }
}

private fun sourceLabel(origin: MediaOrigin): String =
    when (origin) {
        MediaOrigin.MediaStore -> "设备媒体库"
        MediaOrigin.OpenDocument -> "单独打开"
        MediaOrigin.DocumentTree -> "授权文件夹"
    }

private fun windowBrightnessLabel(value: Float): String =
    when {
        value < 0.055f -> "极暗"
        value < 0.11f -> "柔和"
        value < 0.19f -> "清晰"
        else -> "明亮"
    }

private fun luminanceLabel(
    value: Int,
    minimum: Int,
    maximum: Int,
): String {
    val ratio = (value - minimum).toFloat() / (maximum - minimum).toFloat()
    return when {
        ratio < 0.25f -> "极暗"
        ratio < 0.55f -> "柔和"
        ratio < 0.82f -> "清晰"
        else -> "明亮"
    }
}

private fun subtitleSizeLabel(value: Float): String =
    when {
        value < 22f -> "紧凑"
        value < 28f -> "标准"
        value < 33f -> "醒目"
        else -> "大字"
    }

private fun verticalPositionLabel(bias: Float): String {
    val percent = (bias.coerceIn(-0.60f, 0.60f) * 100).roundToInt()
    return when {
        percent.absoluteValue < 5 -> "中央"
        percent < 0 -> "上移 ${percent.absoluteValue}%"
        else -> "下移 $percent%"
    }
}
