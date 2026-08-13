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
    Home,
    Tonight,
    MyContent,
    Discoveries,
    Sources,
    Display,
}

private data class ResolvedPlaylist(
    val id: String,
    val name: String,
    val items: List<MediaSourceRef>,
)

@Composable
internal fun HomeScreen(
    section: HomeSection,
    onSectionChanged: (HomeSection) -> Unit,
    draft: SessionDraft,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    tonight: List<MediaSourceRef>,
    tonightCurrentIndex: Int,
    tonightPositionMs: Long,
    playback: PlaybackSnapshot,
    displayProfile: DisplayProfile,
    library: MediaLibraryState,
    onPickMedia: () -> Unit,
    onCancelMediaOpen: () -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshSource: (String) -> Unit,
    onCancelScan: (String) -> Unit,
    onAddFolder: () -> Unit,
    onRelinkFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onClearLooseFiles: () -> Unit,
    onAcceptCandidates: (Set<String>) -> Unit,
    onIgnoreCandidates: (Set<String>) -> Unit,
    onRemoveIncluded: (Set<String>) -> Unit,
    onRestoreIgnored: (Set<String>) -> Unit,
    onCollectMedia: (MediaSourceRef) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onAddToTonight: (MediaSourceRef) -> Unit,
    onRemoveFromTonight: (Int) -> Unit,
    onMoveTonight: (Int, Int) -> Unit,
    onClearTonight: () -> Unit,
    onStartTonight: () -> Unit,
    onCreatePlaylist: (String, MediaSourceRef?) -> Unit,
    onAddToPlaylist: (String, MediaSourceRef) -> Unit,
    onRemoveFromPlaylist: (String, MediaSourceRef) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var myContentTab by rememberSaveable { mutableStateOf(MyContentTab.Collected) }
    var showingIgnored by rememberSaveable { mutableStateOf(false) }
    var reviewSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var knownCandidateUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ignoredSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var importVisible by rememberSaveable { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var mediaActionTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var createPlaylistVisible by rememberSaveable { mutableStateOf(false) }
    var createPlaylistName by rememberSaveable { mutableStateOf("") }
    var createPlaylistSeed by remember { mutableStateOf<MediaSourceRef?>(null) }
    var folderRemovalTarget by remember { mutableStateOf<LibraryFolderRef?>(null) }
    var clearLooseFilesConfirmation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(section) {
        if (section != HomeSection.MyContent) {
            searchVisible = false
            selectedPlaylistId = null
        }
        if (section != HomeSection.Discoveries) showingIgnored = false
        if (section != HomeSection.Sources) selectedSourceId = null
    }
    LaunchedEffect(section, showingIgnored) {
        if (section == HomeSection.Discoveries && !showingIgnored) {
            reviewSelection =
                library.candidates
                    .filter { it.kind == MediaKind.Audio && !library.isUnavailable(it) }
                    .mapTo(linkedSetOf()) { it.uri.toString() }
            knownCandidateUris =
                library.candidates
                    .filterNot(library::isUnavailable)
                    .mapTo(hashSetOf()) { it.uri.toString() }
        } else if (section == HomeSection.Discoveries && showingIgnored) {
            ignoredSelection = emptySet()
        }
    }
    LaunchedEffect(library.candidates, library.config, library.sources) {
        val availableCandidates = library.candidates.filterNot(library::isUnavailable)
        val currentUris = availableCandidates.mapTo(hashSetOf()) { it.uri.toString() }
        if (section == HomeSection.Discoveries && !showingIgnored) {
            val newAudioUris =
                availableCandidates
                    .filter { it.kind == MediaKind.Audio }
                    .mapTo(linkedSetOf()) { it.uri.toString() }
            reviewSelection =
                reconcileDiscoverySelection(
                    selectedUris = reviewSelection,
                    knownUris = knownCandidateUris,
                    availableUris = currentUris,
                    defaultSelectedUris = newAudioUris,
                )
        }
        knownCandidateUris = currentUris
    }
    LaunchedEffect(library.ignoredItems) {
        val ignoredUris = library.ignoredItems.mapTo(hashSetOf()) { it.uri.toString() }
        ignoredSelection = ignoredSelection.filterTo(linkedSetOf(), ignoredUris::contains)
    }
    LaunchedEffect(library.sources, selectedSourceId) {
        if (
            selectedSourceId != null &&
                library.sources.none { it.id == selectedSourceId }
        ) {
            selectedSourceId = null
        }
    }

    val resumableSession = activeSession ?: lastSession?.session
    val miniPlayerSession =
        resumableSession?.takeIf {
            section == HomeSection.MyContent &&
                (searchVisible || selectedPlaylistId != null)
        }
    val showMiniPlayer = miniPlayerSession != null
    val selectedPlaylist =
        selectedPlaylistId?.let { playlistId -> resolvePlaylist(playlistId, library) }

    BackHandler(
        enabled =
            section == HomeSection.Tonight ||
                (section == HomeSection.MyContent &&
                (searchVisible || selectedPlaylistId != null)) ||
                (section == HomeSection.Discoveries && showingIgnored) ||
                (section == HomeSection.Sources && selectedSourceId != null),
    ) {
        when {
            section == HomeSection.MyContent && selectedPlaylistId != null ->
                selectedPlaylistId = null
            section == HomeSection.MyContent && searchVisible -> {
                searchVisible = false
                searchQuery = ""
            }
            section == HomeSection.Discoveries -> showingIgnored = false
            section == HomeSection.Sources && selectedSourceId != null -> selectedSourceId = null
            section == HomeSection.Tonight -> onSectionChanged(HomeSection.Home)
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
            selectedPlaylist = selectedPlaylist,
            selectedSource = library.sources.firstOrNull { it.id == selectedSourceId },
            showingIgnored = showingIgnored,
            onBack = {
                when {
                    section == HomeSection.MyContent && selectedPlaylistId != null ->
                        selectedPlaylistId = null
                    section == HomeSection.MyContent && searchVisible -> {
                        searchVisible = false
                        searchQuery = ""
                    }
                    section == HomeSection.Discoveries && showingIgnored ->
                        showingIgnored = false
                    section == HomeSection.Sources && selectedSourceId != null ->
                        selectedSourceId = null
                    section == HomeSection.Tonight -> onSectionChanged(HomeSection.Home)
                    section != HomeSection.Home -> onSectionChanged(HomeSection.Home)
                }
            },
            onSearch = {
                selectedPlaylistId = null
                searchVisible = true
            },
            onDisplay = { onSectionChanged(HomeSection.Display) },
            onToggleIgnored = { showingIgnored = !showingIgnored },
        )

        when (section) {
            HomeSection.Home ->
                HomeLanding(
                    library = library,
                    resumableSession = resumableSession,
                    activeSession = activeSession,
                    lastSession = lastSession,
                    tonight = tonight,
                    tonightCurrentIndex = tonightCurrentIndex,
                    tonightPositionMs = tonightPositionMs,
                    playback = playback,
                    onOpenMyContent = {
                        myContentTab = defaultMyContentTab(library.items.size)
                        onSectionChanged(HomeSection.MyContent)
                    },
                    onOpenDiscoveries = {
                        showingIgnored = false
                        onSectionChanged(HomeSection.Discoveries)
                    },
                    onOpenTonight = { onSectionChanged(HomeSection.Tonight) },
                    onOpenSources = { onSectionChanged(HomeSection.Sources) },
                    onPlayLibraryItems = onPlayLibraryItems,
                    onOpenMediaActions = { mediaActionTarget = it },
                    onAddFolder = onAddFolder,
                    onOpenFile = {
                        importVisible = true
                        onPickMedia()
                    },
                    onContinue = onContinue,
                    onPlayPause = onPlayPause,
                    onStartTonight = onStartTonight,
                    bottomPadding = 8.dp,
                    modifier = Modifier.weight(1f),
                )
            HomeSection.Tonight ->
                TonightEditor(
                    tonight = tonight,
                    currentIndex = tonightCurrentIndex,
                    library = library,
                    onStartTonight = onStartTonight,
                    onRemove = onRemoveFromTonight,
                    onMove = onMoveTonight,
                    onClear = onClearTonight,
                    onOpenMediaActions = { mediaActionTarget = it },
                    modifier = Modifier.weight(1f),
                )
            HomeSection.MyContent ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        selectedPlaylist != null ->
                            PlaylistDetail(
                                playlist = selectedPlaylist,
                                library = library,
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                onRemoveFromPlaylist = onRemoveFromPlaylist,
                                onDeletePlaylist = { playlistId ->
                                    onDeletePlaylist(playlistId)
                                    selectedPlaylistId = null
                                },
                                bottomPadding = if (showMiniPlayer) 88.dp else 8.dp,
                                modifier = Modifier.fillMaxSize(),
                            )
                        searchVisible ->
                            MyContentSearch(
                                query = searchQuery,
                                onQueryChanged = { searchQuery = it },
                                library = library,
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                bottomPadding = if (showMiniPlayer) 88.dp else 8.dp,
                                modifier = Modifier.fillMaxSize(),
                            )
                        else ->
                            MyContent(
                                library = library,
                                selectedTab = myContentTab,
                                onTabChanged = { myContentTab = it },
                                onPlayLibraryItems = onPlayLibraryItems,
                                onOpenMediaActions = { mediaActionTarget = it },
                                onOpenPlaylist = { playlistId ->
                                    selectedPlaylistId = playlistId
                                },
                                onCreatePlaylist = {
                                    createPlaylistSeed = null
                                    createPlaylistName = ""
                                    createPlaylistVisible = true
                                },
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
            HomeSection.Discoveries ->
                if (showingIgnored) {
                    IgnoredContent(
                        items = library.ignoredItems,
                        selectedUris = ignoredSelection,
                        onSelectionChanged = { ignoredSelection = it },
                        onRestore = {
                            onRestoreIgnored(setOf(it.uri.toString()))
                            ignoredSelection -= it.uri.toString()
                        },
                        onRestoreSelected = {
                            onRestoreIgnored(ignoredSelection)
                            ignoredSelection = emptySet()
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    CandidateReview(
                        library = library,
                        selectedUris = reviewSelection,
                        onSelectionChanged = { reviewSelection = it },
                        onAccept = {
                            onAcceptCandidates(reviewSelection)
                            reviewSelection = emptySet()
                        },
                        onAcceptOne = { uri ->
                            onAcceptCandidates(setOf(uri))
                            reviewSelection -= uri
                        },
                        onPlay = { media -> onPlayLibraryItems(listOf(media), 0) },
                        onOpenMediaActions = { mediaActionTarget = it },
                        onIgnore = { uri ->
                            reviewSelection -= uri
                            onIgnoreCandidates(setOf(uri))
                        },
                        onIgnoreSelected = {
                            onIgnoreCandidates(reviewSelection)
                            reviewSelection = emptySet()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            HomeSection.Sources ->
                if (selectedSourceId != null) {
                    SourceDetail(
                        source = library.sources.firstOrNull { it.id == selectedSourceId },
                        library = library,
                        onRequestLibraryAccess = onRequestLibraryAccess,
                        onRefreshSource = onRefreshSource,
                        onCancelScan = onCancelScan,
                        onRelinkFolder = onRelinkFolder,
                        onRemoveFolder = { folderId ->
                            folderRemovalTarget =
                                library.config.folders.firstOrNull { it.id == folderId }
                        },
                        onPlay = { media -> onPlayLibraryItems(listOf(media), 0) },
                        onCollect = onCollectMedia,
                        onRemoveIncluded = onRemoveIncluded,
                        onIgnore = { media -> onIgnoreCandidates(setOf(media.uri.toString())) },
                        onRestore = { media -> onRestoreIgnored(setOf(media.uri.toString())) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    ScanSources(
                        library = library,
                        onRequestLibraryAccess = onRequestLibraryAccess,
                        onRefreshLibrary = onRefreshLibrary,
                        onRefreshSource = onRefreshSource,
                        onCancelScan = onCancelScan,
                        onOpenSource = { selectedSourceId = it },
                        onAddFolder = onAddFolder,
                        onRelinkFolder = onRelinkFolder,
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
            isCollected = library.config.isCollected(item),
            onDismiss = { mediaActionTarget = null },
            onAddToTonight = {
                onAddToTonight(item)
                mediaActionTarget = null
            },
            onChoosePlaylist = {
                mediaActionTarget = null
                playlistTarget = item
            },
            onAddToMyContent = {
                onCollectMedia(item)
                mediaActionTarget = null
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
        OpenContentDialog(
            draft = draft,
            onPickMedia = onPickMedia,
            onCancel = {
                onCancelMediaOpen()
                importVisible = false
            },
            onDismiss = {
                onCancelMediaOpen()
                importVisible = false
            },
        )
    }

    folderRemovalTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderRemovalTarget = null },
            title = { Text("移除来源？", color = AppPrimaryText) },
            text = {
                Text(
                    "只移除应用对“${folder.displayName}”的来源引用，不会删除原文件。",
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
    selectedPlaylist: ResolvedPlaylist?,
    selectedSource: LibrarySourceState?,
    showingIgnored: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onDisplay: () -> Unit,
    onToggleIgnored: () -> Unit,
) {
    val isHome = section == HomeSection.Home
    val title =
        when {
            searchVisible -> "搜索"
            selectedPlaylist != null -> selectedPlaylist.name
            selectedSource != null -> selectedSource.displayName
            section == HomeSection.MyContent -> "我的内容"
            section == HomeSection.Tonight -> "今晚"
            section == HomeSection.Discoveries && showingIgnored -> "已忽略"
            section == HomeSection.Discoveries -> "新发现"
            section == HomeSection.Sources -> "来源"
            section == HomeSection.Display -> "显示"
            else -> "今晚"
        }
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isHome) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = AppPrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
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
            isHome ->
                TextButton(onClick = onDisplay) {
                    Text("显示", color = AppSecondaryText, fontSize = 12.sp)
                }
            section == HomeSection.MyContent &&
                !searchVisible &&
                selectedPlaylist == null -> {
                TextButton(onClick = onSearch) {
                    Text("搜索", color = AppSecondaryText, fontSize = 12.sp)
                }
            }
            section == HomeSection.Discoveries ->
                TextButton(onClick = onToggleIgnored) {
                    Text(
                        if (showingIgnored) "新发现" else "已忽略",
                        color = AppSecondaryText,
                        fontSize = 12.sp,
                    )
                }
        }
    }
}

@Composable
private fun HomeLanding(
    library: MediaLibraryState,
    resumableSession: ActiveSession?,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    tonight: List<MediaSourceRef>,
    tonightCurrentIndex: Int,
    tonightPositionMs: Long,
    playback: PlaybackSnapshot,
    onOpenMyContent: () -> Unit,
    onOpenDiscoveries: () -> Unit,
    onOpenTonight: () -> Unit,
    onOpenSources: () -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onAddFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onStartTonight: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val recentItems = remember(library.config) { resolveRecentItems(library) }
    val collectedItems =
        remember(library.items, library.config) { orderedCollectedItems(library, recentItems) }
    val firstUse =
        tonight.isEmpty() && shouldShowFirstUseHome(
            hasResumableSession = resumableSession != null,
            collectedCount = collectedItems.size,
            recentCount = recentItems.size,
            discoveryCount = library.candidates.size,
            ignoredCount = library.ignoredItems.size,
            hasConfiguredSource =
                library.config.folders.isNotEmpty() || library.hasSystemAccess,
        )
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (firstUse) {
            item(key = "first-use") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "今晚想听什么？",
                        color = AppPrimaryText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    QuietButton(
                        "打开一个内容",
                        onOpenFile,
                        Modifier.fillMaxWidth(),
                        emphasized = true,
                    )
                    QuietButton("添加常用文件夹", onAddFolder, Modifier.fillMaxWidth())
                }
            }
        } else {
            if (tonight.isNotEmpty()) {
                item(key = "tonight") {
                    TonightPreview(
                        tonight = tonight,
                        currentIndex = tonightCurrentIndex,
                        positionMs = tonightPositionMs,
                        onOpen = onOpenTonight,
                        onStart = onStartTonight,
                    )
                }
            } else {
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
            }
            item(key = "my-content-heading") {
                ManagerEntry(
                    title = "我的内容",
                    detail =
                        when {
                            collectedItems.isNotEmpty() -> "${collectedItems.size} 项已收录"
                            recentItems.isNotEmpty() -> "${recentItems.size} 项最近打开"
                            else -> "还没有收录内容"
                        },
                    action = "查看全部",
                    onClick = onOpenMyContent,
                )
            }
            itemsIndexed(
                items = collectedItems.take(HomePreviewItemCount),
                key = { _, item -> "home-content-${item.uri}" },
            ) { index, media ->
                TrackRow(
                    media = media,
                    hasSubtitle = library.hasSubtitleFor(media),
                    unavailable = library.isUnavailable(media),
                    onPlay = { onPlayLibraryItems(collectedItems, index) },
                    onOpenMediaActions = { onOpenMediaActions(media) },
                )
            }
            item(key = "more-heading") { SectionHeading("更多") }
            if (library.candidates.isNotEmpty() || library.ignoredItems.isNotEmpty()) {
                item(key = "discoveries") {
                    ManagerEntry(
                        title = "新发现",
                        detail =
                            if (library.candidates.isNotEmpty()) {
                                "${library.candidates.size} 项尚未决定的内容"
                            } else {
                                "没有新发现 · ${library.ignoredItems.size} 项已忽略"
                            },
                        action = "查看",
                        onClick = onOpenDiscoveries,
                    )
                }
            }
            item(key = "add-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    QuietButton(
                        "打开一个内容",
                        onOpenFile,
                        Modifier.fillMaxWidth(),
                        emphasized = true,
                    )
                    QuietButton("添加常用文件夹", onAddFolder, Modifier.fillMaxWidth())
                }
            }
            item(key = "sources") {
                ManagerEntry(
                    title = "来源",
                    detail = sourceSummary(library),
                    action = "管理",
                    onClick = onOpenSources,
                )
            }
        }
        if (library.isLoading) item(key = "loading") { StatusText("来源正在扫描…") }
        library.errorMessage?.let { message ->
            item(key = "error") { StatusText(message, color = AppError) }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: ResolvedPlaylist,
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
                playlist.name,
                color = AppPrimaryText,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(collectionDetail(playlist.items), color = AppMutedText, fontSize = 11.sp)
        }
        TextButton(onClick = onPlay, enabled = playlist.items.isNotEmpty()) {
            Text("播放", color = AppSecondaryText, fontSize = 12.sp)
        }
    }
    Hairline()
}

@Composable
private fun PlaylistDetail(
    playlist: ResolvedPlaylist,
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
                Text(collectionDetail(playlist.items), color = AppMutedText, fontSize = 12.sp)
                Row {
                    TextButton(
                        onClick = { onPlayLibraryItems(playlist.items, 0) },
                        enabled = playlist.items.isNotEmpty(),
                    ) {
                        Text("播放全部", color = AppSecondaryText, fontSize = 12.sp)
                    }
                    TextButton(onClick = { onDeletePlaylist(playlist.id) }) {
                        Text("删除", color = AppMutedText, fontSize = 12.sp)
                    }
                }
            }
        }
        itemsIndexed(
            items = playlist.items,
            key = { _, item -> "collection-${item.uri}" },
        ) { index, media ->
            TrackRow(
                media = media,
                hasSubtitle = library.hasSubtitleFor(media),
                onPlay = { onPlayLibraryItems(playlist.items, index) },
                onOpenMediaActions = { onOpenMediaActions(media) },
                onRemove = { onRemoveFromPlaylist(playlist.id, media) },
            )
        }
        if (playlist.items.isEmpty()) item { StatusText("这里还没有内容") }
    }
}

@Composable
private fun MyContent(
    library: MediaLibraryState,
    selectedTab: MyContentTab,
    onTabChanged: (MyContentTab) -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val recentItems = remember(library.config) { resolveRecentItems(library) }
    val collectedItems =
        remember(library.items, library.config) { orderedCollectedItems(library, recentItems) }
    val visibleItems =
        when (selectedTab) {
            MyContentTab.Collected -> collectedItems
            MyContentTab.Recent -> recentItems
        }
    val playlists = remember(library.config.playlists) { availablePlaylists(library) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "my-content-tabs") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuietButton(
                    label = "已收录 ${collectedItems.size}",
                    onClick = { onTabChanged(MyContentTab.Collected) },
                    modifier = Modifier.weight(1f),
                    emphasized = selectedTab == MyContentTab.Collected,
                )
                QuietButton(
                    label = "最近 ${recentItems.size}",
                    onClick = { onTabChanged(MyContentTab.Recent) },
                    modifier = Modifier.weight(1f),
                    emphasized = selectedTab == MyContentTab.Recent,
                )
            }
        }
        if (visibleItems.isEmpty()) {
            item(key = "my-content-empty") {
                StatusText(
                    if (selectedTab == MyContentTab.Collected) {
                        "还没有加入“我的内容”的条目"
                    } else {
                        "还没有最近打开的内容"
                    },
                )
            }
        } else {
            itemsIndexed(
                items = visibleItems,
                key = { _, item -> "my-content-${selectedTab.name}-${item.uri}" },
            ) { index, media ->
                TrackRow(
                    media = media,
                    hasSubtitle = library.hasSubtitleFor(media),
                    unavailable = library.isUnavailable(media),
                    onPlay = { onPlayLibraryItems(visibleItems, index) },
                    onOpenMediaActions = { onOpenMediaActions(media) },
                )
            }
        }
        if (selectedTab == MyContentTab.Collected) {
            item(key = "playlists-heading") {
                Spacer(modifier = Modifier.height(10.dp))
                SectionHeading("播放列表", "可选的手动编排")
            }
            items(
                items = playlists,
                key = { "playlist-${it.id}" },
            ) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onOpen = { onOpenPlaylist(playlist.id) },
                    onPlay = {
                        if (playlist.items.isNotEmpty()) {
                            onPlayLibraryItems(playlist.items, 0)
                        }
                    },
                )
            }
            item(key = "create-playlist") {
                TextButton(onClick = onCreatePlaylist, modifier = Modifier.fillMaxWidth()) {
                    Text("新建播放列表", color = AppSecondaryText, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MyContentSearch(
    query: String,
    onQueryChanged: (String) -> Unit,
    library: MediaLibraryState,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val normalized = query.trim()
    val recentItems = remember(library.config) { resolveRecentItems(library) }
    val searchableItems =
        remember(library.items, library.config) {
            distinctContentItems(recentItems + orderedCollectedItems(library, recentItems), library)
        }
    val matches =
        if (normalized.isEmpty()) emptyList()
        else searchableItems.filter { it.matches(normalized) }
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
                placeholder = { Text("标题、文件名或来源", color = AppMutedText) },
                singleLine = true,
            )
        }
        if (normalized.isEmpty()) {
            item { StatusText("搜索已收录和最近打开的内容") }
        } else {
            if (matches.isNotEmpty()) {
                item { SectionHeading("内容", "${matches.size} 项") }
                itemsIndexed(matches, key = { _, item -> "search-${item.uri}" }) { index, media ->
                    TrackRow(
                        media = media,
                        hasSubtitle = library.hasSubtitleFor(media),
                        statusLabel =
                            if (library.config.isCollected(media)) "已收录" else "最近打开",
                        unavailable = library.isUnavailable(media),
                        onPlay = { onPlayLibraryItems(matches, index) },
                        onOpenMediaActions = { onOpenMediaActions(media) },
                    )
                }
            }
            if (matches.isEmpty()) item { StatusText("没有找到相关内容") }
        }
    }
}

@Composable
private fun ManagerEntry(
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = AppPrimaryText, fontSize = 15.sp)
            Text(detail, color = AppMutedText, fontSize = 11.sp)
        }
        Text(action, color = AppSecondaryText, fontSize = 12.sp)
    }
    Hairline()
}

