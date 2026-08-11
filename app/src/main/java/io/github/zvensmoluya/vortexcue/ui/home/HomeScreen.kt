package io.github.zvensmoluya.vortexcue.ui.home

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.library.*
import io.github.zvensmoluya.vortexcue.ui.player.PlaybackSnapshot
import io.github.zvensmoluya.vortexcue.ui.player.formatTime
import io.github.zvensmoluya.vortexcue.ui.theme.*

internal enum class HomeSection {
    Library,
    Manage,
    Display,
}

private enum class ManagerPage(val title: String) {
    Overview("内容管理"),
    Review("待整理"),
    Included("已收录"),
    Ignored("已忽略"),
    Sources("扫描范围"),
}

private data class ResolvedCollection(
    val shortcut: LibraryShortcut,
    val name: String,
    val items: List<MediaSourceRef>,
    val isPlaylist: Boolean = false,
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
    openImportRequested: Boolean,
    onImportRequestConsumed: () -> Unit,
    onPickMedia: () -> Unit,
    onPickSubtitle: (TextTrackSlot) -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onClearLooseFiles: () -> Unit,
    onAcceptCandidates: (Set<String>) -> Unit,
    onIgnoreCandidates: (Set<String>) -> Unit,
    onRemoveIncluded: (Set<String>) -> Unit,
    onRestoreIgnored: (Set<String>) -> Unit,
    onToggleHomeShortcut: (LibraryShortcut) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onPlayNext: (MediaSourceRef) -> Unit,
    onAddToQueue: (MediaSourceRef) -> Unit,
    onCreatePlaylist: (String, MediaSourceRef?) -> Unit,
    onAddToPlaylist: (String, MediaSourceRef) -> Unit,
    onRemoveFromPlaylist: (String, MediaSourceRef) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveSubtitle: (TextTrackSlot) -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedShortcut by remember { mutableStateOf<LibraryShortcut?>(null) }
    var managerPage by rememberSaveable { mutableStateOf(ManagerPage.Overview) }
    var reviewSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importVisible by rememberSaveable { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var mediaActionTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var createPlaylistVisible by rememberSaveable { mutableStateOf(false) }
    var createPlaylistName by rememberSaveable { mutableStateOf("") }
    var createPlaylistSeed by remember { mutableStateOf<MediaSourceRef?>(null) }
    var folderRemovalTarget by remember { mutableStateOf<LibraryFolderRef?>(null) }
    var clearLooseFilesConfirmation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(section) {
        if (section != HomeSection.Library) {
            searchVisible = false
            selectedShortcut = null
        }
        if (section != HomeSection.Manage) managerPage = ManagerPage.Overview
    }
    LaunchedEffect(openImportRequested) {
        if (openImportRequested) {
            importVisible = true
            onImportRequestConsumed()
        }
    }
    LaunchedEffect(managerPage) {
        if (managerPage == ManagerPage.Review) {
            reviewSelection =
                library.candidates
                    .filter { it.kind == MediaKind.Audio }
                    .mapTo(linkedSetOf()) { it.uri.toString() }
        }
    }

    val resumableSession = activeSession ?: lastSession?.session
    val miniPlayerSession =
        resumableSession?.takeIf {
            section == HomeSection.Library && (searchVisible || selectedShortcut != null)
        }
    val showMiniPlayer = miniPlayerSession != null
    val selectedCollection =
        selectedShortcut?.let { shortcut -> resolveCollection(shortcut, library) }

    BackHandler(
        enabled =
            (section == HomeSection.Library && (searchVisible || selectedShortcut != null)) ||
                (section == HomeSection.Manage && managerPage != ManagerPage.Overview),
    ) {
        when {
            section == HomeSection.Library && selectedShortcut != null -> selectedShortcut = null
            section == HomeSection.Library && searchVisible -> {
                searchVisible = false
                searchQuery = ""
            }
            section == HomeSection.Manage -> managerPage = ManagerPage.Overview
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
            searchVisible = searchVisible,
            selectedCollection = selectedCollection,
            managerPage = managerPage,
            onBack = {
                when {
                    section == HomeSection.Library && selectedShortcut != null ->
                        selectedShortcut = null
                    section == HomeSection.Library && searchVisible -> {
                        searchVisible = false
                        searchQuery = ""
                    }
                    section == HomeSection.Manage && managerPage != ManagerPage.Overview ->
                        managerPage = ManagerPage.Overview
                    section != HomeSection.Library -> onSectionChanged(HomeSection.Library)
                }
            },
            onSearch = {
                selectedShortcut = null
                searchVisible = true
            },
            onManage = { onSectionChanged(HomeSection.Manage) },
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
                                onOpenCollection = {
                                    searchVisible = false
                                    searchQuery = ""
                                    selectedShortcut = it
                                },
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                bottomPadding = if (showMiniPlayer) 88.dp else 8.dp,
                                modifier = Modifier.fillMaxSize(),
                            )
                        selectedCollection != null ->
                            CollectionDetail(
                                collection = selectedCollection,
                                library = library,
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                onRemoveFromPlaylist = onRemoveFromPlaylist,
                                onDeletePlaylist = { playlistId ->
                                    onDeletePlaylist(playlistId)
                                    selectedShortcut = null
                                },
                                bottomPadding = if (showMiniPlayer) 88.dp else 8.dp,
                                modifier = Modifier.fillMaxSize(),
                            )
                        else ->
                            LibraryLanding(
                                library = library,
                                resumableSession = resumableSession,
                                activeSession = activeSession,
                                lastSession = lastSession,
                                playback = playback,
                                onOpenCollection = { selectedShortcut = it },
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenManage = { page ->
                                    managerPage = page
                                    onSectionChanged(HomeSection.Manage)
                                },
                                onAddFolder = onAddFolder,
                                onOpenFile = {
                                    importVisible = true
                                    onPickMedia()
                                },
                                onContinue = onContinue,
                                onPlayPause = onPlayPause,
                                bottomPadding = 8.dp,
                                modifier = Modifier.fillMaxSize(),
                            )
                    }

                    miniPlayerSession?.let { session ->
                        NowPlayingBar(
                            session = session,
                            activeSession = activeSession,
                            lastSession = lastSession,
                            libraryConfig = library.config,
                            playback = playback,
                            onContinue = onContinue,
                            onPlayPause = onPlayPause,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            HomeSection.Manage ->
                when (managerPage) {
                    ManagerPage.Overview ->
                        ContentManagerOverview(
                            library = library,
                            onOpenPage = { managerPage = it },
                            onToggleHomeShortcut = onToggleHomeShortcut,
                            onRequestLibraryAccess = onRequestLibraryAccess,
                            onRefreshLibrary = onRefreshLibrary,
                            onAddFolder = onAddFolder,
                            onOpenFile = {
                                importVisible = true
                                onPickMedia()
                            },
                            onCreatePlaylist = {
                                createPlaylistSeed = null
                                createPlaylistName = ""
                                createPlaylistVisible = true
                            },
                            modifier = Modifier.weight(1f),
                        )
                    ManagerPage.Review ->
                        CandidateReview(
                            library = library,
                            selectedUris = reviewSelection,
                            onSelectionChanged = { reviewSelection = it },
                            onAccept = {
                                onAcceptCandidates(reviewSelection)
                                managerPage = ManagerPage.Overview
                            },
                            onIgnore = { uri ->
                                reviewSelection -= uri
                                onIgnoreCandidates(setOf(uri))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    ManagerPage.Included ->
                        IncludedContent(
                            items = library.items,
                            onRemove = { onRemoveIncluded(setOf(it.uri.toString())) },
                            modifier = Modifier.weight(1f),
                        )
                    ManagerPage.Ignored ->
                        IgnoredContent(
                            items = library.ignoredItems,
                            onRestore = { onRestoreIgnored(setOf(it.uri.toString())) },
                            modifier = Modifier.weight(1f),
                        )
                    ManagerPage.Sources ->
                        ScanSources(
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
                }
            HomeSection.Display ->
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    DisplaySettings(displayProfile, onDisplayProfileChanged)
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
        MediaActionDialog(
            item = item,
            canRemoveFromLibrary = library.config.isCollected(item),
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
            onRemoveFromLibrary = {
                onRemoveIncluded(setOf(item.uri.toString()))
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
            title = { Text("移除扫描范围？", color = AppPrimaryText) },
            text = {
                Text(
                    "将停止发现“${folder.displayName}”中的内容，不会删除原文件。",
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
            text = { Text("只移除应用内记录，不会删除原文件。", color = AppSecondaryText) },
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
    searchVisible: Boolean,
    selectedCollection: ResolvedCollection?,
    managerPage: ManagerPage,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onManage: () -> Unit,
    onDisplay: () -> Unit,
) {
    val isRoot =
        section == HomeSection.Library && !searchVisible && selectedCollection == null
    val title =
        when {
            searchVisible -> "搜索"
            selectedCollection != null -> selectedCollection.name
            section == HomeSection.Manage -> managerPage.title
            section == HomeSection.Display -> "显示"
            else -> ""
        }
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRoot) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(end = 12.dp)) {
                    Text("返回", color = AppSecondaryText, fontSize = 12.sp)
                }
                Text(
                    title,
                    color = AppPrimaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            isRoot -> {
                TextButton(onClick = onSearch) {
                    Text("搜索", color = AppSecondaryText, fontSize = 12.sp)
                }
                TextButton(onClick = onManage) {
                    Text("更多来源", color = AppSecondaryText, fontSize = 12.sp)
                }
            }
            section == HomeSection.Manage && managerPage == ManagerPage.Overview ->
                TextButton(onClick = onDisplay) {
                    Text("显示", color = AppSecondaryText, fontSize = 12.sp)
                }
        }
    }
}

@Composable
private fun LibraryLanding(
    library: MediaLibraryState,
    resumableSession: ActiveSession?,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    playback: PlaybackSnapshot,
    onOpenCollection: (LibraryShortcut) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenManage: (ManagerPage) -> Unit,
    onAddFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val collections = remember(library.items, library.config) { resolveHomeCollections(library) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        resumableSession?.let { session ->
            item(key = "continue") {
                ContinueSection(
                    session = session,
                    activeSession = activeSession,
                    lastSession = lastSession,
                    libraryConfig = library.config,
                    playback = playback,
                    onContinue = onContinue,
                    onPlayPause = onPlayPause,
                )
            }
        }
        item(key = "intent-actions") {
            HomeIntentActions(
                onOpenFile = onOpenFile,
                onAddFolder = onAddFolder,
                onOpenMoreSources = { onOpenManage(ManagerPage.Overview) },
            )
        }
        if (library.isLoading) item(key = "loading") { StatusText("更多来源正在扫描…") }
        library.errorMessage?.let { message ->
            item(key = "error") { StatusText(message, AppError) }
        }
        if (collections.isNotEmpty()) {
            item(key = "collections-heading") { SectionHeading("已整理内容") }
            items(collections, key = { "home-${it.shortcut.type}-${it.shortcut.key}" }) {
                collection ->
                CollectionRow(
                    collection = collection,
                    onOpen = { onOpenCollection(collection.shortcut) },
                    onPlay = {
                        if (collection.items.isNotEmpty()) {
                            onPlayLibraryItems(collection.items, 0)
                        }
                    },
                )
            }
        }
        if (library.candidates.isNotEmpty()) {
            item(key = "pending") {
                ManagerEntry(
                    title = "待整理",
                    detail = "${library.candidates.size} 项来自扫描或文件夹的内容",
                    action = "查看",
                    onClick = { onOpenManage(ManagerPage.Review) },
                )
            }
        }
    }
}

@Composable
private fun HomeIntentActions(
    onOpenFile: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenMoreSources: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        QuietButton("打开内容", onOpenFile, Modifier.fillMaxWidth(), emphasized = true)
        QuietButton("选择文件夹", onAddFolder, Modifier.fillMaxWidth())
        TextButton(onClick = onOpenMoreSources, modifier = Modifier.fillMaxWidth()) {
            Text("更多来源", color = AppSecondaryText)
        }
    }
}

@Composable
private fun CollectionRow(
    collection: ResolvedCollection,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                collection.name,
                color = AppPrimaryText,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(collectionDetail(collection.items), color = AppMutedText, fontSize = 11.sp)
        }
        TextButton(onClick = onPlay, enabled = collection.items.isNotEmpty()) {
            Text("播放", color = AppSecondaryText, fontSize = 12.sp)
        }
    }
    Hairline()
}

@Composable
private fun CollectionDetail(
    collection: ResolvedCollection,
    library: MediaLibraryState,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onRemoveFromPlaylist: (String, MediaSourceRef) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "collection-actions") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(collectionDetail(collection.items), color = AppMutedText, fontSize = 12.sp)
                Row {
                    TextButton(
                        onClick = { onPlayLibraryItems(collection.items, 0) },
                        enabled = collection.items.isNotEmpty(),
                    ) {
                        Text("播放全部", color = AppSecondaryText, fontSize = 12.sp)
                    }
                    if (collection.isPlaylist) {
                        TextButton(onClick = { onDeletePlaylist(collection.shortcut.key) }) {
                            Text("删除", color = AppMutedText, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        itemsIndexed(
            items = collection.items,
            key = { _, item -> "collection-${item.uri}" },
        ) { index, media ->
            TrackRow(
                media = media,
                hasSubtitle = library.hasSubtitleFor(media),
                onPlay = { onPlayLibraryItems(collection.items, index) },
                onOpenMediaActions = { onOpenMediaActions(media) },
                onRemove =
                    if (collection.isPlaylist) {
                        { onRemoveFromPlaylist(collection.shortcut.key, media) }
                    } else {
                        null
                    },
            )
        }
        if (collection.items.isEmpty()) item { StatusText("这里还没有内容") }
    }
}

@Composable
private fun LibrarySearch(
    query: String,
    onQueryChanged: (String) -> Unit,
    library: MediaLibraryState,
    onOpenCollection: (LibraryShortcut) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val normalized = query.trim()
    val matches =
        if (normalized.isEmpty()) emptyList()
        else library.items.filter { it.matches(normalized) }
    val collections =
        if (normalized.isEmpty()) emptyList()
        else availableCollections(library).filter { it.name.contains(normalized, ignoreCase = true) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search-field") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("标题、文件名、文件夹或播放列表", color = AppMutedText) },
                singleLine = true,
            )
        }
        if (normalized.isEmpty()) {
            item { StatusText("输入内容开始搜索") }
        } else {
            if (collections.isNotEmpty()) {
                item { SectionHeading("内容集", "${collections.size} 个") }
                items(collections.take(8), key = { "search-${it.shortcut.type}-${it.shortcut.key}" }) {
                    collection ->
                    CollectionRow(
                        collection = collection,
                        onOpen = { onOpenCollection(collection.shortcut) },
                        onPlay = {
                            if (collection.items.isNotEmpty()) {
                                onPlayLibraryItems(collection.items, 0)
                            }
                        },
                    )
                }
            }
            if (matches.isNotEmpty()) {
                item { SectionHeading("内容", "${matches.size} 项") }
                itemsIndexed(matches, key = { _, item -> "search-${item.uri}" }) { index, media ->
                    TrackRow(
                        media = media,
                        hasSubtitle = library.hasSubtitleFor(media),
                        onPlay = { onPlayLibraryItems(matches, index) },
                        onOpenMediaActions = { onOpenMediaActions(media) },
                    )
                }
            }
            if (collections.isEmpty() && matches.isEmpty()) item { StatusText("没有找到相关内容") }
        }
    }
}

@Composable
private fun ContentManagerOverview(
    library: MediaLibraryState,
    onOpenPage: (ManagerPage) -> Unit,
    onToggleHomeShortcut: (LibraryShortcut) -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val collections = remember(library.items, library.config) { availableCollections(library) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (library.candidates.isNotEmpty()) {
            item(key = "pending") {
                val videoCount = library.candidates.count { it.kind == MediaKind.Video }
                ManagerEntry(
                    title = "待整理",
                    detail =
                        "${library.candidates.size} 项新内容" +
                            if (videoCount > 0) " · $videoCount 项视频默认未选" else "",
                    action = "选择",
                    emphasized = true,
                    onClick = { onOpenPage(ManagerPage.Review) },
                )
            }
        }
        item(key = "home-heading") { SectionHeading("首页内容", "选择快捷入口") }
        if (collections.isEmpty()) {
            item(key = "home-empty") { StatusText("收录内容后，可以在这里选择首页入口。") }
        } else {
            items(collections, key = { "shortcut-${it.shortcut.type}-${it.shortcut.key}" }) {
                collection ->
                val visible = collection.shortcut in library.config.homeShortcuts
                ShortcutSelectionRow(
                    collection = collection,
                    visible = visible,
                    onToggle = { onToggleHomeShortcut(collection.shortcut) },
                )
            }
        }
        item(key = "library-heading") { SectionHeading("内容", "${library.items.size} 项") }
        item(key = "included-entry") {
            ManagerEntry(
                title = "已收录",
                detail = collectionDetail(library.items),
                action = "查看",
                onClick = { onOpenPage(ManagerPage.Included) },
            )
        }
        if (library.ignoredItems.isNotEmpty()) {
            item(key = "ignored-entry") {
                ManagerEntry(
                    title = "已忽略",
                    detail = "${library.ignoredItems.size} 项不会再次出现在待整理中",
                    action = "查看",
                    onClick = { onOpenPage(ManagerPage.Ignored) },
                )
            }
        }
        item(key = "add-heading") { SectionHeading("发现内容") }
        item(key = "add-actions") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                QuietButton(
                    label =
                        when {
                            !library.hasSystemAccess -> "扫描本机"
                            library.hasAudioAccess &&
                                library.videoAccess == VideoLibraryAccess.Partial ->
                                "调整视频范围"
                            !library.hasCompleteSystemAccess -> "补充本机授权"
                            else -> "重新扫描本机"
                        },
                    onClick =
                        if (library.hasCompleteSystemAccess) onRefreshLibrary
                        else onRequestLibraryAccess,
                    modifier = Modifier.fillMaxWidth(),
                )
                QuietButton("选择文件夹", onAddFolder, Modifier.fillMaxWidth())
                TextButton(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) {
                    Text("打开单个文件", color = AppSecondaryText)
                }
            }
        }
        item(key = "tools-heading") { SectionHeading("更多") }
        item(key = "playlist-entry") {
            ManagerEntry(
                title = "新建播放列表",
                detail = "手动组织一组内容",
                action = "新建",
                onClick = onCreatePlaylist,
            )
        }
        item(key = "sources-entry") {
            ManagerEntry(
                title = "扫描范围",
                detail = "授权、文件夹和单独打开记录",
                action = "管理",
                onClick = { onOpenPage(ManagerPage.Sources) },
            )
        }
    }
}

@Composable
private fun ManagerEntry(
    title: String,
    detail: String,
    action: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = AppPrimaryText, fontSize = 15.sp)
            Text(detail, color = if (emphasized) AppSecondaryText else AppMutedText, fontSize = 11.sp)
        }
        Text(action, color = if (emphasized) AppAccent else AppSecondaryText, fontSize = 12.sp)
    }
    Hairline()
}

