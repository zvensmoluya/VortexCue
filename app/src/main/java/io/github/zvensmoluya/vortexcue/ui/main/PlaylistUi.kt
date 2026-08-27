package io.github.zvensmoluya.vortexcue.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zvensmoluya.vortexcue.core.model.displayArtist
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.data.ContentSummary
import io.github.zvensmoluya.vortexcue.data.PlaylistSummary
import io.github.zvensmoluya.vortexcue.data.PlaylistEntrySummary
import io.github.zvensmoluya.vortexcue.data.WorkDetails
import io.github.zvensmoluya.vortexcue.data.WorkSummary
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppBackground
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import kotlin.math.roundToInt

internal const val AllContentPlaylistId = "__all_content__"

@Composable
internal fun PlaylistOverview(
    playlists: List<PlaylistSummary>,
    recentPlaylists: List<PlaylistSummary>,
    works: List<WorkSummary>,
    allContentCount: Int,
    reorderMode: Boolean,
    listState: LazyListState,
    onOpenAllContent: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenWork: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (PlaylistSummary) -> Unit,
    onDeletePlaylist: (PlaylistSummary) -> Unit,
    onReorderModeChanged: (Boolean) -> Unit,
    onMovePlaylist: (Int, Int) -> Unit,
) {
    val defaultPlaylist = playlists.firstOrNull { it.isDefaultFavorite }
    val userPlaylists = playlists.filterNot { it.isDefaultFavorite }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (recentPlaylists.isNotEmpty() && !reorderMode) {
            item(key = "recent-title") {
                Text(
                    "最近听过",
                    color = AppPrimaryText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            item(key = "recent-row") {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cardWidth = (maxWidth * 0.78f).coerceAtMost(320.dp)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    ) {
                        items(recentPlaylists, key = { it.playlistId }) { playlist ->
                            RecentPlaylistCard(
                                playlist = playlist,
                                modifier = Modifier.width(cardWidth),
                                onClick = { onOpenPlaylist(playlist.playlistId) },
                            )
                        }
                    }
                }
            }
        }
        if (works.isNotEmpty() && !reorderMode) {
            item(key = "works-title") {
                Text(
                    "作品",
                    color = AppPrimaryText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                )
            }
            items(works, key = WorkSummary::workId) { work ->
                WorkCard(
                    work = work,
                    onClick = { onOpenWork(work.workId) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item(key = "all-title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = if (recentPlaylists.isEmpty()) 20.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (reorderMode) "调整歌单顺序" else "全部歌单",
                    color = AppPrimaryText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (reorderMode) {
                    TextButton(onClick = { onReorderModeChanged(false) }) { Text("完成", color = AppAccent) }
                } else {
                    IconButton(
                        onClick = onCreatePlaylist,
                        modifier = Modifier.semantics { contentDescription = "新建歌单" },
                    ) { Icon(Icons.Outlined.Add, null, tint = AppPrimaryText) }
                }
            }
        }
        item(key = AllContentPlaylistId) {
            PlaylistCard(
                name = "全部内容",
                itemCount = allContentCount,
                onClick = { if (!reorderMode) onOpenAllContent() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        defaultPlaylist?.let { playlist ->
            item(key = playlist.playlistId) {
                PlaylistCard(
                    name = playlist.name,
                    itemCount = playlist.itemCount,
                    onClick = { if (!reorderMode) onOpenPlaylist(playlist.playlistId) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        itemsIndexed(userPlaylists, key = { _, it -> it.playlistId }) { index, playlist ->
            if (reorderMode) {
                ReorderPlaylistCard(
                    playlist = playlist,
                    index = index,
                    itemCount = userPlaylists.size,
                    onMove = onMovePlaylist,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                PlaylistCard(
                    name = playlist.name,
                    itemCount = playlist.itemCount,
                    onClick = { onOpenPlaylist(playlist.playlistId) },
                    onRename = { onRenamePlaylist(playlist) },
                    onReorder = { onReorderModeChanged(true) },
                    onDelete = { onDeletePlaylist(playlist) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentPlaylistCard(playlist: PlaylistSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(124.dp)
            .background(AppSurfaceRaised, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            playlist.name,
            color = AppPrimaryText,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text("${playlist.itemCount} 项内容", color = AppSecondaryText, fontSize = 13.sp)
    }
}

@Composable
private fun PlaylistCard(
    name: String,
    itemCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: (() -> Unit)? = null,
    onReorder: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuVisible by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .background(AppSurfaceRaised, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = AppPrimaryText, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$itemCount 项内容", color = AppSecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (onRename != null || onReorder != null || onDelete != null) {
            Box {
                IconButton(
                    onClick = { menuVisible = true },
                    modifier = Modifier.semantics { contentDescription = "$name 歌单操作" },
                ) { Icon(Icons.Outlined.MoreVert, null, tint = AppSecondaryText) }
                DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                    onRename?.let {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menuVisible = false; it() },
                        )
                    }
                    onReorder?.let {
                        DropdownMenuItem(
                            text = { Text("调整歌单顺序") },
                            leadingIcon = { Icon(Icons.Outlined.DragHandle, null) },
                            onClick = { menuVisible = false; it() },
                        )
                    }
                    onDelete?.let {
                        DropdownMenuItem(
                            text = { Text("删除歌单") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menuVisible = false; it() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderPlaylistCard(
    playlist: PlaylistSummary,
    index: Int,
    itemCount: Int,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragOffset by remember(playlist.playlistId) { mutableFloatStateOf(0f) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .graphicsLayer { translationY = dragOffset }
            .background(AppSurfaceRaised, RoundedCornerShape(8.dp))
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("上移") { onMove(index, index - 1); true })
                    if (index < itemCount - 1) add(CustomAccessibilityAction("下移") { onMove(index, index + 1); true })
                }
            }
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.name, color = AppPrimaryText, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.itemCount} 项内容", color = AppSecondaryText, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .pointerInput(playlist.playlistId, index, itemCount) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = {
                            val target = (index + (dragOffset / 78.dp.toPx()).roundToInt()).coerceIn(0, itemCount - 1)
                            dragOffset = 0f
                            if (target != index) onMove(index, target)
                        },
                        onDragCancel = { dragOffset = 0f },
                        onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.DragHandle, contentDescription = "拖动重排 ${playlist.name}", tint = AppSecondaryText)
        }
    }
}

@Composable
internal fun PlaylistDetail(
    title: String,
    content: List<ContentSummary>,
    isAllContent: Boolean,
    reorderMode: Boolean,
    listState: LazyListState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlay: (Int) -> Unit,
    onLongPress: (ContentSummary) -> Unit,
    onReorderModeChanged: (Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAddContent: () -> Unit,
    organizeMode: Boolean = false,
    selectedContentIds: Set<String> = emptySet(),
    onOrganizeModeChanged: (Boolean) -> Unit = {},
    onSelectionChanged: (Set<String>) -> Unit = {},
    onBatchAdd: () -> Unit = {},
    onBatchMove: () -> Unit = {},
    onBatchRemove: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (organizeMode) onOrganizeModeChanged(false) else onBack() }) {
                Icon(
                    if (organizeMode) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = if (organizeMode) "退出整理" else "返回",
                    tint = AppPrimaryText,
                )
            }
            Text(
                if (organizeMode) "已选择 ${selectedContentIds.size} 项" else title,
                color = AppPrimaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (organizeMode) {
                TextButton(
                    onClick = {
                        onSelectionChanged(
                            if (selectedContentIds.size == content.size) emptySet()
                            else content.mapTo(linkedSetOf(), ContentSummary::contentId),
                        )
                    },
                ) { Text(if (selectedContentIds.size == content.size) "取消全选" else "全选", color = AppAccent) }
            } else if (!isAllContent) {
                if (reorderMode) {
                    TextButton(onClick = { onReorderModeChanged(false) }) { Text("完成", color = AppAccent) }
                } else {
                    var menuVisible by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuVisible = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "歌单操作", tint = AppSecondaryText) }
                        DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                            DropdownMenuItem(
                                text = { Text("调整内容顺序") },
                                leadingIcon = { Icon(Icons.Outlined.DragHandle, null) },
                                onClick = { menuVisible = false; onReorderModeChanged(true) },
                            )
                            DropdownMenuItem(
                                text = { Text("整理内容") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                enabled = content.isNotEmpty(),
                                onClick = { menuVisible = false; onOrganizeModeChanged(true) },
                            )
                        }
                    }
                }
            } else if (content.isNotEmpty()) {
                TextButton(onClick = { onOrganizeModeChanged(true) }) { Text("整理", color = AppAccent) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${content.size} 项内容", color = AppSecondaryText, modifier = Modifier.weight(1f))
            if (!isAllContent && !organizeMode) {
                Button(enabled = content.isNotEmpty(), onClick = onPlayAll) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("播放全部")
                }
            }
        }
        if (content.isEmpty()) {
            EmptyContent(onAddContent)
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                itemsIndexed(content, key = { _, item -> item.contentId }) { index, item ->
                    if (organizeMode) {
                        SelectableContentRow(
                            item = item,
                            checked = item.contentId in selectedContentIds,
                            onChecked = { checked ->
                                onSelectionChanged(
                                    if (checked) selectedContentIds + item.contentId
                                    else selectedContentIds - item.contentId,
                                )
                            },
                        )
                    } else if (reorderMode && !isAllContent) {
                        ReorderContentRow(item, index, content.size, onMove)
                    } else {
                        ContentRow(item = item, status = null, onClick = { onPlay(index) }, onLongPress = { onLongPress(item) })
                    }
                }
            }
        }
        if (organizeMode) {
            HorizontalDivider(color = AppHairline)
            Row(
                modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = selectedContentIds.isNotEmpty(),
                    onClick = onBatchAdd,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("加入歌单") }
                if (!isAllContent) {
                    TextButton(
                        enabled = selectedContentIds.isNotEmpty(),
                        onClick = onBatchMove,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("移动到") }
                    TextButton(
                        enabled = selectedContentIds.isNotEmpty(),
                        onClick = onBatchRemove,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("从此移除") }
                }
            }
        }
    }
}

@Composable
private fun WorkCard(work: WorkSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().background(AppSurfaceRaised, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(work.title, color = AppPrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(work.releaseCode, color = AppAccent, fontSize = 12.sp)
        Text("完整收录 ${work.partCount} 段 · 当前播放 ${work.playbackPartCount} 段 · ${work.profileLabel}", color = AppSecondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatWorkDuration(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}小时${minutes}分" else "${minutes}分钟"
}

@Composable
internal fun WorkDetailPage(
    details: WorkDetails,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onPlayFromStart: () -> Unit,
    onPlayPart: (Int) -> Unit,
    onPartLongPress: (ContentSummary) -> Unit,
    onSelectEdition: (String) -> Unit,
    onAddWorkToPlaylist: () -> Unit,
    onAddPartsToPlaylist: (Set<String>) -> Unit,
    onDeleteWork: () -> Unit,
) {
    var selectingParts by remember(details.summary.workId) { mutableStateOf(false) }
    var selectedIds by remember(details.includedParts) {
        mutableStateOf<Set<String>>(details.includedParts.mapTo(linkedSetOf()) { it.content.contentId })
    }
    LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        item(key = "work-header") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回作品" }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = AppPrimaryText)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(details.summary.title, color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(details.summary.releaseCode, color = AppAccent, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDeleteWork, modifier = Modifier.semantics { contentDescription = "移除作品" }) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = AppSecondaryText)
                    }
                }
                Text(
                    "完整收录 ${details.summary.partCount} 段 · 当前播放 ${details.summary.playbackPartCount} 段 / ${formatWorkDuration(details.summary.playbackDurationMs)}",
                    color = AppSecondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onContinue, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("继续播放") }
                    TextButton(onClick = onPlayFromStart, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("从头播放") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onAddWorkToPlaylist, modifier = Modifier.weight(1f)) { Text("整部加入歌单") }
                    TextButton(onClick = { selectingParts = !selectingParts }, modifier = Modifier.weight(1f)) {
                        Text(if (selectingParts) "取消选择" else "选择段落")
                    }
                }
                if (details.editions.size > 1) {
                    Text("播放版本", color = AppSecondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        details.editions.forEach { edition ->
                            if (edition.isSelected) {
                                Button(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(edition.label, maxLines = 1)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onSelectEdition(edition.key) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) { Text(edition.label, maxLines = 1) }
                            }
                        }
                    }
                }
                Text(details.summary.profileLabel, color = AppMutedText, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
                if (selectingParts) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选择 ${selectedIds.size} 段", color = AppSecondaryText, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Button(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                onAddPartsToPlaylist(selectedIds)
                                selectingParts = false
                            },
                        ) { Text("加入歌单") }
                    }
                }
            }
        }
        details.includedParts.groupBy { it.sectionName }.forEach { (section, parts) ->
            item(key = "work-section:$section") {
                Text(section, color = AppPrimaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
            }
            itemsIndexed(parts, key = { _, part -> part.content.contentId }) { _, part ->
                if (selectingParts) {
                    SelectableContentRow(
                        item = part.content,
                        checked = part.content.contentId in selectedIds,
                        onChecked = { checked ->
                            selectedIds = if (checked) selectedIds + part.content.contentId else selectedIds - part.content.contentId
                        },
                    )
                } else {
                    val includedIndex = details.includedParts.indexOfFirst { it.content.contentId == part.content.contentId }
                    ContentRow(
                        item = part.content,
                        status = null,
                        onClick = { if (includedIndex >= 0) onPlayPart(includedIndex) },
                        onLongPress = { onPartLongPress(part.content) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MixedPlaylistDetail(
    title: String,
    entries: List<PlaylistEntrySummary>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayEntry: (Int) -> Unit,
    onOpenWork: (String) -> Unit,
    onContentLongPress: (ContentSummary) -> Unit,
    onRemoveWork: (String) -> Unit,
    onAddContent: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        item(key = "mixed-header") {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回歌单" }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = AppPrimaryText)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("${entries.size} 项", color = AppSecondaryText, fontSize = 12.sp)
                }
                if (entries.isNotEmpty()) {
                    IconButton(onClick = onPlayAll, modifier = Modifier.semantics { contentDescription = "播放全部" }) {
                        Icon(Icons.Outlined.PlayArrow, null, tint = AppPrimaryText)
                    }
                }
                IconButton(onClick = onAddContent, modifier = Modifier.semantics { contentDescription = "向歌单添加内容" }) {
                    Icon(Icons.Outlined.Add, null, tint = AppPrimaryText)
                }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.entryId }) { index, entry ->
            when (entry) {
                is PlaylistEntrySummary.Content ->
                    ContentRow(
                        item = entry.content,
                        status = null,
                        onClick = { onPlayEntry(index) },
                        onLongPress = { onContentLongPress(entry.content) },
                    )
                is PlaylistEntrySummary.Work ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenWork(entry.work.workId) }.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.work.title, color = AppPrimaryText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("作品 · 当前播放 ${entry.work.playbackPartCount} 段 · ${entry.work.releaseCode}", color = AppSecondaryText, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onRemoveWork(entry.entryId) }, modifier = Modifier.semantics { contentDescription = "从歌单移除 ${entry.work.title}" }) {
                            Icon(Icons.Outlined.Close, null, tint = AppSecondaryText)
                        }
                    }
            }
            HorizontalDivider(color = AppHairline)
        }
    }
}

@Composable
internal fun AllCollectedPage(
    works: List<WorkSummary>,
    content: List<ContentSummary>,
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    onPlayContent: (String) -> Unit,
    onContentLongPress: (ContentSummary) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        item(key = "all-collected-header") {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回歌单" }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = AppPrimaryText)
                }
                Column {
                    Text("全部内容", color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("${works.size} 部作品 · ${content.size} 项独立内容", color = AppSecondaryText, fontSize = 12.sp)
                }
            }
        }
        if (works.isNotEmpty()) {
            item(key = "all-works-title") {
                Text("作品", color = AppPrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            items(works, key = WorkSummary::workId) { work ->
                WorkCard(work, { onOpenWork(work.workId) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        if (content.isNotEmpty()) {
            item(key = "all-loose-title") {
                Text("独立内容", color = AppPrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            items(content, key = ContentSummary::contentId) { item ->
                ContentRow(item, null, { onPlayContent(item.contentId) }, { onContentLongPress(item) })
            }
        }
    }
}

@Composable
private fun SelectableContentRow(
    item: ContentSummary,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(if (checked) AppSurfaceRaised else AppBackground)
                .clickable { onChecked(!checked) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.semantics { contentDescription = "选择 ${item.media.displayTitle}" },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.media.displayTitle, color = AppPrimaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.media.displayArtist?.let {
                Text(it, color = AppSecondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(color = AppHairline)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderContentRow(item: ContentSummary, index: Int, itemCount: Int, onMove: (Int, Int) -> Unit) {
    var dragOffset by remember(item.contentId) { mutableFloatStateOf(0f) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = dragOffset }
                .semantics {
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction("上移") { onMove(index, index - 1); true })
                        if (index < itemCount - 1) add(CustomAccessibilityAction("下移") { onMove(index, index + 1); true })
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${index + 1}", color = AppSecondaryText, modifier = Modifier.width(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.media.displayTitle, color = AppPrimaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.media.displayArtist?.let { Text(it, color = AppSecondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pointerInput(item.contentId, index, itemCount) {
                        detectDragGesturesAfterLongPress(
                            onDragEnd = {
                                val target = (index + (dragOffset / 64.dp.toPx()).roundToInt()).coerceIn(0, itemCount - 1)
                                dragOffset = 0f
                                if (target != index) onMove(index, target)
                            },
                            onDragCancel = { dragOffset = 0f },
                            onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.DragHandle, contentDescription = "拖动重排 ${item.media.displayTitle}", tint = AppSecondaryText) }
        }
        HorizontalDivider(color = AppHairline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistMembershipSheet(
    title: String,
    playlists: List<PlaylistSummary>,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>, String?) -> Unit,
    defaultToFavorite: Boolean = true,
) {
    val defaultPlaylistId = playlists.firstOrNull { it.isDefaultFavorite }?.playlistId
    var selectedIds by remember(initialSelection, defaultPlaylistId) {
        mutableStateOf(initialSelection.ifEmpty { if (defaultToFavorite) defaultPlaylistId?.let(::setOf).orEmpty() else emptySet() })
    }
    var newPlaylistName by remember { mutableStateOf("") }
    fun toggle(playlistId: String, checked: Boolean) {
        selectedIds =
            if (checked) {
                if (playlistId != defaultPlaylistId && selectedIds == setOfNotNull(defaultPlaylistId)) {
                    setOf(playlistId)
                } else {
                    selectedIds + playlistId
                }
            } else {
                (selectedIds - playlistId).ifEmpty { if (defaultToFavorite) defaultPlaylistId?.let(::setOf).orEmpty() else emptySet() }
            }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppSurfaceRaised) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("加入歌单", color = AppSecondaryText, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(playlists, key = { it.playlistId }) { playlist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            toggle(playlist.playlistId, playlist.playlistId !in selectedIds)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = playlist.playlistId in selectedIds,
                            onCheckedChange = { checked -> toggle(playlist.playlistId, checked) },
                        )
                        Text(playlist.name, color = AppPrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("或新建歌单") },
                singleLine = true,
            )
            Button(
                enabled = selectedIds.isNotEmpty() || newPlaylistName.isNotBlank(),
                onClick = { onSave(selectedIds, newPlaylistName.trim().takeIf(String::isNotEmpty)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("保存") }
        }
    }
}

internal enum class PlaylistBatchTargetMode { Add, Move }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistBatchTargetSheet(
    mode: PlaylistBatchTargetMode,
    selectedContentCount: Int,
    playlists: List<PlaylistSummary>,
    excludedPlaylistId: String?,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, String?) -> Unit,
) {
    val availablePlaylists = playlists.filterNot { it.playlistId == excludedPlaylistId }
    var selectedIds by remember(mode, excludedPlaylistId) { mutableStateOf(emptySet<String>()) }
    var newPlaylistName by remember(mode, excludedPlaylistId) { mutableStateOf("") }
    fun toggle(playlistId: String, checked: Boolean) {
        selectedIds =
            when {
                !checked -> selectedIds - playlistId
                mode == PlaylistBatchTargetMode.Move -> setOf(playlistId)
                else -> selectedIds + playlistId
            }
        if (checked && mode == PlaylistBatchTargetMode.Move) newPlaylistName = ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppSurfaceRaised) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                if (mode == PlaylistBatchTargetMode.Add) "将 $selectedContentCount 项加入歌单" else "移动 $selectedContentCount 项",
                color = AppPrimaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (mode == PlaylistBatchTargetMode.Add) "可同时选择多个目标，原有归属保持不变" else "内容会从当前歌单移出，其他已有归属保持不变",
                color = AppSecondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                if (availablePlaylists.isEmpty()) {
                    item {
                        Text(
                            "没有其他歌单，可以在下方新建",
                            color = AppSecondaryText,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        )
                    }
                }
                items(availablePlaylists, key = PlaylistSummary::playlistId) { playlist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            toggle(playlist.playlistId, playlist.playlistId !in selectedIds)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = playlist.playlistId in selectedIds,
                            onCheckedChange = { toggle(playlist.playlistId, it) },
                        )
                        Text(playlist.name, color = AppPrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { value ->
                    newPlaylistName = value
                    if (mode == PlaylistBatchTargetMode.Move && value.isNotBlank()) selectedIds = emptySet()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("或新建歌单") },
                singleLine = true,
            )
            Button(
                enabled = selectedIds.isNotEmpty() || newPlaylistName.isNotBlank(),
                onClick = { onConfirm(selectedIds, newPlaylistName.trim().takeIf(String::isNotEmpty)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp),
            ) { Text(if (mode == PlaylistBatchTargetMode.Add) "加入" else "移动") }
        }
    }
}

@Composable
internal fun PlaylistNameDialog(
    title: String,
    value: String,
    onValueChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = onValueChanged, label = { Text("名称") }, singleLine = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(enabled = value.trim().isNotBlank(), onClick = onConfirm) { Text("保存") } },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
internal fun DeletePlaylistDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除歌单") },
        text = { Text("删除“$name”不会删除其中的内容，也不会改变当前播放列表。") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        containerColor = AppSurfaceRaised,
    )
}