@Composable
private fun CandidateReview(
    library: MediaLibraryState,
    selectedUris: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onAccept: () -> Unit,
    onAcceptOne: (String) -> Unit,
    onPlay: (MediaSourceRef) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onIgnore: (String) -> Unit,
    onIgnoreSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (library.candidates.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusText("没有新发现。已收录内容仍可从“我的内容”打开。")
        }
        return
    }
    val groups =
        remember(library.candidates) {
            library.candidates
                .groupBy(MediaSourceRef::libraryFolderKey)
                .values
                .sortedBy { it.firstOrNull()?.libraryFolderName.orEmpty().lowercase() }
        }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "这里是来源中新发现、尚未决定的内容。音频已预选，视频需要逐项确认。",
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
                val groupUris =
                    group.filterNot(library::isUnavailable)
                        .mapTo(linkedSetOf()) { it.uri.toString() }
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
                                    group.filter {
                                        it.kind == MediaKind.Audio && !library.isUnavailable(it)
                                    }
                                        .mapTo(linkedSetOf()) { it.uri.toString() }
                                onSelectionChanged(selectedUris + audioUris)
                            },
                        ) {
                            Text("选择音频", color = AppSecondaryText, fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = {
                                if (groupUris.isNotEmpty() && groupUris.all(selectedUris::contains)) {
                                    onSelectionChanged(selectedUris - groupUris)
                                } else {
                                    onSelectionChanged(selectedUris + groupUris)
                                }
                            },
                        ) {
                            Text(
                                if (groupUris.isNotEmpty() && groupUris.all(selectedUris::contains)) {
                                    "取消全选"
                                } else {
                                    "全选"
                                },
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
                        isUnavailable = library.isUnavailable(media),
                        onToggle = {
                            onSelectionChanged(
                                if (uriValue in selectedUris) selectedUris - uriValue
                                else selectedUris + uriValue,
                            )
                        },
                        onAccept = { onAcceptOne(uriValue) },
                        onPlay = { onPlay(media) },
                        onOpenMediaActions = { onOpenMediaActions(media) },
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
                label = "加入我的内容",
                onClick = onAccept,
                enabled = selectedUris.isNotEmpty(),
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
            QuietButton(
                label = "忽略所选",
                onClick = onIgnoreSelected,
                enabled = selectedUris.isNotEmpty(),
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
    isUnavailable: Boolean,
    onToggle: () -> Unit,
    onAccept: () -> Unit,
    onPlay: () -> Unit,
    onOpenMediaActions: () -> Unit,
    onIgnore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isUnavailable, onClick = onPlay)
                    .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    listOfNotNull(
                        "不可用".takeIf { isUnavailable },
                        candidateDetail(media, hasSubtitle),
                    ).joinToString(" · "),
                    color = if (isUnavailable) AppError else AppMutedText,
                    fontSize = 10.sp,
                )
            }
            Text(
                if (isUnavailable) "需恢复来源" else "播放",
                color = if (isUnavailable) AppMutedText else AppSecondaryText,
                fontSize = 11.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onToggle, enabled = !isUnavailable) {
                Text(
                    if (selected) "已选" else "选择",
                    color = if (selected) AppAccent else AppSecondaryText,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = onAccept, enabled = !isUnavailable) {
                Text("收录", color = AppSecondaryText, fontSize = 11.sp)
            }
            TextButton(onClick = onOpenMediaActions, enabled = !isUnavailable) {
                Text("操作", color = AppSecondaryText, fontSize = 11.sp)
            }
            TextButton(onClick = onIgnore) {
                Text("忽略", color = AppMutedText, fontSize = 11.sp)
            }
        }
    }
    Hairline()
}