@Composable
private fun ShortcutSelectionRow(
    collection: ResolvedCollection,
    visible: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(collection.name, color = AppPrimaryText, fontSize = 14.sp)
            Text(collectionDetail(collection.items), color = AppMutedText, fontSize = 11.sp)
        }
        Text(if (visible) "首页显示" else "已隐藏", color = if (visible) AppAccent else AppMutedText, fontSize = 11.sp)
    }
    Hairline()
}

@Composable
private fun CandidateReview(
    library: MediaLibraryState,
    selectedUris: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onAccept: () -> Unit,
    onIgnore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups =
        remember(library.candidates) {
            library.candidates
                .groupBy(MediaSourceRef::libraryFolderKey)
                .values
                .sortedBy { it.firstOrNull()?.libraryFolderName.orEmpty().lowercase() }
        }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "音频已预选；视频需要逐项确认。未选内容会继续留在这里。",
            color = AppSecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { group ->
                val groupUris = group.map { it.uri.toString() }.toSet()
                item(key = "candidate-group-${group.first().libraryFolderKey()}") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeading(group.first().libraryFolderName, "${group.size} 项")
                    }
                    Row {
                        TextButton(
                            onClick = {
                                val audioUris =
                                    group.filter { it.kind == MediaKind.Audio }
                                        .mapTo(linkedSetOf()) { it.uri.toString() }
                                onSelectionChanged(selectedUris + audioUris)
                            },
                        ) {
                            Text("选择音频", color = AppSecondaryText, fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = {
                                if (groupUris.all(selectedUris::contains)) {
                                    onSelectionChanged(selectedUris - groupUris)
                                } else {
                                    onSelectionChanged(selectedUris + groupUris)
                                }
                            },
                        ) {
                            Text(
                                if (groupUris.all(selectedUris::contains)) "取消全选" else "全选",
                                color = AppMutedText,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                items(group, key = { "candidate-${it.uri}" }) { media ->
                    val uriValue = media.uri.toString()
                    CandidateRow(
                        media = media,
                        selected = uriValue in selectedUris,
                        hasSubtitle = library.hasSuggestedSubtitleFor(media),
                        onToggle = {
                            onSelectionChanged(
                                if (uriValue in selectedUris) selectedUris - uriValue
                                else selectedUris + uriValue,
                            )
                        },
                        onIgnore = { onIgnore(uriValue) },
                    )
                }
            }
        }
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 ${selectedUris.size} 项", color = AppSecondaryText, fontSize = 12.sp)
            QuietButton(
                label = "收录所选",
                onClick = onAccept,
                enabled = selectedUris.isNotEmpty(),
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CandidateRow(
    media: MediaSourceRef,
    selected: Boolean,
    hasSubtitle: Boolean,
    onToggle: () -> Unit,
    onIgnore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            Text(candidateDetail(media, hasSubtitle), color = AppMutedText, fontSize = 10.sp)
        }
        TextButton(onClick = onToggle) {
            Text(if (selected) "已选" else "选择", color = if (selected) AppAccent else AppSecondaryText, fontSize = 11.sp)
        }
        TextButton(onClick = onIgnore) {
            Text("忽略", color = AppMutedText, fontSize = 11.sp)
        }
    }
    Hairline()
}

@Composable
private fun IncludedContent(
    items: List<MediaSourceRef>,
    onRemove: (MediaSourceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    ManagedMediaList(
        items = items,
        emptyText = "还没有收录内容",
        action = "移除",
        onAction = onRemove,
        modifier = modifier,
    )
}

@Composable
private fun IgnoredContent(
    items: List<MediaSourceRef>,
    onRestore: (MediaSourceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    ManagedMediaList(
        items = items,
        emptyText = "没有已忽略内容",
        action = "恢复",
        onAction = onRestore,
        modifier = modifier,
    )
}

@Composable
private fun ManagedMediaList(
    items: List<MediaSourceRef>,
    emptyText: String,
    action: String,
    onAction: (MediaSourceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (items.isEmpty()) item { StatusText(emptyText) }
        items(items, key = { "managed-${it.uri}" }) { media ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(media.displayTitle, color = AppPrimaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(candidateDetail(media, false), color = AppMutedText, fontSize = 10.sp)
                }
                TextButton(onClick = { onAction(media) }) {
                    Text(action, color = AppSecondaryText, fontSize = 11.sp)
                }
            }
            Hairline()
        }
    }
}

@Composable
private fun ScanSources(
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "这里决定扫描哪里；扫描到的内容仍需在待整理中确认。",
                color = AppSecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        if (library.isLoading) item { StatusText("正在扫描…") }
        items(
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
                            if (library.hasCompleteSystemAccess) onRefreshLibrary
                            else onRequestLibraryAccess
                        LibrarySourceKind.Folder -> null
                        LibrarySourceKind.LooseFiles -> onClearLooseFiles
                    },
                primaryActionLabel =
                    when (source.kind) {
                        LibrarySourceKind.SystemLibrary ->
                            when {
                                library.hasCompleteSystemAccess -> "刷新"
                                library.hasAudioAccess &&
                                    library.videoAccess == VideoLibraryAccess.Partial ->
                                    "调整范围"
                                else -> "补充授权"
                            }
                        LibrarySourceKind.Folder -> null
                        LibrarySourceKind.LooseFiles -> "清空"
                    },
                onRemove =
                    if (source.kind == LibrarySourceKind.Folder) {
                        { onRemoveFolder(source.id) }
                    } else {
                        null
                    },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                QuietButton("选择文件夹", onAddFolder, Modifier.fillMaxWidth())
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
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(source.displayName, color = AppPrimaryText, fontSize = 14.sp)
                Text(
                    when {
                        isLoading -> "正在扫描"
                        !source.hasAccess -> "需要授权"
                        source.errorMessage != null -> "暂时不可用"
                        !source.isScanned -> "等待手动刷新"
                        else -> "发现 ${source.itemCount} 项"
                    },
                    color = if (!source.hasAccess || source.errorMessage != null) AppError else AppMutedText,
                    fontSize = 11.sp,
                )
            }
            if (primaryActionLabel != null && onPrimaryAction != null) {
                TextButton(onClick = onPrimaryAction) {
                    Text(primaryActionLabel, color = AppSecondaryText, fontSize = 11.sp)
                }
            }
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text("移除", color = AppMutedText, fontSize = 11.sp) }
            }
        }
        source.errorMessage?.let { StatusText(it, AppError) }
        Hairline()
    }
}

