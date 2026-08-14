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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import io.github.zvensmoluya.vortexcue.core.model.MediaKind
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.displayArtist
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.data.ContentDisposition
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.ContentSummary
import io.github.zvensmoluya.vortexcue.data.HomeLibraryProjection
import io.github.zvensmoluya.vortexcue.data.PlaybackRecoveryProjection
import io.github.zvensmoluya.vortexcue.data.PlaylistSummary
import io.github.zvensmoluya.vortexcue.data.SourceSummary
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
import io.github.zvensmoluya.vortexcue.library.LibrarySourceIds
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
import io.github.zvensmoluya.vortexcue.ui.player.PlaybackSnapshot
import io.github.zvensmoluya.vortexcue.ui.player.formatTime
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppBackground
import io.github.zvensmoluya.vortexcue.ui.theme.AppError
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurface
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal enum class HomeSection {
    Home,
    Tonight,
    MyContent,
    Discoveries,
    Sources,
    Display,
}

@Composable
internal fun HomeScreen(
    section: HomeSection,
    onSectionChanged: (HomeSection) -> Unit,
    repository: ContentRepository,
    home: HomeLibraryProjection,
    coordinator: LibraryCoordinatorState,
    draft: ContentSummary?,
    recovery: PlaybackRecoveryProjection,
    runtimeCurrentIndex: Int,
    runtimePositionMs: Long,
    playback: PlaybackSnapshot,
    displayProfile: DisplayProfile,
    onPickMedia: () -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshSource: (String) -> Unit,
    onCancelScan: (String) -> Unit,
    onAddFolder: () -> Unit,
    onRelinkFolder: (String) -> Unit,
    onRemoveSource: (String) -> Unit,
    onCollect: (String) -> Unit,
    onUncollect: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onRestoreIgnored: (String) -> Unit,
    onPlaySingle: (String) -> Unit,
    onPlayGroup: (List<String>, Int) -> Unit,
    onSelectTonight: (String) -> Unit,
    onAddToTonight: (String) -> Unit,
    onRemoveFromTonight: (String) -> Unit,
    onMoveTonight: (Int, Int) -> Unit,
    onClearTonight: () -> Unit,
    onStartTonight: () -> Unit,
    onCreatePlaylist: (String, String?) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    syncTextTaskCount: Int,
    onOpenSyncTextTasks: () -> Unit,
    onOpenSyncTextSchemes: () -> Unit,
    onGenerateSyncText: (ContentDetails) -> Unit,
    onOpenGeneratedTextResult: (String) -> Unit,
    onRelinkContent: (String) -> Unit,
    onSelectContentTextTrack: (String, TextTrackSlot, String?) -> Unit,
    onRestoreAutomaticContentTextTrack: (String, TextTrackSlot) -> Unit,
    onAddContentTextTrack: (String, TextTrackSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(MyContentTab.Collected) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showingIgnored by rememberSaveable { mutableStateOf(false) }
    var contentDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var createPlaylistVisible by rememberSaveable { mutableStateOf(false) }
    var createPlaylistSeed by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val collectedFlow = remember(repository) { repository.pagedCollected() }
    val recentFlow = remember(repository) { repository.pagedRecent() }
    val discoveryFlow = remember(repository) { repository.pagedDiscovery() }
    val ignoredFlow = remember(repository) { repository.pagedIgnored() }
    val selectedPlaylistFlow =
        remember(repository, selectedPlaylistId) {
            selectedPlaylistId?.let(repository::pagedPlaylist) ?: flowOf(PagingData.empty())
        }
    val selectedSourceFlow =
        remember(repository, selectedSourceId) {
            selectedSourceId?.let(repository::pagedSource) ?: flowOf(PagingData.empty())
        }
    val searchFlow =
        remember(repository, searchQuery) {
            searchQuery.takeIf(String::isNotBlank)?.let(repository::pagedSearch)
                ?: flowOf(PagingData.empty())
        }
    val collected = collectedFlow.collectAsLazyPagingItems()
    val recent = recentFlow.collectAsLazyPagingItems()
    val discovery = discoveryFlow.collectAsLazyPagingItems()
    val ignored = ignoredFlow.collectAsLazyPagingItems()
    val playlistItems = selectedPlaylistFlow.collectAsLazyPagingItems()
    val sourceItems = selectedSourceFlow.collectAsLazyPagingItems()
    val searchItems = searchFlow.collectAsLazyPagingItems()

    val detailFlow: Flow<ContentDetails?> =
        remember(repository, contentDetailId) {
            contentDetailId?.let(repository::observeDetails) ?: flowOf(null)
        }
    val details by detailFlow.collectAsState(initial = null)

    val nested = selectedPlaylistId != null || selectedSourceId != null || searchVisible || showingIgnored
    BackHandler(enabled = section != HomeSection.Home || nested) {
        when {
            selectedPlaylistId != null -> selectedPlaylistId = null
            selectedSourceId != null -> selectedSourceId = null
            searchVisible -> {
                searchVisible = false
                searchQuery = ""
            }
            showingIgnored -> showingIgnored = false
            else -> onSectionChanged(HomeSection.Home)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeHeader(
            section = section,
            nestedTitle =
                selectedPlaylistId?.let { id -> home.playlists.firstOrNull { it.playlistId == id }?.name }
                    ?: selectedSourceId?.let { id -> home.sources.firstOrNull { it.sourceId == id }?.displayName }
                    ?: "搜索".takeIf { searchVisible }
                    ?: "已忽略".takeIf { showingIgnored },
            onBack = {
                when {
                    selectedPlaylistId != null -> selectedPlaylistId = null
                    selectedSourceId != null -> selectedSourceId = null
                    searchVisible -> searchVisible = false
                    showingIgnored -> showingIgnored = false
                    else -> onSectionChanged(HomeSection.Home)
                }
            },
            onSearch = {
                selectedPlaylistId = null
                searchVisible = true
            },
            onDisplay = { onSectionChanged(HomeSection.Display) },
            syncTextTaskCount = syncTextTaskCount,
            onOpenSyncTextTasks = onOpenSyncTextTasks,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                HomeSection.Home ->
                    HomeLanding(
                        home = home,
                        draft = draft,
                        recovery = recovery,
                        runtimePositionMs = runtimePositionMs,
                        playback = playback,
                        onContinue = onContinue,
                        onPlayPause = onPlayPause,
                        onPlay = onPlaySingle,
                        onDetails = { contentDetailId = it },
                        onOpenMyContent = { onSectionChanged(HomeSection.MyContent) },
                        onOpenDiscoveries = { onSectionChanged(HomeSection.Discoveries) },
                        onOpenTonight = { onSectionChanged(HomeSection.Tonight) },
                        onOpenSources = { onSectionChanged(HomeSection.Sources) },
                        onPickMedia = onPickMedia,
                        onAddFolder = onAddFolder,
                    )
                HomeSection.Tonight ->
                    TonightEditor(
                        recovery = recovery,
                        currentIndex = runtimeCurrentIndex,
                        onStart = onStartTonight,
                        onPlay = onSelectTonight,
                        onDetails = { contentDetailId = it },
                        onRemove = onRemoveFromTonight,
                        onMove = onMoveTonight,
                        onClear = onClearTonight,
                    )
                HomeSection.MyContent ->
                    when {
                        selectedPlaylistId != null ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { playlistDeleteId = selectedPlaylistId }) {
                                        Text("删除播放列表", color = AppError)
                                    }
                                }
                                PagedContentList(
                                    items = playlistItems,
                                    emptyText = "播放列表为空",
                                    onPlay = onPlaySingle,
                                    onDetails = { contentDetailId = it },
                                    modifier = Modifier.weight(1f),
                                    trailing = { summary ->
                                        TextButton(onClick = {
                                            onRemoveFromPlaylist(selectedPlaylistId!!, summary.contentId)
                                        }) { Text("移出", color = AppSecondaryText) }
                                    },
                                )
                            }
                        searchVisible ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("搜索标题、作者或专辑") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                PagedContentList(
                                    items = searchItems,
                                    emptyText = if (searchQuery.isBlank()) "输入关键词" else "没有匹配内容",
                                    onPlay = onPlaySingle,
                                    onDetails = { contentDetailId = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        else ->
                            MyContent(
                                tab = selectedTab,
                                onTabChanged = { selectedTab = it },
                                collected = collected,
                                recent = recent,
                                playlists = home.playlists,
                                onOpenPlaylist = { selectedPlaylistId = it },
                                onCreatePlaylist = {
                                    createPlaylistSeed = null
                                    createPlaylistVisible = true
                                },
                                onPlay = onPlaySingle,
                                onPlayLoaded = onPlayGroup,
                                onDetails = { contentDetailId = it },
                            )
                    }
                HomeSection.Discoveries ->
                    Discoveries(
                        showingIgnored = showingIgnored,
                        onToggleIgnored = { showingIgnored = !showingIgnored },
                        items = if (showingIgnored) ignored else discovery,
                        onPlay = onPlaySingle,
                        onDetails = { contentDetailId = it },
                        onAccept = onCollect,
                        onIgnore = onIgnore,
                        onRestore = onRestoreIgnored,
                    )
                HomeSection.Sources ->
                    if (selectedSourceId != null) {
                        SourceDetail(
                            source = home.sources.firstOrNull { it.sourceId == selectedSourceId },
                            items = sourceItems,
                            isScanning = selectedSourceId in coordinator.scanningSourceIds,
                            onRefresh = { selectedSourceId?.let(onRefreshSource) },
                            onCancel = { selectedSourceId?.let(onCancelScan) },
                            onRelink = { selectedSourceId?.let(onRelinkFolder) },
                            onRemove = {
                                selectedSourceId?.let(onRemoveSource)
                                selectedSourceId = null
                            },
                            onPlay = onPlaySingle,
                            onDetails = { contentDetailId = it },
                        )
                    } else {
                        Sources(
                            sources = home.sources,
                            coordinator = coordinator,
                            onOpen = { selectedSourceId = it },
                            onRequestAccess = onRequestLibraryAccess,
                            onRefreshAll = onRefreshLibrary,
                            onAddFolder = onAddFolder,
                        )
                    }
                HomeSection.Display ->
                    DisplaySettings(displayProfile, onDisplayProfileChanged)
            }
        }

        if (section != HomeSection.Display && selectedPlaylistId == null && selectedSourceId == null && !searchVisible) {
            HomeNavigation(section, onSectionChanged)
        }
    }

    details?.let { content ->
        ContentDetailDialog(
            details = content,
            playlists = home.playlists,
            onDismiss = { contentDetailId = null },
            onPlay = onPlaySingle,
            onAddTonight = onAddToTonight,
            onCollect = onCollect,
            onUncollect = onUncollect,
            onIgnore = onIgnore,
            onRelink = onRelinkContent,
            onChoosePlaylist = { playlistTargetId = content.contentId },
            onSelectTrack = onSelectContentTextTrack,
            onRestoreAutomatic = onRestoreAutomaticContentTextTrack,
            onAddTrack = onAddContentTextTrack,
            onGenerateSyncText = { onGenerateSyncText(content) },
            onOpenSyncTextSchemes = onOpenSyncTextSchemes,
            onOpenGeneratedTextResult = onOpenGeneratedTextResult,
        )
    }

    playlistTargetId?.let { contentId ->
        PlaylistChooser(
            playlists = home.playlists,
            onChoose = {
                onAddToPlaylist(it, contentId)
                playlistTargetId = null
            },
            onCreate = {
                createPlaylistSeed = contentId
                createPlaylistVisible = true
                playlistTargetId = null
            },
            onDismiss = { playlistTargetId = null },
        )
    }

    if (createPlaylistVisible) {
        CreatePlaylistDialog(
            onCreate = { name ->
                onCreatePlaylist(name, createPlaylistSeed)
                createPlaylistVisible = false
                createPlaylistSeed = null
            },
            onDismiss = { createPlaylistVisible = false },
        )
    }

    playlistDeleteId?.let { playlistId ->
        val playlistName = home.playlists.firstOrNull { it.playlistId == playlistId }?.name ?: "此播放列表"
        AlertDialog(
            onDismissRequest = { playlistDeleteId = null },
            title = { Text("删除播放列表") },
            text = { Text("将删除“$playlistName”，其中的内容仍会保留在我的内容中。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(playlistId)
                        if (selectedPlaylistId == playlistId) selectedPlaylistId = null
                        playlistDeleteId = null
                    },
                ) { Text("删除", color = AppError) }
            },
            dismissButton = {
                TextButton(onClick = { playlistDeleteId = null }) {
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
    nestedTitle: String?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onDisplay: () -> Unit,
    syncTextTaskCount: Int,
    onOpenSyncTextTasks: () -> Unit,
) {
    val title = nestedTitle ?: when (section) {
        HomeSection.Home -> "VortexCue"
        HomeSection.Tonight -> "今晚"
        HomeSection.MyContent -> "我的内容"
        HomeSection.Discoveries -> "待决定"
        HomeSection.Sources -> "来源"
        HomeSection.Display -> "显示"
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (section != HomeSection.Home || nestedTitle != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("返回", color = AppSecondaryText)
            }
        }
        Text(
            text = title,
            color = AppPrimaryText,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (section == HomeSection.MyContent && nestedTitle == null) {
            TextButton(onClick = onSearch) { Text("搜索", color = AppAccent) }
        }
        if (syncTextTaskCount > 0) {
            TextButton(onClick = onOpenSyncTextTasks) {
                Text("文字任务 $syncTextTaskCount", color = AppAccent, fontSize = 12.sp)
            }
        }
        if (section == HomeSection.Home) {
            TextButton(onClick = onDisplay) { Text("显示", color = AppSecondaryText) }
        }
    }
    Hairline()
}

@Composable
private fun HomeLanding(
    home: HomeLibraryProjection,
    draft: ContentSummary?,
    recovery: PlaybackRecoveryProjection,
    runtimePositionMs: Long,
    playback: PlaybackSnapshot,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onPlay: (String) -> Unit,
    onDetails: (String) -> Unit,
    onOpenMyContent: () -> Unit,
    onOpenDiscoveries: () -> Unit,
    onOpenTonight: () -> Unit,
    onOpenSources: () -> Unit,
    onPickMedia: () -> Unit,
    onAddFolder: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        recovery.currentItem?.let { current ->
            item(key = "continue") {
                SectionHeading("继续")
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onContinue).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(current.media.displayTitle, color = AppPrimaryText, fontSize = 16.sp)
                        Text(
                            "${formatTime(runtimePositionMs)} / ${formatTime(current.media.durationMs)}",
                            color = AppSecondaryText,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onPlayPause) {
                        Text(if (playback.isPlaying) "暂停" else "继续", color = AppAccent)
                    }
                }
            }
        }
        draft?.takeIf { it.contentId != recovery.currentItem?.contentId }?.let { pending ->
            item(key = "draft") {
                SectionHeading("待处理")
                SummaryRow(pending, onPlay, onDetails)
            }
        }
        item(key = "counts") {
            SectionHeading("目录")
            LandingAction("我的内容", "${home.collectedCount} 项收录 · ${home.recentCount} 项最近", onOpenMyContent)
            LandingAction("待决定", "${home.discoveryCount} 项待决定 · ${home.ignoredCount} 项已忽略", onOpenDiscoveries)
            LandingAction("今晚", "${recovery.queue.size} 项", onOpenTonight)
            LandingAction("来源", sourceSummary(home.sources), onOpenSources)
        }
        if (home.recentPreview.isNotEmpty()) {
            item { SectionHeading("最近打开") }
            items(home.recentPreview, key = { "recent-${it.contentId}" }) { item ->
                SummaryRow(item, onPlay, onDetails)
            }
        } else if (home.collectedPreview.isNotEmpty()) {
            item { SectionHeading("已收录") }
            items(home.collectedPreview, key = { "collected-${it.contentId}" }) { item ->
                SummaryRow(item, onPlay, onDetails)
            }
        }
        item(key = "imports") {
            SectionHeading("导入")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("打开文件", onPickMedia, Modifier.weight(1f))
                QuietButton("添加文件夹", onAddFolder, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LandingAction(title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = AppPrimaryText, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(detail, color = AppSecondaryText, fontSize = 12.sp, maxLines = 1)
    }
    Hairline()
}

@Composable
private fun TonightEditor(
    recovery: PlaybackRecoveryProjection,
    currentIndex: Int,
    onStart: () -> Unit,
    onPlay: (String) -> Unit,
    onDetails: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${recovery.queue.size} 项", color = AppSecondaryText, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear, enabled = recovery.queue.isNotEmpty()) { Text("清空", color = AppError) }
            OutlinedButton(onClick = onStart, enabled = recovery.queue.isNotEmpty()) { Text("开始") }
        }
        if (recovery.queue.isEmpty()) {
            EmptyState("今晚还没有内容")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(recovery.queue.size, key = { recovery.queue[it].contentId }) { index ->
                    val item = recovery.queue[index]
                    Row(
                        modifier = Modifier.fillMaxWidth().background(if (index == currentIndex) AppSurface else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable { onPlay(item.contentId) }.padding(vertical = 10.dp),
                        ) {
                            Text(item.media.displayTitle, color = AppPrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (index == currentIndex) "当前" else "第 ${index + 1} 项", color = AppSecondaryText, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) { Text("上移") }
                        TextButton(onClick = { onMove(index, index + 1) }, enabled = index < recovery.queue.lastIndex) { Text("下移") }
                        TextButton(onClick = { onDetails(item.contentId) }) { Text("详情") }
                        TextButton(onClick = { onRemove(item.contentId) }) { Text("移除", color = AppError) }
                    }
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun MyContent(
    tab: MyContentTab,
    onTabChanged: (MyContentTab) -> Unit,
    collected: LazyPagingItems<ContentSummary>,
    recent: LazyPagingItems<ContentSummary>,
    playlists: List<PlaylistSummary>,
    onOpenPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onPlay: (String) -> Unit,
    onPlayLoaded: (List<String>, Int) -> Unit,
    onDetails: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Segment("收录", tab == MyContentTab.Collected) { onTabChanged(MyContentTab.Collected) }
            Segment("最近", tab == MyContentTab.Recent) { onTabChanged(MyContentTab.Recent) }
            Segment("播放列表", tab == MyContentTab.Playlists) { onTabChanged(MyContentTab.Playlists) }
        }
        when (tab) {
            MyContentTab.Collected ->
                PagedContentList(
                    items = collected,
                    emptyText = "还没有收录内容",
                    onPlay = onPlay,
                    onDetails = onDetails,
                    onPlayLoaded = onPlayLoaded,
                    modifier = Modifier.weight(1f),
                )
            MyContentTab.Recent ->
                PagedContentList(
                    items = recent,
                    emptyText = "还没有最近打开内容",
                    onPlay = onPlay,
                    onDetails = onDetails,
                    onPlayLoaded = onPlayLoaded,
                    modifier = Modifier.weight(1f),
                )
            MyContentTab.Playlists ->
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        QuietButton("新建播放列表", onCreatePlaylist, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                    }
                    items(playlists, key = PlaylistSummary::playlistId) { playlist ->
                        LandingAction(playlist.name, "打开", { onOpenPlaylist(playlist.playlistId) })
                    }
                    if (playlists.isEmpty()) item { EmptyState("还没有播放列表") }
                }
        }
    }
}

@Composable
private fun Discoveries(
    showingIgnored: Boolean,
    onToggleIgnored: () -> Unit,
    items: LazyPagingItems<ContentSummary>,
    onPlay: (String) -> Unit,
    onDetails: (String) -> Unit,
    onAccept: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (showingIgnored) "已忽略内容仍会保留其身份" else "逐项决定是否纳入我的内容",
                color = AppSecondaryText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleIgnored) {
                Text(if (showingIgnored) "待决定" else "已忽略", color = AppAccent)
            }
        }
        PagedContentList(
            items = items,
            emptyText = if (showingIgnored) "没有已忽略内容" else "没有待决定内容",
            onPlay = onPlay,
            onDetails = onDetails,
            trailing = { summary ->
                if (showingIgnored) {
                    TextButton(onClick = { onRestore(summary.contentId) }) { Text("恢复", color = AppAccent) }
                } else {
                    TextButton(onClick = { onAccept(summary.contentId) }) { Text("收录", color = AppAccent) }
                    TextButton(onClick = { onIgnore(summary.contentId) }) { Text("忽略", color = AppSecondaryText) }
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Sources(
    sources: List<SourceSummary>,
    coordinator: LibraryCoordinatorState,
    onOpen: (String) -> Unit,
    onRequestAccess: () -> Unit,
    onRefreshAll: () -> Unit,
    onAddFolder: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("媒体授权", onRequestAccess, Modifier.weight(1f))
                QuietButton("添加文件夹", onAddFolder, Modifier.weight(1f))
                QuietButton("全部刷新", onRefreshAll, Modifier.weight(1f))
            }
            coordinator.errorMessage?.let { Text(it, color = AppError, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
        }
        items(sources, key = SourceSummary::sourceId) { source ->
            val detail = buildString {
                append("${source.itemCount} 项")
                if (!source.hasAccess) append(" · 授权失效")
                if (source.sourceId in coordinator.scanningSourceIds) append(" · 扫描中")
            }
            LandingAction(source.displayName, detail) { onOpen(source.sourceId) }
        }
        if (sources.isEmpty()) item { EmptyState("还没有可用来源") }
    }
}

@Composable
private fun SourceDetail(
    source: SourceSummary?,
    items: LazyPagingItems<ContentSummary>,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onRelink: () -> Unit,
    onRemove: () -> Unit,
    onPlay: (String) -> Unit,
    onDetails: (String) -> Unit,
) {
    if (source == null) {
        EmptyState("来源已不存在")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietButton(if (isScanning) "停止" else "刷新", if (isScanning) onCancel else onRefresh)
            if (source.kind == LibrarySourceKind.Folder) QuietButton("重新授权", onRelink)
            if (source.sourceId != LibrarySourceIds.SystemLibrary) QuietButton("移除来源", onRemove, danger = true)
        }
        source.lastError?.let { Text(it, color = AppError, fontSize = 12.sp) }
        PagedContentList(
            items = items,
            emptyText = "来源中没有可显示内容",
            onPlay = onPlay,
            onDetails = onDetails,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PagedContentList(
    items: LazyPagingItems<ContentSummary>,
    emptyText: String,
    onPlay: (String) -> Unit,
    onDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlayLoaded: ((List<String>, Int) -> Unit)? = null,
    trailing: @Composable (ContentSummary) -> Unit = {},
) {
    when {
        items.loadState.refresh is LoadState.Loading -> EmptyState("正在加载…", modifier)
        items.loadState.refresh is LoadState.Error ->
            Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("加载失败")
                TextButton(onClick = items::retry) { Text("重试", color = AppAccent) }
            }
        items.itemCount == 0 -> EmptyState(emptyText, modifier)
        else ->
            LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(items.itemCount, key = { index -> items.peek(index)?.contentId ?: "placeholder-$index" }) { index ->
                    items[index]?.let { summary ->
                        SummaryRow(
                            summary = summary,
                            onPlay = {
                                val loaded = items.itemSnapshotList.items.map(ContentSummary::contentId)
                                val loadedIndex = loaded.indexOf(summary.contentId)
                                if (onPlayLoaded != null && loadedIndex >= 0) onPlayLoaded(loaded, loadedIndex)
                                else onPlay(summary.contentId)
                            },
                            onDetails = onDetails,
                            trailing = { trailing(summary) },
                        )
                    }
                }
                if (items.loadState.append is LoadState.Loading) item { EmptyState("正在加载更多…") }
                if (items.loadState.append is LoadState.Error) {
                    item { TextButton(onClick = items::retry) { Text("加载更多失败，重试", color = AppAccent) } }
                }
            }
    }
}

@Composable
private fun SummaryRow(
    summary: ContentSummary,
    onPlay: (String) -> Unit,
    onDetails: (String) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f).clickable { onPlay(summary.contentId) }.padding(vertical = 9.dp),
        ) {
            Text(
                summary.media.displayTitle,
                color = if (summary.availability == ContentAvailability.Unavailable) AppSecondaryText else AppPrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    summary.media.displayArtist,
                    formatTime(summary.media.durationMs).takeIf { summary.media.durationMs > 0L },
                    "不可用".takeIf { summary.availability == ContentAvailability.Unavailable },
                ).joinToString(" · ").ifBlank { summary.media.displayName },
                color = AppSecondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
        TextButton(onClick = { onDetails(summary.contentId) }) { Text("详情", color = AppSecondaryText) }
    }
    Hairline()
}

@Composable
private fun ContentDetailDialog(
    details: ContentDetails,
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onAddTonight: (String) -> Unit,
    onCollect: (String) -> Unit,
    onUncollect: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onRelink: (String) -> Unit,
    onChoosePlaylist: () -> Unit,
    onSelectTrack: (String, TextTrackSlot, String?) -> Unit,
    onRestoreAutomatic: (String, TextTrackSlot) -> Unit,
    onAddTrack: (String, TextTrackSlot) -> Unit,
    onGenerateSyncText: () -> Unit,
    onOpenSyncTextSchemes: () -> Unit,
    onOpenGeneratedTextResult: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(details.activeMedia.displayTitle, color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    details.activeMedia.displayArtist?.let { Text(it, color = AppSecondaryText) }
                }
                TextButton(onClick = onDismiss) { Text("关闭", color = AppSecondaryText) }
            }
            Hairline()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuietButton("播放", { onPlay(details.contentId) })
                QuietButton("加入今晚", { onAddTonight(details.contentId) })
                QuietButton("播放列表", onChoosePlaylist, enabled = playlists.isNotEmpty())
                QuietButton("重新定位", { onRelink(details.contentId) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (details.disposition) {
                    ContentDisposition.Collected -> QuietButton("取消收录", { onUncollect(details.contentId) })
                    ContentDisposition.Ignored -> QuietButton("收录", { onCollect(details.contentId) })
                    null -> {
                        QuietButton("收录", { onCollect(details.contentId) })
                        QuietButton("忽略", { onIgnore(details.contentId) })
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SectionHeading("媒体定位")
                    details.mediaLocators.forEach { locator ->
                        Text(
                            text = (if (locator.stableId == details.activeMediaLocatorId) "当前 · " else "") + locator.media.uri,
                            color = if (locator.availability == ContentAvailability.Unavailable) AppError else AppSecondaryText,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                item {
                    SectionHeading("同步文字")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuietButton("生成", onGenerateSyncText)
                        QuietButton("方案", onOpenSyncTextSchemes)
                    }
                    TextTrackSlot.entries.forEach { slot ->
                        TextTrackSlotEditor(
                            details = details,
                            slot = slot,
                            onSelect = { onSelectTrack(details.contentId, slot, it) },
                            onAutomatic = { onRestoreAutomatic(details.contentId, slot) },
                            onAdd = { onAddTrack(details.contentId, slot) },
                            onOpenGeneratedResult = onOpenGeneratedTextResult,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextTrackSlotEditor(
    details: ContentDetails,
    slot: TextTrackSlot,
    onSelect: (String?) -> Unit,
    onAutomatic: () -> Unit,
    onAdd: () -> Unit,
    onOpenGeneratedResult: (String) -> Unit,
) {
    val selectedId = when (slot) {
        TextTrackSlot.Primary -> details.activeText.primaryTrackId
        TextTrackSlot.Secondary -> details.activeText.secondaryTrackId
    }
    val mode = when (slot) {
        TextTrackSlot.Primary -> details.activeText.primaryMode
        TextTrackSlot.Secondary -> details.activeText.secondaryMode
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (slot == TextTrackSlot.Primary) "主文字" else "第二文字", color = AppPrimaryText, modifier = Modifier.weight(1f))
        if (mode == TextSelectionMode.Manual) {
            TextButton(onClick = onAutomatic) { Text("恢复自动", color = AppAccent) }
        }
        TextButton(onClick = onAdd) { Text("添加", color = AppAccent) }
    }
    details.timedTextTracks.forEach { track ->
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onSelect(track.stableId) }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (track.stableId == selectedId) "已选" else "", color = AppAccent, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.label ?: track.locator.displayName, color = AppPrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trackDetail(track), color = AppSecondaryText, fontSize = 11.sp)
            }
            track.locator.generatedSlotId?.let { slotId ->
                TextButton(onClick = { onOpenGeneratedResult(slotId) }) {
                    Text("结果", color = AppAccent, fontSize = 11.sp)
                }
            }
        }
    }
    if (selectedId != null) {
        TextButton(onClick = { onSelect(null) }) { Text("此槽不显示文字", color = AppSecondaryText) }
    }
}

@Composable
private fun PlaylistChooser(
    playlists: List<PlaylistSummary>,
    onChoose: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入播放列表") },
        text = {
            Column {
                playlists.forEach { playlist ->
                    TextButton(onClick = { onChoose(playlist.playlistId) }) {
                        Text(playlist.name, color = AppPrimaryText)
                    }
                }
                TextButton(onClick = onCreate) { Text("新建播放列表", color = AppAccent) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建播放列表") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("创建", color = AppAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun DisplaySettings(profile: DisplayProfile, onChanged: (DisplayProfile) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionHeading("预设")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DisplayProfile.Presets.forEach { preset ->
                    Segment(preset.name, profile.name == preset.name) { onChanged(preset) }
                }
            }
        }
        item {
            SettingSlider("窗口亮度", profile.glowWindowBrightness, 0.01f..0.30f) {
                onChanged(profile.copy(name = DisplayProfile.CustomName, glowWindowBrightness = it))
            }
            SettingSlider("文字亮度", profile.glowSubtitleLevel.toFloat(), 4f..96f) {
                onChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleLevel = it.toInt()))
            }
            SettingSlider("控制亮度", profile.glowControlLevel.toFloat(), 24f..160f) {
                onChanged(profile.copy(name = DisplayProfile.CustomName, glowControlLevel = it.toInt()))
            }
            SettingSlider("文字大小", profile.glowSubtitleSizeSp, 18f..36f) {
                onChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleSizeSp = it))
            }
            SettingSlider("文字位置", profile.glowSubtitleVerticalBias, -0.60f..0.60f) {
                onChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleVerticalBias = it))
            }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChanged: (Float) -> Unit) {
    Column {
        Row {
            Text(label, color = AppPrimaryText, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), color = AppSecondaryText, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChanged,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = AppAccent, activeTrackColor = AppAccent),
        )
    }
}