@Composable
private fun IgnoredContent(
    items: List<MediaSourceRef>,
    selectedUris: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onRestore: (MediaSourceRef) -> Unit,
    onRestoreSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            StatusText("没有已忽略内容")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { "ignored-${it.uri}" }) { media ->
                val uriValue = media.uri.toString()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onSelectionChanged(
                                if (uriValue in selectedUris) selectedUris - uriValue
                                else selectedUris + uriValue,
                            )
                        },
                    ) {
                        Text(
                            if (uriValue in selectedUris) "已选" else "选择",
                            color = if (uriValue in selectedUris) AppAccent else AppSecondaryText,
                            fontSize = 11.sp,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            media.displayTitle,
                            color = AppPrimaryText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(candidateDetail(media, false), color = AppMutedText, fontSize = 10.sp)
                    }
                    TextButton(onClick = { onRestore(media) }) {
                        Text("恢复", color = AppSecondaryText, fontSize = 11.sp)
                    }
                }
                Hairline()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 ${selectedUris.size} 项", color = AppSecondaryText, fontSize = 12.sp)
            QuietButton(
                label = "恢复所选",
                onClick = onRestoreSelected,
                enabled = selectedUris.isNotEmpty(),
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    onRefreshSource: (String) -> Unit,
    onCancelScan: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onAddFolder: () -> Unit,
    onRelinkFolder: (String) -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "这里管理内容从哪里进入；扫描结果会出现在“新发现”中。",
                    modifier = Modifier.weight(1f),
                    color = AppSecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                TextButton(
                    onClick = onRefreshLibrary,
                    enabled = !library.isLoading,
                ) {
                    Text("刷新全部", color = AppMutedText, fontSize = 11.sp)
                }
            }
        }
        if (library.isLoading && library.scanningSourceIds.size > 1) {
            item { StatusText("正在刷新 ${library.scanningSourceIds.size} 个来源…") }
        }
        items(
            library.sources.filter {
                it.kind != LibrarySourceKind.LooseFiles || it.itemCount > 0
            },
            key = { "source-${it.id}" },
        ) { source ->
            LibrarySourceRow(
                source = source,
                isLoading = source.id in library.scanningSourceIds,
                onPrimaryAction =
                    when (source.kind) {
                        LibrarySourceKind.SystemLibrary ->
                            if (source.id in library.scanningSourceIds) {
                                { onCancelScan(source.id) }
                            }
                            else if (library.hasCompleteSystemAccess) {
                                { onRefreshSource(source.id) }
                            }
                            else onRequestLibraryAccess
                        LibrarySourceKind.Folder ->
                            if (source.id in library.scanningSourceIds) {
                                { onCancelScan(source.id) }
                            }
                            else if (!source.hasAccess) {
                                { onRelinkFolder(source.id) }
                            }
                            else { { onRefreshSource(source.id) } }
                        LibrarySourceKind.LooseFiles -> onClearLooseFiles
                    },
                primaryActionLabel =
                    when (source.kind) {
                        LibrarySourceKind.SystemLibrary ->
                            if (source.id in library.scanningSourceIds) "取消"
                            else when {
                                library.hasCompleteSystemAccess -> "刷新"
                                library.hasAudioAccess &&
                                    library.videoAccess == VideoLibraryAccess.Partial ->
                                    "调整范围"
                                else -> "补充授权"
                            }
                        LibrarySourceKind.Folder ->
                            when {
                                source.id in library.scanningSourceIds -> "取消"
                                !source.hasAccess -> "重新授权"
                                else -> "刷新"
                            }
                        LibrarySourceKind.LooseFiles -> "清空"
                    },
                onOpen = { onOpenSource(source.id) },
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
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(source.displayName, color = AppPrimaryText, fontSize = 14.sp)
                Text(
                    when {
                        isLoading -> "正在刷新"
                        !source.hasAccess -> "需要授权"
                        source.errorMessage != null -> "暂时不可用"
                        !source.isScanned -> "缓存 ${source.itemCount} 项 · 等待刷新"
                        else -> "缓存 ${source.itemCount} 项"
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
            TextButton(onClick = onOpen) {
                Text("查看", color = AppSecondaryText, fontSize = 11.sp)
            }
        }
        source.errorMessage?.let { StatusText(it, color = AppError) }
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
private fun TonightPreview(
    tonight: List<MediaSourceRef>,
    currentIndex: Int,
    positionMs: Long,
    onOpen: () -> Unit,
    onStart: () -> Unit,
) {
    val safeIndex = currentIndex.coerceIn(tonight.indices)
    val current = tonight.getOrNull(safeIndex)
    val next = tonight.getOrNull(safeIndex + 1)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ManagerEntry(
            title = "今晚",
            detail = "${tonight.size} 项",
            action = "编辑",
            onClick = onOpen,
        )
        current?.let { media ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(media.displayTitle, color = AppPrimaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            "第 ${safeIndex + 1} / ${tonight.size} 项",
                            formatTime(positionMs).takeIf { positionMs > 0L },
                            next?.displayTitle?.let { "接下来：$it" },
                        ).joinToString(" · "),
                        color = AppMutedText,
                        fontSize = 11.sp,
                    )
                }
                TextButton(onClick = onStart) {
                    Text(if (positionMs > 0L) "继续今晚" else "开始今晚", color = AppAccent, fontSize = 12.sp)
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun TonightEditor(
    tonight: List<MediaSourceRef>,
    currentIndex: Int,
    library: MediaLibraryState,
    onStartTonight: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (tonight.isEmpty()) "还没有安排今晚的内容" else "${tonight.size} 项",
                color = AppSecondaryText,
                fontSize = 12.sp,
            )
            Row {
                TextButton(onClick = onStartTonight, enabled = tonight.isNotEmpty()) {
                    Text("继续今晚", color = AppAccent, fontSize = 12.sp)
                }
                TextButton(onClick = onClear, enabled = tonight.isNotEmpty()) {
                    Text("清空", color = AppMutedText, fontSize = 12.sp)
                }
            }
        }
        if (tonight.isEmpty()) {
            StatusText("可以在“我的内容”或“新发现”中打开条目操作，加入今晚。")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(
                items = tonight,
                key = { index, media -> "tonight-$index-${media.uri}" },
            ) { index, entry ->
                val media = library.config.resolveActiveMedia(entry)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            media.displayTitle,
                            color = if (index == currentIndex) AppAccent else AppPrimaryText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                if (index == currentIndex) "当前项" else "待播放",
                            ).joinToString(" · "),
                            color = AppMutedText,
                            fontSize = 10.sp,
                        )
                    }
                    TextButton(onClick = { onOpenMediaActions(media) }) {
                        Text("操作", color = AppSecondaryText, fontSize = 11.sp)
                    }
                    TextButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                        Text("↑", color = if (index > 0) AppSecondaryText else AppHairline, fontSize = 12.sp)
                    }
                    TextButton(onClick = { onMove(index, index + 1) }, enabled = index < tonight.lastIndex) {
                        Text("↓", color = if (index < tonight.lastIndex) AppSecondaryText else AppHairline, fontSize = 12.sp)
                    }
                    TextButton(onClick = { onRemove(index) }) {
                        Text("移除", color = AppMutedText, fontSize = 11.sp)
                    }
                }
                Hairline()
            }
        }
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
    statusLabel: String? = null,
    unavailable: Boolean = false,
    onPlay: () -> Unit,
    onOpenMediaActions: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !unavailable, onClick = onPlay)
                .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(media.displayTitle, color = AppPrimaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    "不可用".takeIf { unavailable },
                    statusLabel,
                    if (media.kind == MediaKind.Video) "视频" else "音频",
                    "字幕".takeIf { hasSubtitle },
                    media.displayArtist ?: media.libraryFolderName,
                ).joinToString(" · "),
                color = if (unavailable) AppError else AppMutedText,
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
private fun SourceDetail(
    source: LibrarySourceState?,
    library: MediaLibraryState,
    onRequestLibraryAccess: () -> Unit,
    onRefreshSource: (String) -> Unit,
    onCancelScan: (String) -> Unit,
    onRelinkFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onPlay: (MediaSourceRef) -> Unit,
    onCollect: (MediaSourceRef) -> Unit,
    onRemoveIncluded: (Set<String>) -> Unit,
    onIgnore: (MediaSourceRef) -> Unit,
    onRestore: (MediaSourceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSource = source
    if (currentSource == null) {
        StatusText("来源已移除", modifier = modifier)
        return
    }
    val isScanning = currentSource.id in library.scanningSourceIds
    val items = remember(library.config, currentSource.id) {
        library.config.cachedSourceItems(currentSource.id)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            when {
                isScanning -> "正在刷新这个来源；已缓存的内容仍可继续使用。"
                !currentSource.hasAccess -> "这个来源需要重新授权。"
                currentSource.errorMessage != null -> "来源暂时不可用，保留的内容不会被删除。"
                else -> "这个来源只显示已缓存的发现结果；播放不要求先收录。"
            },
            color = AppSecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        if (currentSource.kind == LibrarySourceKind.SystemLibrary) {
            StatusText(systemLibraryAccessSummary(library))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "缓存 ${items.size} 项",
                color = AppMutedText,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            if (
                currentSource.kind == LibrarySourceKind.SystemLibrary &&
                    !library.hasCompleteSystemAccess &&
                    !isScanning
            ) {
                TextButton(onClick = onRequestLibraryAccess) {
                    Text(
                        if (currentSource.hasAccess) "调整授权" else "授权",
                        color = AppSecondaryText,
                        fontSize = 11.sp,
                    )
                }
            }
            when (currentSource.kind) {
                LibrarySourceKind.SystemLibrary ->
                    if (currentSource.hasAccess) {
                        QuietButton(
                            if (isScanning) "取消" else "刷新",
                            if (isScanning) {
                                { onCancelScan(currentSource.id) }
                            } else {
                                { onRefreshSource(currentSource.id) }
                            },
                        )
                    }
                LibrarySourceKind.Folder ->
                    QuietButton(
                        when {
                            isScanning -> "取消"
                            !currentSource.hasAccess -> "重新授权"
                            else -> "刷新"
                        },
                        when {
                            isScanning -> ({ onCancelScan(currentSource.id) })
                            !currentSource.hasAccess -> ({ onRelinkFolder(currentSource.id) })
                            else -> ({ onRefreshSource(currentSource.id) })
                        },
                    )
                LibrarySourceKind.LooseFiles -> Unit
            }
            if (currentSource.kind == LibrarySourceKind.Folder) {
                TextButton(onClick = { onRemoveFolder(currentSource.id) }) {
                    Text("移除来源", color = AppMutedText, fontSize = 11.sp)
                }
            }
        }
        currentSource.errorMessage?.let { StatusText(it, color = AppError) }
        Hairline()
        if (items.isEmpty()) {
            StatusText(
                if (isScanning) "扫描完成后，内容会显示在这里。" else "还没有缓存内容；点击刷新开始发现。",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { "source-item-${currentSource.id}-${it.uri}" }) { media ->
                    SourceMediaRow(
                        media = media,
                        decision = library.config.decisionFor(media),
                        isUnavailable = library.isUnavailable(media),
                        onPlay = { onPlay(media) },
                        onCollect = { onCollect(media) },
                        onRemoveIncluded = { onRemoveIncluded(setOf(media.uri.toString())) },
                        onIgnore = { onIgnore(media) },
                        onRestore = { onRestore(media) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceMediaRow(
    media: MediaSourceRef,
    decision: LibraryDecision,
    isUnavailable: Boolean,
    onPlay: () -> Unit,
    onCollect: () -> Unit,
    onRemoveIncluded: () -> Unit,
    onIgnore: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isUnavailable, onClick = onPlay)
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    listOfNotNull(
                        "不可用".takeIf { isUnavailable },
                        when (decision) {
                            LibraryDecision.Included -> "已收录"
                            LibraryDecision.Ignored -> "已忽略"
                            LibraryDecision.Candidate -> "新发现"
                        },
                        candidateDetail(media, false),
                    ).joinToString(" · "),
                    color = AppMutedText,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("播放", color = AppSecondaryText, fontSize = 11.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            when (decision) {
                LibraryDecision.Included ->
                    TextButton(onClick = onRemoveIncluded) {
                        Text("移出我的内容", color = AppMutedText, fontSize = 11.sp)
                    }
                LibraryDecision.Candidate -> {
                    TextButton(onClick = onCollect) {
                        Text("加入我的内容", color = AppSecondaryText, fontSize = 11.sp)
                    }
                    TextButton(onClick = onIgnore) {
                        Text("忽略", color = AppMutedText, fontSize = 11.sp)
                    }
                }
                LibraryDecision.Ignored ->
                    TextButton(onClick = onRestore) {
                        Text("恢复发现", color = AppSecondaryText, fontSize = 11.sp)
                    }
            }
        }
        Hairline()
    }
}

@Composable
private fun OpenContentDialog(
    draft: SessionDraft,
    onPickMedia: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isChecking = draft.media is DraftMedia.Checking
    val isUnavailable = draft.media is DraftMedia.Unavailable
    AlertDialog(
        onDismissRequest = { if (isChecking) onCancel() else onDismiss() },
        title = { Text("打开内容", color = AppPrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "选择一个音频或视频后会直接进入播放器；同步文字可以在播放现场继续添加。",
                    color = AppSecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                when (val media = draft.media) {
                    is DraftMedia.Checking ->
                        StatusText("正在打开 ${media.source.displayName}…")
                    is DraftMedia.Unavailable ->
                        StatusText(media.message, color = AppError)
                    DraftMedia.None,
                    is DraftMedia.Ready,
                    -> StatusText("不会自动加入“我的内容”，但会保留在最近记录中。")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (isChecking) onCancel else onPickMedia) {
                Text(
                    if (isChecking) "取消打开" else if (isUnavailable) "重新选择" else "选择内容",
                    color = if (isChecking) AppMutedText else AppAccent,
                )
            }
        },
        dismissButton = {
            if (!isChecking) {
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = AppSecondaryText)
                }
            }
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
    isCollected: Boolean,
    onDismiss: () -> Unit,
    onAddToTonight: () -> Unit,
    onChoosePlaylist: () -> Unit,
    onAddToMyContent: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayTitle, color = AppPrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onAddToTonight) { Text("加入今晚", color = AppPrimaryText) }
                TextButton(onClick = onChoosePlaylist) { Text("添加到播放列表", color = AppPrimaryText) }
                if (isCollected) {
                    TextButton(onClick = onRemoveFromLibrary) {
                        Text("移出我的内容", color = AppSecondaryText)
                    }
                } else {
                    TextButton(onClick = onAddToMyContent) {
                        Text("加入我的内容", color = AppAccent)
                    }
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
    modifier: Modifier = Modifier,
    color: Color = AppMutedText,
) {
    Text(text, modifier = modifier, color = color, fontSize = 11.sp, lineHeight = 16.sp)
}

private fun availablePlaylists(library: MediaLibraryState): List<ResolvedPlaylist> =
    library.config.playlists.map { playlist ->
        ResolvedPlaylist(
            id = playlist.id,
            name = playlist.name,
            items = playlist.items,
        )
    }

private fun resolvePlaylist(
    playlistId: String,
    library: MediaLibraryState,
): ResolvedPlaylist? =
    availablePlaylists(library).firstOrNull { it.id == playlistId }

private fun resolveRecentItems(library: MediaLibraryState): List<MediaSourceRef> =
    distinctContentItems(
        items =
            library.config.recentItems.map { recent ->
                library.config.contentUnitFor(recent)?.media ?: recent
            },
        library = library,
    )

private fun orderedCollectedItems(
    library: MediaLibraryState,
    recentItems: List<MediaSourceRef>,
): List<MediaSourceRef> =
    orderCollectedContent(
        recentItems = recentItems,
        collectedItems = library.items,
        stableKey = { media ->
            library.config.contentUnitFor(media)?.stableId ?: "uri:${media.uri}"
        },
        isCollected = library.config::isCollected,
    )

private fun distinctContentItems(
    items: List<MediaSourceRef>,
    library: MediaLibraryState,
): List<MediaSourceRef> =
    items.distinctBy { media ->
        library.config.contentUnitFor(media)?.stableId ?: "uri:${media.uri}"
    }

private fun MediaLibraryState.isUnavailable(media: MediaSourceRef): Boolean =
    config.contentUnitFor(media)?.let {
        it.availability == ContentAvailability.Unavailable
    } ?: (sources.firstOrNull { it.id == media.sourceId }?.hasAccess == false)

private fun sourceSummary(library: MediaLibraryState): String {
    val looseItemCount =
        library.sources
            .firstOrNull { it.kind == LibrarySourceKind.LooseFiles }
            ?.itemCount
            ?: 0
    return buildList {
        if (library.config.folders.isNotEmpty()) add("${library.config.folders.size} 个文件夹")
        if (library.hasSystemAccess) add("设备媒体库")
        if (looseItemCount > 0) add("$looseItemCount 项单独打开记录")
    }.ifEmpty {
        listOf("添加文件夹或管理设备媒体库")
    }.joinToString(" · ")
}

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

private fun systemLibraryAccessSummary(library: MediaLibraryState): String =
    listOf(
        if (library.hasAudioAccess) "音频已授权" else "音频未授权",
        when (library.videoAccess) {
            VideoLibraryAccess.Full -> "视频已完整授权"
            VideoLibraryAccess.Partial -> "视频仅限所选范围"
            VideoLibraryAccess.None -> "视频未授权"
        },
    ).joinToString(" · ")

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

private const val HomePreviewItemCount = 3