@Composable
private fun ContinueSection(
    session: ActiveSession,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    libraryConfig: LibraryConfig,
    playback: PlaybackSnapshot,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val lastSurface =
        lastSession
            ?.takeIf { libraryConfig.sameContent(it.session.media, session.media) }
            ?.lastSurface
            ?.asLastSurface()
            ?: PlayerSurface.Normal
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(
            title = "继续上次",
            detail = if (lastSurface == PlayerSurface.Glow) "微光" else null,
        )
        NowPlayingBar(
            session = session,
            activeSession = activeSession,
            lastSession = lastSession,
            libraryConfig = libraryConfig,
            playback = playback,
            onContinue = onContinue,
            onPlayPause = onPlayPause,
        )
    }
}

@Composable
private fun NowPlayingBar(
    session: ActiveSession,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    libraryConfig: LibraryConfig,
    playback: PlaybackSnapshot,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val matchingLastSession =
        lastSession?.takeIf { libraryConfig.sameContent(it.session.media, session.media) }
    val availability =
        matchingLastSession?.mediaAvailability
            ?: if (activeSession != null) MediaAvailability.Ready else null
    val lastSurface = matchingLastSession?.lastSurface?.asLastSurface() ?: PlayerSurface.Normal
    val positionMs =
        if (activeSession != null) playback.positionMs else matchingLastSession?.positionMs ?: 0L
    val sessionDetail =
        listOf(
            formatTime(positionMs),
            if (session.subtitle == null) "无字幕" else "有字幕",
            "微光".takeIf { lastSurface == PlayerSurface.Glow },
        ).filterNotNull().joinToString(" · ")
    Column(modifier = modifier.fillMaxWidth().background(Color.Black)) {
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp).clickable(onClick = onContinue),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(session.media.displayTitle, color = AppPrimaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (availability) {
                        MediaAvailability.Checking -> "正在验证"
                        is MediaAvailability.Unavailable -> "需要重新定位"
                        else -> sessionDetail
                    },
                    color = if (availability is MediaAvailability.Unavailable) AppError else AppMutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick =
                    if (activeSession == null || availability is MediaAvailability.Unavailable) {
                        onContinue
                    } else {
                        onPlayPause
                    },
                enabled = availability != MediaAvailability.Checking,
            ) {
                Text(
                    when {
                        availability is MediaAvailability.Unavailable -> "重新定位"
                        activeSession == null && lastSurface == PlayerSurface.Glow -> "继续微光"
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
    hasSubtitle: Boolean,
    onPlay: () -> Unit,
    onOpenMediaActions: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(media.displayTitle, color = AppPrimaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (media.kind == MediaKind.Video) "视频" else "音频",
                    "字幕".takeIf { hasSubtitle },
                    media.displayArtist ?: media.libraryFolderName,
                ).joinToString(" · "),
                color = AppMutedText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (media.durationMs > 0L) Text(formatTime(media.durationMs), color = AppMutedText, fontSize = 11.sp)
        TextButton(onClick = onRemove ?: onOpenMediaActions) {
            Text(if (onRemove == null) "操作" else "移除", color = AppMutedText, fontSize = 11.sp)
        }
    }
    Hairline()
}

@Composable
private fun ImportContentDialog(
    draft: SessionDraft,
    activeSession: ActiveSession?,
    onPickMedia: () -> Unit,
    onPickSubtitle: (TextTrackSlot) -> Unit,
    onRemoveSubtitle: (TextTrackSlot) -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canPickSubtitle = draft.media is DraftMedia.Ready
    val subtitleDrafts = listOf(draft.subtitle, draft.secondarySubtitle)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开内容", color = AppPrimaryText) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                DraftSubtitleSourceRow(
                    label = "主字幕",
                    emptyValue = "可选 · 一份字幕文件即可",
                    subtitle = draft.subtitle,
                    canPickSubtitle = canPickSubtitle,
                    onPick = { onPickSubtitle(TextTrackSlot.Primary) },
                    onRemove = { onRemoveSubtitle(TextTrackSlot.Primary) },
                )
                Hairline()
                DraftSubtitleSourceRow(
                    label = "副字幕",
                    emptyValue = "可选 · 仅在需要第二份独立字幕时添加",
                    subtitle = draft.secondarySubtitle,
                    canPickSubtitle = canPickSubtitle,
                    onPick = { onPickSubtitle(TextTrackSlot.Secondary) },
                    onRemove = { onRemoveSubtitle(TextTrackSlot.Secondary) },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearDraft) { Text("清空", color = AppMutedText) }
                    QuietButton(
                        label =
                            when {
                                draft.media is DraftMedia.Checking -> "正在验证"
                                draft.media is DraftMedia.Unavailable -> "需要重新定位"
                                subtitleDrafts.any { it is DraftSubtitle.Loading } -> "正在读取字幕"
                                subtitleDrafts.any { it is DraftSubtitle.Failed } ->
                                    "忽略不可用字幕并播放"
                                activeSession == null -> "开始播放"
                                else -> "应用并播放"
                            },
                        onClick = onApplyDraft,
                        enabled =
                            draft.media is DraftMedia.Ready &&
                                subtitleDrafts.none { it is DraftSubtitle.Loading },
                        modifier = Modifier.weight(1f),
                        emphasized = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = AppSecondaryText) } },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun DraftSubtitleSourceRow(
    label: String,
    emptyValue: String,
    subtitle: DraftSubtitle,
    canPickSubtitle: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    SourceRow(
        label = label,
        value =
            when (subtitle) {
                DraftSubtitle.None ->
                    if (canPickSubtitle) emptyValue else "请先选择播放内容"
                is DraftSubtitle.Loading -> "正在读取 ${subtitle.source.displayName}"
                is DraftSubtitle.Ready -> subtitle.subtitle.source.displayName
                is DraftSubtitle.Failed -> subtitle.source.displayName
            },
        action = if (subtitle == DraftSubtitle.None) "选择" else "更换",
        onClick = onPick,
        enabled = canPickSubtitle,
    )
    when (subtitle) {
        is DraftSubtitle.Failed -> StatusText(subtitle.message, AppError)
        is DraftSubtitle.Ready ->
            StatusText("已读取 ${subtitle.subtitle.timeline.cues.size} 条字幕")
        else -> Unit
    }
    if (subtitle != DraftSubtitle.None) {
        TextButton(onClick = onRemove) {
            Text("移除$label", color = AppMutedText)
        }
    }
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
                Text(item.displayTitle, color = AppSecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                playlists.forEach { playlist ->
                    TextButton(onClick = { onAdd(playlist.id) }) {
                        Text("${playlist.name} · ${playlist.items.size} 项", color = AppPrimaryText)
                    }
                }
                if (playlists.isEmpty()) StatusText("还没有播放列表")
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text("新建播放列表", color = AppAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = AppSecondaryText) } },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun MediaActionDialog(
    item: MediaSourceRef,
    canRemoveFromLibrary: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onChoosePlaylist: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayTitle, color = AppPrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onPlayNext) { Text("下一项播放", color = AppPrimaryText) }
                TextButton(onClick = onAddToQueue) { Text("添加到队列末尾", color = AppPrimaryText) }
                TextButton(onClick = onChoosePlaylist) { Text("添加到播放列表", color = AppPrimaryText) }
                if (canRemoveFromLibrary) {
                    TextButton(onClick = onRemoveFromLibrary) { Text("移出收录", color = AppSecondaryText) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = AppSecondaryText) } },
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
            OutlinedTextField(value = name, onValueChange = onNameChanged, label = { Text("名称") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) {
                Text("创建", color = if (name.isNotBlank()) AppAccent else AppMutedText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = AppSecondaryText) } },
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
        SectionHeading("预设", profile.name)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DisplayProfile.Presets.forEach { preset ->
                QuietButton(preset.name, { onProfileChanged(preset) }, Modifier.weight(1f), emphasized = preset == profile)
            }
        }
    }
    Hairline()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("微光校准", "窗口与发光强度")
        DisplaySettingSlider("环境亮度", windowBrightnessLabel(profile.glowWindowBrightness), profile.glowWindowBrightness, 0.01f..0.30f) {
            onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowWindowBrightness = it))
        }
        DisplaySettingSlider("文字亮度", luminanceLabel(profile.glowSubtitleLevel, 4, 96), profile.glowSubtitleLevel.toFloat(), 4f..96f) {
            onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleLevel = it.roundToInt()))
        }
        DisplaySettingSlider("控制亮度", luminanceLabel(profile.glowControlLevel, 24, 160), profile.glowControlLevel.toFloat(), 24f..160f) {
            onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowControlLevel = it.roundToInt()))
        }
    }
    Hairline()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("字幕外观", "独立于屏幕亮度")
        DisplaySettingSlider("字号", subtitleSizeLabel(profile.glowSubtitleSizeSp), profile.glowSubtitleSizeSp, 18f..36f) {
            onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleSizeSp = it))
        }
        DisplaySettingSlider("垂直位置", verticalPositionLabel(profile.glowSubtitleVerticalBias), profile.glowSubtitleVerticalBias, -0.60f..0.60f) {
            onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleVerticalBias = it))
        }
    }
    Hairline()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("微光交互", "锁定态")
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
                Modifier.align(BiasAlignment(0f, profile.glowSubtitleVerticalBias.coerceIn(-0.60f, 0.60f)))
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = AppMutedText, fontSize = 11.sp)
            Text(value, color = AppPrimaryText, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onClick, enabled = enabled) {
            Text(action, color = if (enabled) AppSecondaryText else AppMutedText)
        }
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
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = if (emphasized) AppAccent else AppPrimaryText, disabledContentColor = AppMutedText),
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = AppSecondaryText, fontSize = 12.sp)
            Text(valueLabel, color = AppPrimaryText, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.semantics { contentDescription = "$label，$valueLabel" },
            colors = SliderDefaults.colors(thumbColor = AppAccent, activeTrackColor = AppSecondaryText, inactiveTrackColor = AppHairline),
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

private fun availableCollections(library: MediaLibraryState): List<ResolvedCollection> {
    val folders =
        library.items
            .groupBy(MediaSourceRef::libraryFolderKey)
            .values
            .map { items ->
                ResolvedCollection(
                    shortcut = items.first().toFolderShortcut(),
                    name = items.first().libraryFolderName,
                    items = items.sortedBy { it.displayTitle.lowercase() },
                )
            }
            .sortedBy { it.name.lowercase() }
    val playlists =
        library.config.playlists.map { playlist ->
            ResolvedCollection(
                shortcut = LibraryShortcut(LibraryShortcutType.Playlist, playlist.id),
                name = playlist.name,
                items = playlist.items,
                isPlaylist = true,
            )
        }
    return folders + playlists
}

private fun resolveHomeCollections(library: MediaLibraryState): List<ResolvedCollection> {
    val available = availableCollections(library).associateBy(ResolvedCollection::shortcut)
    return library.config.homeShortcuts.mapNotNull(available::get)
}

private fun resolveCollection(
    shortcut: LibraryShortcut,
    library: MediaLibraryState,
): ResolvedCollection? = availableCollections(library).firstOrNull { it.shortcut == shortcut }

private fun MediaSourceRef.matches(query: String): Boolean =
    listOf(displayTitle, displayArtist, album, folder, displayName)
        .any { value -> value?.contains(query, ignoreCase = true) == true }

private fun MediaLibraryState.hasSuggestedSubtitleFor(media: MediaSourceRef): Boolean =
    suggestedTextTracks.any { it.mediaUriValue == media.uri.toString() } ||
        config.hasTimedTextTracks(media)

private fun MediaLibraryState.hasSubtitleFor(media: MediaSourceRef): Boolean =
    config.activeTextTrack(media, TextTrackSlot.Primary)?.toSubtitleSourceRef() != null

private fun collectionDetail(items: List<MediaSourceRef>): String {
    val audioCount = items.count { it.kind == MediaKind.Audio }
    val videoCount = items.size - audioCount
    return listOfNotNull(
        "$audioCount 音频".takeIf { audioCount > 0 },
        "$videoCount 视频".takeIf { videoCount > 0 },
    ).ifEmpty { listOf("0 项") }.joinToString(" · ")
}

private fun candidateDetail(
    media: MediaSourceRef,
    hasSubtitle: Boolean,
): String =
    listOfNotNull(
        if (media.kind == MediaKind.Video) "视频" else "音频",
        formatTime(media.durationMs).takeIf { media.durationMs > 0L },
        if (media.videoWidth > 0 && media.videoHeight > 0) "${media.videoWidth}×${media.videoHeight}" else null,
        formatFileSize(media.sizeBytes).takeIf { media.sizeBytes > 0L },
        "匹配字幕".takeIf { hasSubtitle },
    ).joinToString(" · ")

private fun formatFileSize(bytes: Long): String =
    when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

private fun windowBrightnessLabel(value: Float): String =
    when {
        value < 0.055f -> "极暗"
        value < 0.11f -> "柔和"
        value < 0.19f -> "清晰"
        else -> "明亮"
    }

private fun luminanceLabel(value: Int, minimum: Int, maximum: Int): String {
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
