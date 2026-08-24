package io.github.zvensmoluya.vortexcue.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import io.github.zvensmoluya.vortexcue.R
import io.github.zvensmoluya.vortexcue.core.model.displayArtist
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.ContentSummary
import io.github.zvensmoluya.vortexcue.data.PlaylistBatchResult
import io.github.zvensmoluya.vortexcue.data.PlaylistSummary
import io.github.zvensmoluya.vortexcue.data.PlaybackRecoveryProjection
import io.github.zvensmoluya.vortexcue.data.SourceSummary
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
import io.github.zvensmoluya.vortexcue.playback.PlaybackGateway
import io.github.zvensmoluya.vortexcue.ui.player.PlaybackSnapshot
import io.github.zvensmoluya.vortexcue.ui.player.formatTime
import io.github.zvensmoluya.vortexcue.ui.queue.QueueItemRow
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextAction
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextConfigPage
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextUiState
import io.github.zvensmoluya.vortexcue.ui.synctext.generationProductStatus
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppBackground
import io.github.zvensmoluya.vortexcue.ui.theme.AppBoneLogo
import io.github.zvensmoluya.vortexcue.ui.theme.AppError
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceSelected
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MainTabScreen(
    selectedTab: MainTab,
    onTabChanged: (MainTab) -> Unit,
    openImportHubRequest: Int = 0,
    onImportHubRequestConsumed: () -> Unit = {},
    repository: ContentRepository,
    sources: List<SourceSummary>,
    coordinator: LibraryCoordinatorState,
    recovery: PlaybackRecoveryProjection,
    playback: PlaybackSnapshot,
    gateway: PlaybackGateway,
    displayProfile: DisplayProfile,
    playlistViewId: String?,
    onPlaylistViewChanged: (String?) -> Unit,
    playlistListState: LazyListState,
    playlistDetailListState: LazyListState,
    onPickMedia: () -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshSource: (String) -> Unit,
    onRelinkSource: (String) -> Unit,
    onCancelScan: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveSource: (String) -> Unit,
    onPlaySingle: (String) -> Unit,
    onPlayPlaylist: (String, List<String>, Int) -> Unit,
    onQueueSelect: (Int) -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onOpenSyncText: (ContentSummary) -> Unit,
    syncTextUiState: SyncTextUiState,
    onSyncTextAction: (SyncTextAction) -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    onCalibrateGlow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val playlists by repository.playlists.collectAsState(initial = emptyList())
    val recentPlaylists by repository.recentPlaylists.collectAsState(initial = emptyList())
    val allContent by repository.allContent.collectAsState(initial = emptyList())
    val sourceSummaries by repository.sources.collectAsState(initial = sources)
    val detailContentFlow = remember(repository, playlistViewId) {
        if (playlistViewId == null || playlistViewId == AllContentPlaylistId) repository.allContent
        else repository.observePlaylistContent(playlistViewId)
    }
    val detailContent by detailContentFlow.collectAsState(initial = emptyList())
    var importVisible by rememberSaveable { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<ContentSummary?>(null) }
    var selectedItemPlaylistIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistDialogMode by remember { mutableStateOf<PlaylistDialogMode?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var membershipItem by remember { mutableStateOf<ContentSummary?>(null) }
    var membershipInitialIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistToDelete by remember { mutableStateOf<PlaylistSummary?>(null) }
    var playlistReorderMode by rememberSaveable { mutableStateOf(false) }
    var contentReorderMode by rememberSaveable { mutableStateOf(false) }
    var contentOrganizeMode by rememberSaveable { mutableStateOf(false) }
    var organizedContentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistBatchTargetMode by remember { mutableStateOf<PlaylistBatchTargetMode?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val queueListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val settingsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(playlistViewId, playlists) {
        if (playlistViewId != null && playlistViewId != AllContentPlaylistId && playlists.none { it.playlistId == playlistViewId }) {
            onPlaylistViewChanged(null)
        }
    }
    LaunchedEffect(playlistViewId) {
        contentReorderMode = false
        contentOrganizeMode = false
        organizedContentIds = emptySet()
        playlistBatchTargetMode = null
    }
    LaunchedEffect(detailContent) {
        organizedContentIds = organizedContentIds.intersect(detailContent.mapTo(hashSetOf(), ContentSummary::contentId))
    }
    LaunchedEffect(openImportHubRequest) {
        if (openImportHubRequest > 0) {
            importVisible = true
            onImportHubRequestConsumed()
        }
    }
    LaunchedEffect(selectedItem?.contentId) {
        selectedItemPlaylistIds =
            selectedItem?.let { repository.playlistIdsForContent(it.contentId) }.orEmpty()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AppBackground,
        topBar = {
            if (selectedTab != MainTab.Playlists && selectedTab != MainTab.Config) {
                TopAppBar(
                    title = {
                        Text(
                            when (selectedTab) {
                                MainTab.Playlists -> "歌单"
                                MainTab.Queue -> "播放列表"
                                MainTab.Config -> "配置"
                                MainTab.Settings -> "设置"
                            },
                            color = AppPrimaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        when (selectedTab) {
                            MainTab.Playlists -> Unit
                            MainTab.Queue -> {
                                if (recovery.queue.isNotEmpty()) {
                                    IconButton(
                                        onClick = { scope.launch { gateway.clear() } },
                                        modifier = Modifier.semantics { contentDescription = "清空播放列表" },
                                    ) { Icon(Icons.Outlined.DeleteOutline, null, tint = AppSecondaryText) }
                                }
                            }
                            MainTab.Config,
                            MainTab.Settings,
                            -> Unit
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.background(AppBackground)) {
                if (recovery.queue.isNotEmpty()) {
                    MiniPlayer(
                        title = recovery.currentItem?.media?.displayTitle ?: "播放列表",
                        isPlaying = playback.isPlaying,
                        progress = if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f,
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                        onOpen = onContinue,
                        onPlayPause = onPlayPause,
                    )
                }
                MainNavigationBar(
                    selectedTab = selectedTab,
                    onTabChanged = onTabChanged,
                    onOpenImport = { importVisible = true },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { insets ->
        Box(modifier = Modifier.padding(insets).fillMaxSize()) {
            when (selectedTab) {
                MainTab.Playlists ->
                    if (playlistViewId == null) {
                        PlaylistOverview(
                            playlists = playlists,
                            recentPlaylists = recentPlaylists,
                            allContentCount = allContent.size,
                            reorderMode = playlistReorderMode,
                            listState = playlistListState,
                            onOpenAllContent = { onPlaylistViewChanged(AllContentPlaylistId) },
                            onOpenPlaylist = onPlaylistViewChanged,
                            onCreatePlaylist = {
                                playlistName = ""
                                playlistDialogMode = PlaylistDialogMode.Create
                            },
                            onRenamePlaylist = { playlist ->
                                playlistName = playlist.name
                                playlistDialogMode = PlaylistDialogMode.Rename(playlist.playlistId)
                            },
                            onDeletePlaylist = { playlistToDelete = it },
                            onReorderModeChanged = { playlistReorderMode = it },
                            onMovePlaylist = { from, to ->
                                val ids = playlists.filterNot { it.isDefaultFavorite }.map { it.playlistId }.toMutableList()
                                if (from in ids.indices && to in ids.indices) {
                                    ids.add(to, ids.removeAt(from))
                                    scope.launch { repository.reorderPlaylists(ids) }
                                }
                            },
                        )
                    } else {
                        val isAllContent = playlistViewId == AllContentPlaylistId
                        val playlist = playlists.firstOrNull { it.playlistId == playlistViewId }
                        PlaylistDetail(
                            title = if (isAllContent) "全部内容" else playlist?.name ?: "歌单",
                            content = detailContent,
                            isAllContent = isAllContent,
                            reorderMode = contentReorderMode,
                            listState = playlistDetailListState,
                            onBack = {
                                contentReorderMode = false
                                contentOrganizeMode = false
                                organizedContentIds = emptySet()
                                onPlaylistViewChanged(null)
                            },
                            onPlayAll = {
                                if (!isAllContent && detailContent.isNotEmpty()) {
                                    onPlayPlaylist(playlistViewId, detailContent.map { it.contentId }, 0)
                                }
                            },
                            onPlay = { index ->
                                if (isAllContent) detailContent.getOrNull(index)?.let { onPlaySingle(it.contentId) }
                                else onPlayPlaylist(playlistViewId, detailContent.map { it.contentId }, index)
                            },
                            onLongPress = { selectedItem = it },
                            onReorderModeChanged = {
                                contentReorderMode = it
                                if (it) {
                                    contentOrganizeMode = false
                                    organizedContentIds = emptySet()
                                }
                            },
                            onMove = { from, to ->
                                val ids = detailContent.map { it.contentId }.toMutableList()
                                if (!isAllContent && from in ids.indices && to in ids.indices) {
                                    ids.add(to, ids.removeAt(from))
                                    scope.launch { repository.reorderPlaylistItems(playlistViewId, ids) }
                                }
                            },
                            onAddContent = { importVisible = true },
                            organizeMode = contentOrganizeMode,
                            selectedContentIds = organizedContentIds,
                            onOrganizeModeChanged = { enabled ->
                                contentOrganizeMode = enabled
                                if (enabled) contentReorderMode = false else organizedContentIds = emptySet()
                            },
                            onSelectionChanged = { organizedContentIds = it },
                            onBatchAdd = { playlistBatchTargetMode = PlaylistBatchTargetMode.Add },
                            onBatchMove = { playlistBatchTargetMode = PlaylistBatchTargetMode.Move },
                            onBatchRemove = {
                                val currentPlaylistId = playlistViewId.takeIf { it != AllContentPlaylistId }
                                val selectedIds = organizedContentIds
                                if (currentPlaylistId != null && selectedIds.isNotEmpty()) {
                                    scope.launch {
                                        runCatching {
                                            repository.removeContentsFromPlaylist(selectedIds, currentPlaylistId)
                                        }.onSuccess { result ->
                                            notice = batchRemoveNotice(result)
                                            contentOrganizeMode = false
                                            organizedContentIds = emptySet()
                                        }.onFailure { notice = it.message ?: "无法移除所选内容" }
                                    }
                                }
                            },
                        )
                    }
                MainTab.Queue ->
                    QueueTab(
                        recovery = recovery,
                        onSelect = onQueueSelect,
                        onMove = { from, to -> scope.launch { gateway.move(from, to) } },
                        onRemove = { id -> scope.launch { gateway.remove(id) } },
                        listState = queueListState,
                    )
                MainTab.Config ->
                    ConfigTab(
                        state = syncTextUiState,
                        onAction = onSyncTextAction,
                    )
                MainTab.Settings ->
                    SettingsTab(
                        profile = displayProfile,
                        onProfileChanged = onDisplayProfileChanged,
                        onCalibrate = onCalibrateGlow,
                        coordinator = coordinator,
                        listState = settingsListState,
                    )
            }
            notice?.let { message ->
                Notice(message = message, onDismiss = { notice = null }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    selectedItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { selectedItem = null },
            containerColor = AppSurfaceRaised,
        ) {
            ItemActions(
                item = item,
                task = syncTextUiState.tasks.firstOrNull { it.contentId == item.contentId },
                onPlay = {
                    selectedItem = null
                    val currentPlaylistId = playlistViewId?.takeIf { it != AllContentPlaylistId }
                    val index = detailContent.indexOfFirst { it.contentId == item.contentId }
                    if (currentPlaylistId != null && index >= 0) {
                        onPlayPlaylist(currentPlaylistId, detailContent.map { it.contentId }, index)
                    } else {
                        onPlaySingle(item.contentId)
                    }
                },
                onAdd = {
                    selectedItem = null
                    scope.launch {
                        notice = if (recovery.queue.any { it.contentId == item.contentId }) {
                            "已在播放列表"
                        } else {
                            gateway.append(listOf(item.contentId))
                            "已加入播放列表"
                        }
                    }
                },
                onManagePlaylists = {
                    selectedItem = null
                    scope.launch {
                        membershipInitialIds = repository.playlistIdsForContent(item.contentId)
                        membershipItem = item
                    }
                },
                onRemoveFromPlaylist =
                    playlistViewId
                        ?.takeIf { it != AllContentPlaylistId }
                        ?.takeIf { currentPlaylistId ->
                            playlists.firstOrNull { it.playlistId == currentPlaylistId }?.let { playlist ->
                                !playlist.isDefaultFavorite || selectedItemPlaylistIds.size > 1
                            } == true
                        }
                        ?.let { currentPlaylistId ->
                        {
                            selectedItem = null
                            scope.launch {
                                repository.removeContentFromPlaylist(item.contentId, currentPlaylistId)
                                notice = "已从歌单移除"
                            }
                        }
                    },
                onSync = {
                    selectedItem = null
                    onOpenSyncText(item)
                },
                onIgnore = {
                    selectedItem = null
                    scope.launch {
                        repository.ignore(item.contentId)
                        notice = "已移出内容（原文件未删除）"
                    }
                },
                onCancelSync = {
                    selectedItem = null
                    onSyncTextAction(SyncTextAction.CancelForContent(item.contentId))
                },
                onRetrySync = {
                    selectedItem = null
                    onSyncTextAction(SyncTextAction.RetryForContent(item.contentId))
                },
                onAllowMeteredSync = {
                    selectedItem = null
                    onSyncTextAction(SyncTextAction.AllowMeteredNetworkForContent(item.contentId))
                },
            )
        }
    }

    membershipItem?.let { item ->
        PlaylistMembershipSheet(
            title = item.media.displayTitle,
            playlists = playlists,
            initialSelection = membershipInitialIds,
            onDismiss = { membershipItem = null },
            onSave = { selectedIds, newName ->
                scope.launch {
                    runCatching {
                        val finalIds =
                            newName?.let {
                                val createdId = repository.createPlaylist(it).playlistId
                                val defaultId = playlists.firstOrNull { playlist -> playlist.isDefaultFavorite }?.playlistId
                                val retained =
                                    if (defaultId != null && selectedIds == setOf(defaultId)) emptySet()
                                    else selectedIds
                                retained + createdId
                            } ?: selectedIds
                        repository.setContentPlaylists(item.contentId, finalIds)
                    }.onSuccess {
                        membershipItem = null
                        notice = "歌单已更新"
                    }
                        .onFailure { notice = it.message ?: "无法更新歌单" }
                }
            },
        )
    }

    playlistBatchTargetMode?.let { mode ->
        val currentPlaylistId = playlistViewId?.takeIf { it != AllContentPlaylistId }
        PlaylistBatchTargetSheet(
            mode = mode,
            selectedContentCount = organizedContentIds.size,
            playlists = playlists,
            excludedPlaylistId = currentPlaylistId,
            onDismiss = { playlistBatchTargetMode = null },
            onConfirm = { selectedTargetIds, newName ->
                val selectedContentIds = organizedContentIds
                scope.launch {
                    runCatching {
                        val created = newName?.let { repository.createPlaylist(it) }
                        when (mode) {
                            PlaylistBatchTargetMode.Add -> {
                                val targets = selectedTargetIds + listOfNotNull(created?.playlistId)
                                require(targets.isNotEmpty()) { "至少选择一个目标歌单" }
                                val result = repository.addContentsToPlaylists(selectedContentIds, targets)
                                Triple(result, targets.size, null)
                            }
                            PlaylistBatchTargetMode.Move -> {
                                val source = requireNotNull(currentPlaylistId) { "全部内容不能执行移动" }
                                val targetId = created?.playlistId ?: selectedTargetIds.singleOrNull()
                                requireNotNull(targetId) { "请选择一个目标歌单" }
                                val result = repository.moveContentsFromPlaylist(selectedContentIds, source, targetId)
                                val targetName = created?.name ?: playlists.firstOrNull { it.playlistId == targetId }?.name
                                Triple(result, 1, targetName)
                            }
                        }
                    }.onSuccess { (result, targetCount, targetName) ->
                        notice =
                            when (mode) {
                                PlaylistBatchTargetMode.Add -> batchAddNotice(result, targetCount)
                                PlaylistBatchTargetMode.Move -> batchMoveNotice(result, targetName)
                            }
                        playlistBatchTargetMode = null
                        contentOrganizeMode = false
                        organizedContentIds = emptySet()
                    }.onFailure { notice = it.message ?: "无法更新歌单" }
                }
            },
        )
    }

    playlistDialogMode?.let { mode ->
        PlaylistNameDialog(
            title = if (mode == PlaylistDialogMode.Create) "新建歌单" else "重命名歌单",
            value = playlistName,
            onValueChanged = { playlistName = it },
            onDismiss = { playlistDialogMode = null },
            onConfirm = {
                val value = playlistName
                playlistDialogMode = null
                scope.launch {
                    runCatching {
                        when (mode) {
                            PlaylistDialogMode.Create -> repository.createPlaylist(value)
                            is PlaylistDialogMode.Rename -> repository.renamePlaylist(mode.playlistId, value)
                        }
                    }.onSuccess { notice = "歌单已保存" }.onFailure { notice = it.message ?: "无法保存歌单" }
                }
            },
        )
    }

    playlistToDelete?.let { playlist ->
        DeletePlaylistDialog(
            name = playlist.name,
            onDismiss = { playlistToDelete = null },
            onConfirm = {
                playlistToDelete = null
                scope.launch {
                    repository.deletePlaylist(playlist.playlistId)
                    notice = "歌单已删除"
                }
            },
        )
    }

    if (importVisible) {
        ImportHubScreen(
            repository = repository,
            sources = sourceSummaries,
            playlists = playlists,
            coordinator = coordinator,
            onDismiss = { importVisible = false },
            onPickMedia = { importVisible = false; onPickMedia() },
            onAddFolder = onAddFolder,
            onRequestLibraryAccess = onRequestLibraryAccess,
            onRefreshLibrary = onRefreshLibrary,
            onRefreshSource = onRefreshSource,
            onRelinkSource = onRelinkSource,
            onCancelScan = onCancelScan,
            onRemoveSource = onRemoveSource,
        )
    }
}

private sealed interface PlaylistDialogMode {
    data object Create : PlaylistDialogMode
    data class Rename(val playlistId: String) : PlaylistDialogMode
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContentRow(item: ContentSummary, status: String?, onClick: () -> Unit, onLongPress: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (item.media.kind.name == "Video") Icons.Outlined.PlayArrow else Icons.Outlined.AudioFile,
                contentDescription = null,
                tint = AppSecondaryText,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.media.displayTitle, color = AppPrimaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(item.media.displayArtist, item.media.displayName.takeIf { it != item.media.displayTitle }).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, color = AppSecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                status?.let { Text(it, color = if (it == "失败") AppError else AppAccent, fontSize = 11.sp) }
            }
        }
        HorizontalDivider(color = AppHairline)
    }
}

@Composable
internal fun EmptyContent(onAddContent: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("还没有内容", color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Button(onClick = onAddContent, colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceRaised, contentColor = AppPrimaryText)) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("添加内容")
        }
    }
}

@Composable
private fun QueueTab(
    recovery: PlaybackRecoveryProjection,
    onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    listState: LazyListState,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("当前播放列表", color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
                Text("${recovery.queue.size} 项 · 播放顺序", color = AppSecondaryText, fontSize = 12.sp)
            }
        }
        if (recovery.queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("播放列表为空", color = AppSecondaryText) }
        } else {
            LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                itemsIndexed(recovery.queue, key = { _, item -> item.contentId }) { index, item ->
                    val current = index == recovery.currentIndex
                    QueueItemRow(
                        itemKey = item.contentId,
                        index = index,
                        itemCount = recovery.queue.size,
                        title = item.media.displayTitle,
                        artist = item.media.displayArtist,
                        isCurrent = current,
                        onSelect = { onSelect(index) },
                        onMove = onMove,
                        onRemove = { onRemove(item.contentId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigTab(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
) {
    SyncTextConfigPage(state = state, onAction = onAction)
}

@Composable
private fun SettingsTab(
    profile: DisplayProfile,
    onProfileChanged: (DisplayProfile) -> Unit,
    onCalibrate: () -> Unit,
    coordinator: LibraryCoordinatorState,
    listState: LazyListState,
) {
    var local by remember(profile) { mutableStateOf(profile) }
    LaunchedEffect(profile) { local = profile }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Glow", color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisplayProfile.Presets.forEach { preset ->
                    OutlinedButton(onClick = { local = preset; onProfileChanged(preset) }) { Text(preset.name) }
                }
            }
            TextButton(onClick = onCalibrate) {
                Icon(Icons.Outlined.Tune, null)
                Spacer(Modifier.width(6.dp))
                Text("校准")
            }
        }
        item {
            Text("窗口亮度 ${(local.glowWindowBrightness * 100).toInt()}%", color = AppSecondaryText)
            androidx.compose.material3.Slider(
                value = local.glowWindowBrightness,
                onValueChange = { local = local.copy(name = DisplayProfile.CustomName, glowWindowBrightness = it); onProfileChanged(local) },
                valueRange = 0.01f..0.30f,
            )
            Text("文字亮度 ${local.glowSubtitleLevel}", color = AppSecondaryText)
            androidx.compose.material3.Slider(
                value = local.glowSubtitleLevel.toFloat(),
                onValueChange = { local = local.copy(name = DisplayProfile.CustomName, glowSubtitleLevel = it.toInt()); onProfileChanged(local) },
                valueRange = 4f..96f,
            )
            Text("控制亮度 ${local.glowControlLevel}", color = AppSecondaryText)
            androidx.compose.material3.Slider(
                value = local.glowControlLevel.toFloat(),
                onValueChange = { local = local.copy(name = DisplayProfile.CustomName, glowControlLevel = it.toInt()); onProfileChanged(local) },
                valueRange = 24f..160f,
            )
            Text("文字大小 ${local.glowSubtitleSizeSp.toInt()}sp", color = AppSecondaryText)
            androidx.compose.material3.Slider(
                value = local.glowSubtitleSizeSp,
                onValueChange = { local = local.copy(name = DisplayProfile.CustomName, glowSubtitleSizeSp = it); onProfileChanged(local) },
                valueRange = 18f..36f,
            )
            Text("垂直位置", color = AppSecondaryText)
            androidx.compose.material3.Slider(
                value = local.glowSubtitleVerticalBias,
                onValueChange = { local = local.copy(name = DisplayProfile.CustomName, glowSubtitleVerticalBias = it); onProfileChanged(local) },
                valueRange = -0.6f..0.6f,
            )
        }
        item {
            Text("权限", color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
            Text(if (coordinator.hasAudioAccess) "设备音频：已授权" else "设备音频：未授权", color = AppSecondaryText)
        }
        item {
            Text("关于", color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
            Text("VortexCue · OLED 优先的同步文字播放器", color = AppSecondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun MiniPlayer(
    title: String,
    isPlaying: Boolean,
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .heightIn(min = 72.dp)
            .background(AppSurfaceRaised, RoundedCornerShape(8.dp)),
    ) {
        if (durationMs > 0L) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = AppAccent,
                trackColor = AppHairline,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 18.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).combinedClickable(onClick = onOpen, onLongClick = onOpen).padding(vertical = 10.dp),
            ) {
                Text(title, color = AppPrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (durationMs > 0L) {
                    Text("${formatTime(positionMs)} / ${formatTime(durationMs)}", color = AppSecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.semantics { contentDescription = if (isPlaying) "暂停" else "播放" }) {
                Icon(if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = AppPrimaryText)
            }
        }
    }
}

@Composable
private fun ItemActions(
    item: ContentSummary,
    task: GenerationTaskSummary?,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    onManagePlaylists: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onSync: () -> Unit,
    onIgnore: () -> Unit,
    onCancelSync: () -> Unit,
    onRetrySync: () -> Unit,
    onAllowMeteredSync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.media.displayTitle, color = AppPrimaryText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
        ActionButton("立即播放", Icons.Outlined.PlayArrow, onPlay)
        ActionButton("加入播放列表", Icons.AutoMirrored.Outlined.QueueMusic, onAdd)
        ActionButton("加入歌单", Icons.AutoMirrored.Outlined.QueueMusic, onManagePlaylists)
        onRemoveFromPlaylist?.let { ActionButton("从当前歌单移除", Icons.Outlined.Close, it) }
        ActionButton("同步文字", Icons.Outlined.Translate, onSync)
        task?.let {
            if (
                it.networkWaitReason ==
                io.github.zvensmoluya.vortexcue.synctext.GenerationNetworkWaitReason.MeteredPermissionRequired
            ) {
                ActionButton("允许本次使用计费网络", Icons.Outlined.Refresh, onAllowMeteredSync)
            } else if (it.needsAttention || it.state == io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState.Paused) {
                ActionButton("继续同步文字", Icons.Outlined.Refresh, onRetrySync)
            } else {
                ActionButton("暂停同步文字", Icons.Outlined.Close, onCancelSync)
            }
        }
        ActionButton("移出内容", Icons.Outlined.DeleteOutline, onIgnore)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(icon, null, tint = AppSecondaryText)
        Spacer(Modifier.width(12.dp))
        Text(label, color = AppPrimaryText, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Notice(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(16.dp).background(AppSurfaceRaised).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = AppPrimaryText, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭提示", tint = AppSecondaryText)
        }
    }
}

private fun batchAddNotice(result: PlaylistBatchResult, targetCount: Int): String =
    when {
        result.changedContentCount == 0 -> "所选 ${result.contentCount} 项已在目标歌单"
        result.unchangedContentCount > 0 ->
            "已更新 ${result.changedContentCount} 项，另有 ${result.unchangedContentCount} 项已在目标歌单"
        else -> "已将 ${result.changedContentCount} 项加入 $targetCount 个歌单"
    }

private fun batchMoveNotice(result: PlaylistBatchResult, targetName: String?): String {
    val alreadyInTarget = result.removedPlaylistMembershipCount - result.addedPlaylistMembershipCount
    return when {
        result.changedContentCount == 0 -> "所选内容已不在当前歌单"
        alreadyInTarget > 0 ->
            "已移动 ${result.changedContentCount} 项，其中 $alreadyInTarget 项原已在目标歌单"
        targetName != null -> "已移动 ${result.changedContentCount} 项到「$targetName」"
        else -> "已移动 ${result.changedContentCount} 项"
    }
}

private fun batchRemoveNotice(result: PlaylistBatchResult): String =
    when {
        result.protectedContentCount == result.contentCount ->
            "默认收藏中的唯一归属不能移除，请先加入其他歌单"
        result.protectedContentCount > 0 ->
            "已移除 ${result.changedContentCount} 项，${result.protectedContentCount} 项仍保留在默认收藏"
        else -> "已从当前歌单移除 ${result.changedContentCount} 项"
    }

private fun MainTab.label(): String =
    when (this) {
        MainTab.Playlists -> "歌单"
        MainTab.Queue -> "播放列表"
        MainTab.Config -> "配置"
        MainTab.Settings -> "设置"
    }

private fun MainTab.icon(): androidx.compose.ui.graphics.vector.ImageVector =
    when (this) {
        MainTab.Playlists -> Icons.Outlined.LibraryMusic
        MainTab.Queue -> Icons.AutoMirrored.Outlined.QueueMusic
        MainTab.Config -> Icons.Outlined.Tune
        MainTab.Settings -> Icons.Outlined.Settings
    }

@Composable
internal fun MainNavigationBar(
    selectedTab: MainTab,
    onTabChanged: (MainTab) -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier, containerColor = AppBackground) {
        MainTab.entries.take(2).forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabChanged(tab) },
                icon = { Icon(tab.icon(), contentDescription = tab.label()) },
                label = { Text(tab.label()) },
                colors = navigationColors(),
            )
        }
        NavigationBarItem(
            selected = false,
            onClick = onOpenImport,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = "添加内容",
                    tint = AppBoneLogo,
                    modifier = Modifier.size(30.dp),
                )
            },
            label = { Text("添加") },
            colors = navigationColors(),
        )
        MainTab.entries.drop(2).forEach { tab ->
            val selected = selectedTab == tab
            NavigationBarItem(
                selected = selected,
                onClick = { onTabChanged(tab) },
                icon = {
                    if (tab == MainTab.Config) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (selected) AppSurfaceSelected else Color.Transparent,
                                    RoundedCornerShape(10.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(tab.icon(), contentDescription = tab.label())
                        }
                    } else {
                        Icon(tab.icon(), contentDescription = tab.label())
                    }
                },
                label = { Text(tab.label()) },
                colors = if (tab == MainTab.Config) navigationColors(indicatorColor = Color.Transparent) else navigationColors(),
            )
        }
    }
}

@Composable
private fun navigationColors(indicatorColor: Color = AppSurfaceSelected) =
    NavigationBarItemDefaults.colors(
        selectedIconColor = AppPrimaryText,
        selectedTextColor = AppPrimaryText,
        indicatorColor = indicatorColor,
        unselectedIconColor = AppMutedText,
        unselectedTextColor = AppMutedText,
    )
