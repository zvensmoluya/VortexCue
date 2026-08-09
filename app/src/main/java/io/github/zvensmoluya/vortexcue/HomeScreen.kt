package io.github.zvensmoluya.vortexcue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

private enum class LibraryView(val label: String) {
    Songs("歌曲"),
    Albums("专辑"),
    Artists("歌手"),
    Folders("文件夹"),
    Sources("来源"),
    Playlists("播放列表"),
}

@Composable
internal fun HomeScreen(
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
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var libraryView by rememberSaveable { mutableStateOf(LibraryView.Songs) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var mediaActionTarget by remember { mutableStateOf<MediaSourceRef?>(null) }
    var createPlaylistVisible by rememberSaveable { mutableStateOf(false) }
    var createPlaylistName by rememberSaveable { mutableStateOf("") }
    var createPlaylistSeed by remember { mutableStateOf<MediaSourceRef?>(null) }

    LaunchedEffect(libraryView, searchQuery) {
        selectedGroupKey = null
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(
            settingsVisible = settingsVisible,
            onToggleSettings = { settingsVisible = !settingsVisible },
        )

        if (settingsVisible) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                DisplaySettings(
                    profile = displayProfile,
                    onProfileChanged = onDisplayProfileChanged,
                )
            }
        } else {
            LibraryHome(
                draft = draft,
                activeSession = activeSession,
                lastSession = lastSession,
                playback = playback,
                library = library,
                libraryView = libraryView,
                searchQuery = searchQuery,
                selectedGroupKey = selectedGroupKey,
                onLibraryViewChanged = { libraryView = it },
                onSearchQueryChanged = { searchQuery = it },
                onSelectedGroupChanged = { selectedGroupKey = it },
                onRequestLibraryAccess = onRequestLibraryAccess,
                onRefreshLibrary = onRefreshLibrary,
                onAddFolder = onAddFolder,
                onPickMedia = onPickMedia,
                onPickSubtitle = onPickSubtitle,
                onRemoveSubtitle = onRemoveSubtitle,
                onClearDraft = onClearDraft,
                onApplyDraft = onApplyDraft,
                onContinue = onContinue,
                onPlayPause = onPlayPause,
                onPlayLibraryItems = onPlayLibraryItems,
                onOpenMediaActions = { mediaActionTarget = it },
                onCreateEmptyPlaylist = {
                    createPlaylistSeed = null
                    createPlaylistName = ""
                    createPlaylistVisible = true
                },
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onDeletePlaylist = onDeletePlaylist,
                modifier = Modifier.weight(1f),
            )
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
}

@Composable
private fun Header(
    settingsVisible: Boolean,
    onToggleSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (settingsVisible) "显示" else "荧荧",
                color = AppPrimaryText,
                fontSize = 25.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (settingsVisible) "微光校准与字幕外观" else "本地播放 · OLED 微光",
                color = AppMutedText,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onToggleSettings) {
            Text(if (settingsVisible) "完成" else "设置", color = AppSecondaryText)
        }
    }
}

