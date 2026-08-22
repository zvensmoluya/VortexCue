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
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppBackground
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import kotlin.math.roundToInt

internal const val AllContentPlaylistId = "__all_content__"

@Composable
internal fun PlaylistOverview(
    playlists: List<PlaylistSummary>,
    recentPlaylists: List<PlaylistSummary>,
    allContentCount: Int,
    reorderMode: Boolean,
    listState: LazyListState,
    onOpenAllContent: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (PlaylistSummary) -> Unit,
    onDeletePlaylist: (PlaylistSummary) -> Unit,
    onReorderModeChanged: (Boolean) -> Unit,
    onMovePlaylist: (Int, Int) -> Unit,
) {
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
        itemsIndexed(playlists, key = { _, it -> it.playlistId }) { index, playlist ->
            if (reorderMode) {
                ReorderPlaylistCard(
                    playlist = playlist,
                    index = index,
                    itemCount = playlists.size,
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
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = AppPrimaryText) }
            Text(title, color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!isAllContent) {
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
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${content.size} 项内容", color = AppSecondaryText, modifier = Modifier.weight(1f))
            if (!isAllContent) {
                Button(enabled = content.isNotEmpty(), onClick = onPlayAll) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("播放全部")
                }
            }
        }
        if (content.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (isAllContent) "还没有内容" else "这个歌单还没有内容", color = AppSecondaryText)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                itemsIndexed(content, key = { _, item -> item.contentId }) { index, item ->
                    if (reorderMode && !isAllContent) {
                        ReorderContentRow(item, index, content.size, onMove)
                    } else {
                        ContentRow(item = item, status = null, onClick = { onPlay(index) }, onLongPress = { onLongPress(item) })
                    }
                }
            }
        }
    }
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
) {
    var selectedIds by remember(initialSelection) { mutableStateOf(initialSelection) }
    var newPlaylistName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppSurfaceRaised) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("加入歌单", color = AppSecondaryText, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(playlists, key = { it.playlistId }) { playlist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            selectedIds = if (playlist.playlistId in selectedIds) selectedIds - playlist.playlistId else selectedIds + playlist.playlistId
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = playlist.playlistId in selectedIds,
                            onCheckedChange = { checked -> selectedIds = if (checked) selectedIds + playlist.playlistId else selectedIds - playlist.playlistId },
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
                onClick = { onSave(selectedIds, newPlaylistName.trim().takeIf(String::isNotEmpty)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("保存") }
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
