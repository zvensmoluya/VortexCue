package io.github.zvensmoluya.vortexcue.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import io.github.zvensmoluya.vortexcue.R
import io.github.zvensmoluya.vortexcue.core.model.displayArtist
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.ContentSummary
import io.github.zvensmoluya.vortexcue.data.PlaylistBatchResult
import io.github.zvensmoluya.vortexcue.data.PlaylistSummary
import io.github.zvensmoluya.vortexcue.data.PlaylistEntrySummary
import io.github.zvensmoluya.vortexcue.data.WorkSummary
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlin.math.PI
import kotlin.math.sin

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
    singleFileImportResult: SingleFileImportResult? = null,
    onSingleFileImportResultConsumed: () -> Unit = {},
    onContentImported: suspend (List<String>) -> Unit = {},
    onPickMedia: (String?) -> Unit,
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
    onOpenPlayer: () -> Unit,
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
    val works by repository.works.collectAsState(initial = emptyList())
    val allContent by repository.allContent.collectAsState(initial = emptyList())
    val sourceSummaries by repository.sources.collectAsState(initial = sources)
    val selectedWorkId = playlistViewId?.takeIf { it.startsWith("work-") }
    val detailContentFlow = remember(repository, playlistViewId, selectedWorkId) {
        if (playlistViewId == null || playlistViewId == AllContentPlaylistId || selectedWorkId != null) repository.allContent
        else repository.observePlaylistContent(playlistViewId)
    }
    val detailContent by detailContentFlow.collectAsState(initial = emptyList())
    val detailEntriesFlow = remember(repository, playlistViewId, selectedWorkId) {
        if (playlistViewId != null && playlistViewId != AllContentPlaylistId && selectedWorkId == null) {
            repository.observePlaylistEntries(playlistViewId)
        } else {
            flowOf(emptyList())
        }
    }
    val detailEntries by detailEntriesFlow.collectAsState(initial = emptyList())
    val workDetailsFlow = remember(repository, selectedWorkId) {
        selectedWorkId?.let(repository::observeWorkDetails) ?: flowOf(null)
    }
    val selectedWorkDetails by workDetailsFlow.collectAsState(initial = null)
    var importVisible by rememberSaveable { mutableStateOf(false) }
    var importTargetPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var singleFileImportError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedItem by remember { mutableStateOf<ContentSummary?>(null) }
    var selectedItemPlaylistIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistDialogMode by remember { mutableStateOf<PlaylistDialogMode?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var membershipItem by remember { mutableStateOf<ContentSummary?>(null) }
    var membershipInitialIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var membershipWork by remember { mutableStateOf<WorkSummary?>(null) }
    var membershipWorkPartIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistToDelete by remember { mutableStateOf<PlaylistSummary?>(null) }
    var workToDelete by remember { mutableStateOf<WorkSummary?>(null) }
    var workToRename by remember { mutableStateOf<WorkSummary?>(null) }
    var workName by remember { mutableStateOf("") }
    var playlistReorderMode by rememberSaveable { mutableStateOf(false) }
    var contentReorderMode by rememberSaveable { mutableStateOf(false) }
    var contentOrganizeMode by rememberSaveable { mutableStateOf(false) }
    var organizedContentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistBatchTargetMode by remember { mutableStateOf<PlaylistBatchTargetMode?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val queueListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val settingsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(playlistViewId, playlists) {
        if (
            playlistViewId != null && playlistViewId != AllContentPlaylistId &&
            !playlistViewId.startsWith("work-") && playlists.none { it.playlistId == playlistViewId }
        ) {
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
            importTargetPlaylistId = null
            singleFileImportError = null
            importVisible = true
            onImportHubRequestConsumed()
        }
    }
    LaunchedEffect(singleFileImportResult) {
        when (val result = singleFileImportResult) {
            SingleFileImportResult.Success -> {
                importVisible = false
                importTargetPlaylistId = null
                singleFileImportError = null
                onSingleFileImportResultConsumed()
            }
            is SingleFileImportResult.Failure -> {
                singleFileImportError = result.message
                onSingleFileImportResultConsumed()
            }
            null -> Unit
        }
    }
    LaunchedEffect(selectedItem?.contentId) {
        selectedItemPlaylistIds =
            selectedItem?.let { repository.playlistIdsForContent(it.contentId) }.orEmpty()
    }
    val miniPlayerUiState = resolveMiniPlayerUiState(recovery, playback)
    var retainedMiniPlayerUiState by remember { mutableStateOf(miniPlayerUiState) }
    LaunchedEffect(miniPlayerUiState) {
        miniPlayerUiState?.let { retainedMiniPlayerUiState = it }
    }
    val displayedMiniPlayerUiState = miniPlayerUiState ?: retainedMiniPlayerUiState
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
                AnimatedVisibility(
                    visible = miniPlayerUiState != null,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 5 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 5 },
                ) {
                    displayedMiniPlayerUiState?.let { state ->
                        MiniPlayer(
                            title = state.title,
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            onOpen = onOpenPlayer,
                            onPlayPause = onPlayPause,
                        )
                    }
                }
                MainNavigationBar(
                    selectedTab = selectedTab,
                    onTabChanged = onTabChanged,
                    onOpenImport = {
                        importTargetPlaylistId = null
                        singleFileImportError = null
                        importVisible = true
                    },
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
                            works = works,
                            allContentCount = allContent.size + works.size,
                            reorderMode = playlistReorderMode,
                            listState = playlistListState,
                            onOpenAllContent = { onPlaylistViewChanged(AllContentPlaylistId) },
                            onOpenPlaylist = onPlaylistViewChanged,
                            onOpenWork = onPlaylistViewChanged,
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
                    } else if (selectedWorkId != null) {
                        selectedWorkDetails?.let { details ->
                            WorkDetailPage(
                                details = details,
                                onBack = { onPlaylistViewChanged(null) },
                                onContinue = {
                                    scope.launch {
                                        val queue = repository.workQueue(details.summary.workId)
                                        val index = queue.indexOfFirst { it.contentId == details.summary.progressContentId }.coerceAtLeast(0)
                                        gateway.replaceEntries(queue, index, details.summary.progressPositionMs)
                                    }
                                },
                                onPlayFromStart = {
                                    scope.launch { gateway.replaceEntries(repository.workQueue(details.summary.workId)) }
                                },
                                onPlayPart = { index ->
                                    scope.launch { gateway.replaceEntries(repository.workQueue(details.summary.workId), index) }
                                },
                                onPartLongPress = { selectedItem = it },
                                onSelectEdition = { editionKey ->
                                    scope.launch {
                                        repository.setWorkEdition(details.summary.workId, editionKey)
                                        notice = "已切换作品播放版本"
                                    }
                                },
                                onAddWorkToPlaylist = {
                                    membershipWork = details.summary
                                    membershipWorkPartIds = emptySet()
                                    membershipInitialIds = emptySet()
                                },
                                onAddPartsToPlaylist = { ids ->
                                    membershipWork = null
                                    membershipWorkPartIds = ids
                                    membershipInitialIds = emptySet()
                                },
                                onRenameWork = {
                                    workName = details.summary.title
                                    workToRename = details.summary
                                },
                                onDeleteWork = {
                                    workToDelete = details.summary
                                },
                            )
                        }
                    } else if (playlistViewId == AllContentPlaylistId) {
                        AllCollectedPage(
                            works = works,
                            content = detailContent,
                            onBack = { onPlaylistViewChanged(null) },
                            onOpenWork = onPlaylistViewChanged,
                            onPlayContent = onPlaySingle,
                            onContentLongPress = { selectedItem = it },
                        )
                    } else {
                        val playlist = playlists.firstOrNull { it.playlistId == playlistViewId }
                        MixedPlaylistDetail(
                            title = playlist?.name ?: "歌单",
                            entries = detailEntries,
                            onBack = { onPlaylistViewChanged(null) },
                            onPlayAll = {
                                scope.launch {
                                    val queue = repository.playlistQueue(playlistViewId)
                                    if (queue.isNotEmpty()) {
                                        gateway.replaceEntries(queue)
                                        repository.markPlaylistPlayed(playlistViewId)
                                    }
                                }
                            },
                            onPlayEntry = { entryIndex ->
                                scope.launch {
                                    val selectedEntry = detailEntries.getOrNull(entryIndex)
                                    val queue = repository.playlistQueue(playlistViewId)
                                    val queueIndex = queue.indexOfFirst { it.originPlaylistEntryId == selectedEntry?.entryId }.coerceAtLeast(0)
                                    if (queue.isNotEmpty()) {
                                        gateway.replaceEntries(queue, queueIndex)
                                        repository.markPlaylistPlayed(playlistViewId)
                                    }
                                }
                            },
                            onOpenWork = onPlaylistViewChanged,
                            onContentLongPress = { selectedItem = it },
                            onRemoveWork = { entryId ->
                                scope.launch {
                                    repository.removePlaylistEntry(entryId, playlistViewId)
                                    notice = "已从歌单移除作品"
                                }
                            },
                            onAddContent = {
                                importTargetPlaylistId = playlistViewId
                                singleFileImportError = null
                                importVisible = true
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
                    val work = selectedWorkDetails
                    if (work != null) {
                        val index = work.includedParts.indexOfFirst { it.content.contentId == item.contentId }.coerceAtLeast(0)
                        scope.launch { gateway.replaceEntries(repository.workQueue(work.summary.workId), index) }
                        return@ItemActions
                    }
                    val currentPlaylistId = playlistViewId?.takeIf { it != AllContentPlaylistId && !it.startsWith("work-") }
                    val entry = detailEntries.filterIsInstance<PlaylistEntrySummary.Content>()
                        .firstOrNull { it.content.contentId == item.contentId }
                    if (currentPlaylistId != null && entry != null) {
                        scope.launch {
                            val queue = repository.playlistQueue(currentPlaylistId)
                            val index = queue.indexOfFirst { it.originPlaylistEntryId == entry.entryId }.coerceAtLeast(0)
                            gateway.replaceEntries(queue, index)
                            repository.markPlaylistPlayed(currentPlaylistId)
                        }
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
                onIgnore =
                    if (selectedWorkDetails == null) {
                        {
                            selectedItem = null
                            scope.launch {
                                repository.ignore(item.contentId)
                                notice = "已移出内容（原文件未删除）"
                            }
                        }
                    } else {
                        null
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
            defaultToFavorite = selectedWorkDetails == null,
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

    if (membershipWork != null || membershipWorkPartIds.isNotEmpty()) {
        val work = membershipWork
        PlaylistMembershipSheet(
            title = work?.title ?: "将 ${membershipWorkPartIds.size} 个段落加入歌单",
            playlists = playlists,
            initialSelection = emptySet(),
            defaultToFavorite = false,
            onDismiss = {
                membershipWork = null
                membershipWorkPartIds = emptySet()
            },
            onSave = { selectedIds, newName ->
                scope.launch {
                    runCatching {
                        val createdId = newName?.let { repository.createPlaylist(it).playlistId }
                        val targets = selectedIds + listOfNotNull(createdId)
                        require(targets.isNotEmpty()) { "至少选择一个歌单" }
                        if (work != null) repository.addWorkToPlaylists(work.workId, targets)
                        else repository.addContentsToPlaylists(membershipWorkPartIds, targets)
                    }.onSuccess {
                        membershipWork = null
                        membershipWorkPartIds = emptySet()
                        notice = "已加入歌单"
                    }.onFailure { notice = it.message ?: "无法加入歌单" }
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

    workToRename?.let { work ->
        PlaylistNameDialog(
            title = "修改作品名",
            value = workName,
            onValueChanged = { workName = it },
            onDismiss = { workToRename = null },
            onConfirm = {
                val title = workName
                workToRename = null
                scope.launch {
                    runCatching { repository.renameWork(work.workId, title) }
                        .onSuccess { notice = "作品名已更新" }
                        .onFailure { notice = it.message ?: "无法修改作品名" }
                }
            },
        )
    }

    workToDelete?.let { work ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { workToDelete = null },
            title = { Text("移出作品？") },
            text = { Text("作品入口和作品级进度会被移除；已单独加入手动歌单的段落会保留，原文件不会删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        workToDelete = null
                        scope.launch {
                            repository.deleteWork(work.workId)
                            onPlaylistViewChanged(null)
                            notice = "已移出作品（原文件未删除）"
                        }
                    },
                ) { Text("移出") }
            },
            dismissButton = { TextButton(onClick = { workToDelete = null }) { Text("取消") } },
        )
    }

    if (importVisible) {
        ImportHubScreen(
            repository = repository,
            sources = sourceSummaries,
            playlists = playlists,
            coordinator = coordinator,
            initialTargetPlaylistId = importTargetPlaylistId,
            singleFileImportError = singleFileImportError,
            onContentImported = onContentImported,
            onDismiss = {
                importVisible = false
                importTargetPlaylistId = null
                singleFileImportError = null
            },
            onPickMedia = {
                singleFileImportError = null
                onPickMedia(importTargetPlaylistId)
            },
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

internal sealed interface SingleFileImportResult {
    data object Success : SingleFileImportResult

    data class Failure(val message: String) : SingleFileImportResult
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
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    val motionPhase = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            motionPhase.snapTo(0f)
            return@LaunchedEffect
        }
        while (isActive) {
            motionPhase.snapTo(0f)
            motionPhase.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1_400, easing = LinearEasing),
            )
        }
    }

    val pulse = ((sin(motionPhase.value * 2f * PI.toFloat() - PI.toFloat() / 2f) + 1f) / 2f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF191620), Color(0xFF0E0D12)),
                ),
            )
            .clickable(
                onClickLabel = "打开播放器",
                role = Role.Button,
                onClick = onOpen,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(5.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(AppAccent.copy(alpha = if (isPlaying) 0.14f else 0.07f), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.72f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, AppAccent.copy(alpha = if (isPlaying) 0.72f else 0.32f), Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(start = 18.dp, end = 12.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerStateMark(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                phase = motionPhase.value,
            )
            Spacer(Modifier.width(13.dp))
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            ) {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 6 })
                            .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { -it / 6 })
                    },
                    label = "miniPlayerTitle",
                ) { currentTitle ->
                    Text(
                        currentTitle,
                        color = AppPrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (durationMs > 0L) {
                    Text(
                        "${formatTime(positionMs)} · -${formatTime(remainingMs)}",
                        color = AppSecondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            MiniPlayerPlaybackButton(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                pulse = pulse,
                onClick = onPlayPause,
            )
        }
    }
}

@Composable
private fun MiniPlayerStateMark(
    isPlaying: Boolean,
    isBuffering: Boolean,
    phase: Float,
) {
    Row(
        modifier = Modifier.size(width = 22.dp, height = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val normalizedWave =
                ((sin((phase + index * 0.23f) * 2f * PI.toFloat()) + 1f) / 2f)
            val height =
                when {
                    isPlaying -> 7f + normalizedWave * 13f
                    isBuffering -> listOf(6f, 12f, 6f)[index]
                    else -> 4f
                }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .background(
                        if (isPlaying) AppAccent else AppMutedText,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

@Composable
private fun MiniPlayerPlaybackButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    pulse: Float,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .size((45f + pulse * 8f).dp)
                    .background(
                        AppAccent.copy(alpha = 0.05f + pulse * 0.07f),
                        CircleShape,
                    ),
            )
        }
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(49.dp).clearAndSetSemantics { },
                color = AppAccent.copy(alpha = 0.78f),
                strokeWidth = 1.5.dp,
            )
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = if (isPlaying) "暂停" else "播放" },
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(AppSurfaceSelected, Color(0xFF211E2B)),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.78f))
                            .togetherWith(fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f))
                    },
                    label = "miniPlayerPlaybackIcon",
                ) { playing ->
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = AppPrimaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

internal data class MiniPlayerUiState(
    val title: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val isBuffering: Boolean = false,
)

internal fun resolveMiniPlayerUiState(
    recovery: PlaybackRecoveryProjection,
    playback: PlaybackSnapshot,
): MiniPlayerUiState? {
    val currentItem = recovery.currentItem ?: return null
    val hasRuntimeTiming = playback.durationMs > 0L
    return MiniPlayerUiState(
        title = currentItem.media.displayTitle,
        isPlaying = playback.isPlaying,
        positionMs = if (hasRuntimeTiming) playback.positionMs else recovery.positionMs,
        durationMs = if (hasRuntimeTiming) playback.durationMs else recovery.durationMs,
        isBuffering = playback.playbackState == Player.STATE_BUFFERING,
    )
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
    onIgnore: (() -> Unit)?,
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
        onIgnore?.let { ActionButton("移出内容", Icons.Outlined.DeleteOutline, it) }
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