@Composable
private fun LibraryHome(
    draft: SessionDraft,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    playback: PlaybackSnapshot,
    library: MediaLibraryState,
    libraryView: LibraryView,
    searchQuery: String,
    selectedGroupKey: String?,
    onLibraryViewChanged: (LibraryView) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSelectedGroupChanged: (String?) -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onAddFolder: () -> Unit,
    onPickMedia: () -> Unit,
    onPickSubtitle: () -> Unit,
    onRemoveSubtitle: () -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayLibraryItems: (List<MediaSourceRef>, Int) -> Unit,
    onOpenMediaActions: (MediaSourceRef) -> Unit,
    onCreateEmptyPlaylist: () -> Unit,
    onRemoveFromPlaylist: (String, MediaSourceRef) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resumableSession = activeSession ?: lastSession?.session
    val normalizedQuery = searchQuery.trim()
    val filteredItems =
        library.items.filter { media ->
            normalizedQuery.isEmpty() ||
                listOf(
                    media.displayTitle,
                    media.displayArtist,
                    media.album,
                    media.folder,
                ).any { value -> value?.contains(normalizedQuery, ignoreCase = true) == true }
        }
    val groups = groupsFor(libraryView, filteredItems)
    val selectedPlaylist =
        if (libraryView == LibraryView.Playlists) {
            library.config.playlists.firstOrNull { it.id == selectedGroupKey }
        } else {
            null
        }
    val selectedGroupItems =
        when {
            selectedPlaylist != null -> selectedPlaylist.items
            selectedGroupKey != null -> groups.firstOrNull { it.first == selectedGroupKey }?.second.orEmpty()
            else -> emptyList()
        }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        resumableSession?.let { session ->
            item(key = "now-playing") {
                NowPlayingBlock(
                    session = session,
                    activeSession = activeSession,
                    lastSession = lastSession,
                    playback = playback,
                    onContinue = onContinue,
                    onPlayPause = onPlayPause,
                )
            }
            item(key = "now-playing-divider") { Hairline() }
        }

        item(key = "library-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading(
                    title = "内容库",
                    detail = "${library.items.size} 项",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton(
                        label = if (library.hasSystemAccess) "刷新" else "扫描本机",
                        onClick =
                            if (library.hasSystemAccess) onRefreshLibrary else onRequestLibraryAccess,
                        modifier = Modifier.weight(1f),
                        emphasized = !library.hasSystemAccess,
                    )
                    QuietButton(
                        label = "添加文件夹",
                        onClick = onAddFolder,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (library.isLoading) StatusText("正在整理本地内容…")
                library.errorMessage?.let { StatusText(it, AppError) }
            }
        }

        if (library.items.isNotEmpty()) {
            item(key = "search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索标题、歌手、专辑或文件夹", color = AppMutedText) },
                    singleLine = true,
                )
            }
        }

        item(key = "library-tabs") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LibraryView.entries.forEach { view ->
                    TextButton(onClick = { onLibraryViewChanged(view) }) {
                        Text(
                            text = view.label,
                            color = if (view == libraryView) AppAccent else AppMutedText,
                            fontWeight = if (view == libraryView) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        if (
            library.items.isEmpty() &&
                library.config.playlists.isEmpty() &&
                !library.isLoading
        ) {
            item(key = "library-empty") {
                Text(
                    text = "扫描本机音频，或添加一个文件夹开始建立内容库。",
                    color = AppSecondaryText,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        } else if (selectedGroupKey != null) {
            item(key = "selected-group-heading") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onSelectedGroupChanged(null) }) {
                        Text("返回${libraryView.label}", color = AppSecondaryText)
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
                    } else if (selectedGroupItems.isNotEmpty()) {
                        TextButton(onClick = { onPlayLibraryItems(selectedGroupItems, 0) }) {
                            Text("播放全部", color = AppSecondaryText)
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
                item(key = "selected-empty") {
                    StatusText("这里还没有内容")
                }
            }
        } else {
            when (libraryView) {
                LibraryView.Songs ->
                    itemsIndexed(
                        items = filteredItems,
                        key = { _, item -> "song-${item.uri}" },
                    ) { index, media ->
                        TrackRow(
                            media = media,
                            onPlay = { onPlayLibraryItems(filteredItems, index) },
                            onOpenMediaActions = { onOpenMediaActions(media) },
                        )
                    }
                LibraryView.Playlists -> {
                    item(key = "new-playlist") {
                        QuietButton(
                            label = "新建播放列表",
                            onClick = onCreateEmptyPlaylist,
                            emphasized = true,
                        )
                    }
                    items(
                        items = library.config.playlists,
                        key = { "playlist-${it.id}" },
                    ) { playlist ->
                        GroupRow(
                            name = playlist.name,
                            count = playlist.items.size,
                            onOpen = { onSelectedGroupChanged(playlist.id) },
                            onPlay = {
                                if (playlist.items.isNotEmpty()) onPlayLibraryItems(playlist.items, 0)
                            },
                        )
                    }
                }
                else ->
                    items(
                        items = groups,
                        key = { "group-${libraryView.name}-${it.first}" },
                    ) { group ->
                        GroupRow(
                            name = group.first,
                            count = group.second.size,
                            onOpen = { onSelectedGroupChanged(group.first) },
                            onPlay = { onPlayLibraryItems(group.second, 0) },
                        )
                    }
            }
        }

        item(key = "add-divider") { Hairline() }
        item(key = "add-content") {
            AddContentBlock(
                draft = draft,
                activeSession = activeSession,
                onPickMedia = onPickMedia,
                onPickSubtitle = onPickSubtitle,
                onRemoveSubtitle = onRemoveSubtitle,
                onClearDraft = onClearDraft,
                onApplyDraft = onApplyDraft,
            )
        }
        item(key = "footer") {
            Text(
                text = "内容只在本机读取",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = AppMutedText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NowPlayingBlock(
    session: ActiveSession,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    playback: PlaybackSnapshot,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val positionMs = if (activeSession != null) playback.positionMs else lastSession?.positionMs ?: 0L
    val durationMs = if (activeSession != null) playback.durationMs else lastSession?.durationMs ?: 0L
    val availability =
        if (activeSession != null) MediaAvailability.Ready else lastSession?.mediaAvailability

    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        SectionHeading(
            title = if (activeSession == null) "继续上次" else "正在播放",
            detail = if (playback.isPlaying && activeSession != null) "声音仍在继续" else null,
        )
        Text(
            text = session.media.displayTitle,
            color = AppPrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        session.media.displayArtist?.let {
            Text(it, color = AppSecondaryText, fontSize = 13.sp)
        }
        Text(
            text =
                "${formatTime(positionMs)} / ${formatTime(durationMs)}  ·  " +
                    (session.subtitle?.source?.displayName ?: "无字幕"),
            color = AppMutedText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (availability) {
            MediaAvailability.Checking -> StatusText("正在验证播放文件…")
            is MediaAvailability.Unavailable -> StatusText(availability.message, AppError)
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(
                label =
                    when {
                        activeSession != null -> "返回播放器"
                        availability is MediaAvailability.Unavailable -> "重新定位"
                        else -> "继续播放"
                    },
                onClick = onContinue,
                enabled = availability != MediaAvailability.Checking,
                modifier = Modifier.weight(1f),
                emphasized = true,
            )
            if (activeSession != null) {
                QuietButton(
                    label = if (playback.isPlaying) "暂停" else "播放",
                    onClick = onPlayPause,
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
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = media.displayTitle,
                color = AppPrimaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(media.displayArtist, media.album, media.folder)
                        .firstOrNull()
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
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, color = AppPrimaryText, fontSize = 15.sp, maxLines = 1)
            Text("$count 项", color = AppMutedText, fontSize = 11.sp)
        }
        TextButton(onClick = onPlay, enabled = count > 0) {
            Text("播放", color = if (count > 0) AppSecondaryText else AppMutedText)
        }
    }
    Hairline()
}

@Composable
private fun AddContentBlock(
    draft: SessionDraft,
    activeSession: ActiveSession?,
    onPickMedia: () -> Unit,
    onPickSubtitle: () -> Unit,
    onRemoveSubtitle: () -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(title = "添加单个内容", detail = "可选字幕")
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
            is DraftSubtitle.Ready -> StatusText("已读取 ${subtitle.subtitle.timeline.cues.size} 条字幕")
            else -> Unit
        }
        if (draft.subtitle != DraftSubtitle.None) {
            TextButton(onClick = onRemoveSubtitle) { Text("移除字幕", color = AppMutedText) }
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
                enabled = draft.media is DraftMedia.Ready && draft.subtitle !is DraftSubtitle.Loading,
                modifier = Modifier.weight(1f),
                emphasized = true,
            )
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
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onChoosePlaylist: () -> Unit,
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
                TextButton(onClick = onPlayNext) {
                    Text("下一项播放", color = AppPrimaryText)
                }
                TextButton(onClick = onAddToQueue) {
                    Text("添加到队列末尾", color = AppPrimaryText)
                }
                TextButton(onClick = onChoosePlaylist) {
                    Text("添加到播放列表", color = AppPrimaryText)
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
            text = "左右区域双击快退或快进，中央双击播放或暂停，长按任意位置显示临时控制。普通单击不会打断沉浸。",
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
            text = "声音继续，画面退场",
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
            text = "长按解锁",
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
            LibraryView.Sources -> items.groupBy { sourceLabel(it.origin) }
            else -> emptyMap()
        }
    return grouped.entries
        .sortedBy { it.key.lowercase() }
        .map { entry -> entry.key to entry.value.sortedBy { media -> media.displayTitle } }
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
        value < 27f -> "标准"
        value < 32f -> "较大"
        else -> "最大"
    }

private fun verticalPositionLabel(bias: Float): String {
    val normalized = bias.coerceIn(-0.60f, 0.60f)
    if (normalized.absoluteValue < 0.04f) return "居中"
    val percent = (normalized.absoluteValue * 100).roundToInt()
    return if (normalized < 0f) "偏上 $percent%" else "偏下 $percent%"
}