@Composable
private fun HomeNavigation(section: HomeSection, onChanged: (HomeSection) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(
            HomeSection.Home to "首页",
            HomeSection.Tonight to "今晚",
            HomeSection.MyContent to "内容",
            HomeSection.Discoveries to "发现",
            HomeSection.Sources to "来源",
        ).forEach { (target, label) ->
            TextButton(onClick = { onChanged(target) }) {
                Text(label, color = if (section == target) AppAccent else AppSecondaryText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, if (selected) AppAccent else AppHairline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) AppSurface else Color.Transparent),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
    ) { Text(label, color = if (selected) AppPrimaryText else AppSecondaryText, fontSize = 12.sp) }
}

@Composable
private fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.dp, if (danger) AppError.copy(alpha = 0.6f) else AppHairline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(label, color = if (danger) AppError else AppPrimaryText, fontSize = 12.sp) }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, color = AppSecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Hairline() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(AppHairline))
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = AppSecondaryText, fontSize = 13.sp)
    }
}

private fun sourceSummary(sources: List<SourceSummary>): String {
    val folders = sources.count { it.kind == LibrarySourceKind.Folder }
    val total = sources.sumOf(SourceSummary::itemCount)
    return "$total 项 · $folders 个文件夹"
}

private fun trackDetail(track: TimedTextTrack): String =
    listOfNotNull(
        when (track.origin) {
            TimedTextOrigin.Embedded -> "内嵌"
            TimedTextOrigin.Sidecar -> "外挂"
            TimedTextOrigin.Lyrics -> "歌词"
            TimedTextOrigin.Generated -> "生成"
        },
        track.language,
        track.format,
        track.readiness.name,
    ).joinToString(" · ")
