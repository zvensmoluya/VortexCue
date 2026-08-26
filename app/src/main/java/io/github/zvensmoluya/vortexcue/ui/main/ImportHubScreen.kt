package io.github.zvensmoluya.vortexcue.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.DiscoveryGroupSummary
import io.github.zvensmoluya.vortexcue.data.DiscoveryItem
import io.github.zvensmoluya.vortexcue.data.DiscoveryItemStatus
import io.github.zvensmoluya.vortexcue.data.ImportContentResult
import io.github.zvensmoluya.vortexcue.data.PlaylistSummary
import io.github.zvensmoluya.vortexcue.data.SourceSummary
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportHubScreen(
    repository: ContentRepository,
    sources: List<SourceSummary>,
    playlists: List<PlaylistSummary>,
    coordinator: LibraryCoordinatorState,
    initialTargetPlaylistId: String? = null,
    singleFileImportError: String? = null,
    onContentImported: suspend (List<String>) -> Unit = {},
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onAddFolder: () -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshSource: (String) -> Unit,
    onRelinkSource: (String) -> Unit,
    onCancelScan: () -> Unit,
    onRemoveSource: (String) -> Unit,
) {
    val groups by repository.discoveryGroups.collectAsState(initial = emptyList())
    var selectedSourceKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGroup = groups.firstOrNull { it.sourceKey == selectedSourceKey }

    LaunchedEffect(Unit) {
        onRefreshLibrary()
    }
    LaunchedEffect(selectedSourceKey, selectedGroup) {
        if (selectedSourceKey != null && selectedGroup == null) selectedSourceKey = null
    }
    BackHandler {
        if (selectedSourceKey != null) selectedSourceKey = null else onDismiss()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
        if (selectedSourceKey != null && selectedGroup != null) {
            if (selectedGroup.isTrustedWorkPackage) {
                ImportWorkDetail(
                    group = selectedGroup,
                    repository = repository,
                    onContentImported = onContentImported,
                    onBack = { selectedSourceKey = null },
                    onDone = onDismiss,
                )
            } else {
                ImportSourceDetail(
                    group = selectedGroup,
                    repository = repository,
                    playlists = playlists,
                    initialTargetPlaylistId = initialTargetPlaylistId,
                    onContentImported = onContentImported,
                    onBack = { selectedSourceKey = null },
                    onDone = onDismiss,
                )
            }
        } else {
            ImportSourceOverview(
                groups = groups,
                sources = sources,
                coordinator = coordinator,
                singleFileImportError = singleFileImportError,
                onDismiss = onDismiss,
                onOpenSource = { selectedSourceKey = it },
                onPickMedia = onPickMedia,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportWorkDetail(
    group: DiscoveryGroupSummary,
    repository: ContentRepository,
    onContentImported: suspend (List<String>) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val items by remember(repository, group.sourceKey) { repository.observeDiscoveryItems(group.sourceKey) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedIds by remember(group.sourceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var initialized by remember(group.sourceKey) { mutableStateOf(false) }
    var importing by remember(group.sourceKey) { mutableStateOf(false) }
    var completion by remember(group.sourceKey) { mutableStateOf<String?>(null) }
    var error by remember(group.sourceKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(items) {
        if (!initialized && items.isNotEmpty()) {
            selectedIds = items.filter(DiscoveryItem::isDefaultSelected).mapTo(linkedSetOf(), DiscoveryItem::contentId)
            initialized = true
        } else if (initialized) {
            selectedIds = selectedIds.intersect(items.mapTo(hashSetOf(), DiscoveryItem::contentId))
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.displayName, color = AppPrimaryText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(group.releaseCode.orEmpty(), color = AppSecondaryText, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回作品列表" }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = AppPrimaryText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                error?.let { Text(it, color = AppError, fontSize = 12.sp) }
                completion?.let { Text(it, color = AppAccent, fontSize = 12.sp) }
                if (completion == null) {
                    Text("${selectedIds.size} 个播放段落 · WAV 优先 · 保留发行顺序", color = AppSecondaryText, fontSize = 12.sp)
                    Button(
                        enabled = selectedIds.isNotEmpty() && !importing,
                        onClick = {
                            scope.launch {
                                importing = true
                                error = null
                                runCatching {
                                    val result = repository.importWork(group.sourceKey, selectedIds)
                                    onContentImported(selectedIds.toList())
                                    result
                                }.onSuccess { result ->
                                    completion = "已导入作品 · ${result.partCount} 个播放段落"
                                }.onFailure { error = it.message ?: "无法导入作品" }
                                importing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            when {
                                importing -> "正在导入作品…"
                                group.importedWorkId != null -> "更新这部作品"
                                else -> "导入这部作品"
                            },
                        )
                    }
                } else {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("完成") }
                }
            }
        },
    ) { insets ->
        LazyColumn(modifier = Modifier.padding(insets).fillMaxSize()) {
            item(key = "work-summary") {
                Column(modifier = Modifier.fillMaxWidth().background(AppSurfaceSelected).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("完整分发作品", color = AppPrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("荧荧会把格式副本合并为同一段落，并把作品作为一个整体保存。", color = AppSecondaryText, fontSize = 13.sp)
                    Text("正篇与特典将按发行顺序保存", color = AppSecondaryText, fontSize = 12.sp)
                }
            }
            items.groupBy(DiscoveryItem::sectionName).forEach { (section, sectionItems) ->
                item(key = "section:$section") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(section, color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
                            Text("${sectionItems.count { it.contentId in selectedIds }} / ${sectionItems.size} 个文件", color = AppSecondaryText, fontSize = 11.sp)
                        }
                        TextButton(onClick = {
                            val ids = sectionItems.mapTo(hashSetOf(), DiscoveryItem::contentId)
                            selectedIds = if (ids.all(selectedIds::contains)) selectedIds - ids else selectedIds + ids
                        }) { Text(if (sectionItems.all { it.contentId in selectedIds }) "取消" else "包含") }
                    }
                }
                items(sectionItems, key = DiscoveryItem::contentId) { item ->
                    ImportItemRow(
                        item = item,
                        checked = item.contentId in selectedIds,
                        onChecked = { checked ->
                            selectedIds = if (checked) selectedIds + item.contentId else selectedIds - item.contentId
                        },
                        onIgnore = null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSourceOverview(
    groups: List<DiscoveryGroupSummary>,
    sources: List<SourceSummary>,
    coordinator: LibraryCoordinatorState,
    singleFileImportError: String?,
    onDismiss: () -> Unit,
    onOpenSource: (String) -> Unit,
    onPickMedia: () -> Unit,
    onAddFolder: () -> Unit,
    onRequestLibraryAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshSource: (String) -> Unit,
    onRelinkSource: (String) -> Unit,
    onCancelScan: () -> Unit,
    onRemoveSource: (String) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val recommended = groups.filter(DiscoveryGroupSummary::isRecommended)
    val visibleGroups = if (showAll || recommended.isEmpty()) groups else recommended
    val brokenSources = sources.filter { !it.hasAccess || it.lastError != null }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("添加内容", color = AppBoneLogo, fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics { contentDescription = "关闭添加内容" },
                    ) { Icon(Icons.Outlined.Close, null, tint = AppPrimaryText) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!coordinator.hasAudioAccess) {
                item(key = "permission") {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("查找设备中的音频", color = AppPrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (coordinator.hasRequestedAudioAccess) {
                                "没有音频访问权限。你仍可以选择文件或从其他位置查找音频。"
                            } else {
                                "荧荧会把可能的内容整理出来，不会移动或删除原文件。"
                            },
                            color = AppSecondaryText,
                        )
                        Button(onClick = onRequestLibraryAccess, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (coordinator.hasRequestedAudioAccess) "再次允许" else "开始查找")
                        }
                    }
                }
            }
            if (coordinator.hasAudioAccess || groups.isNotEmpty()) {
                item(key = "discovery-title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (showAll) "全部音频位置" else "发现的音频",
                                color = AppPrimaryText,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (coordinator.isLoading) Text("正在刷新，已发现的内容仍可浏览", color = AppSecondaryText, fontSize = 12.sp)
                        }
                        IconButton(
                            onClick = onRefreshLibrary,
                            modifier = Modifier.semantics { contentDescription = "刷新音频来源" },
                        ) { Icon(Icons.Outlined.Refresh, null, tint = AppSecondaryText) }
                    }
                }
                if (coordinator.isLoading) {
                    item(key = "loading") {
                        Column {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = AppAccent,
                                trackColor = AppHairline,
                            )
                            TextButton(onClick = onCancelScan, modifier = Modifier.fillMaxWidth()) { Text("取消刷新") }
                        }
                    }
                }
                coordinator.errorMessage?.let { message ->
                    item(key = "scan-error") { Text(message, color = AppError, fontSize = 12.sp) }
                }
                if (visibleGroups.isEmpty() && !coordinator.isLoading) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("没有发现音频", color = AppPrimaryText, fontSize = 18.sp)
                            Text("仍可以主动选择文件或其他位置", color = AppSecondaryText, fontSize = 13.sp)
                        }
                    }
                }
                items(visibleGroups, key = DiscoveryGroupSummary::sourceKey) { group ->
                    ImportSourceCard(
                        group = group,
                        source = sources.firstOrNull { it.sourceId == group.sourceId },
                        onOpen = { onOpenSource(group.sourceKey) },
                        onRefresh = { onRefreshSource(group.sourceId) },
                        onRelink = { onRelinkSource(group.sourceId) },
                        onRemove = { onRemoveSource(group.sourceId) },
                    )
                }
                if (recommended.isNotEmpty() && groups.size > recommended.size) {
                    item(key = "show-all") {
                        TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (showAll) "只看推荐来源" else "查看全部位置", color = AppAccent)
                        }
                    }
                }
            }

            if (brokenSources.isNotEmpty()) {
                item(key = "broken-title") { Text("需要处理的来源", color = AppSecondaryText, fontSize = 12.sp) }
                items(brokenSources, key = { "broken:${it.sourceId}" }) { source ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.displayName, color = AppPrimaryText)
                            Text(source.lastError ?: "需要重新授权", color = AppError, fontSize = 11.sp)
                        }
                        if (source.kind == LibrarySourceKind.Folder) {
                            TextButton(onClick = { onRelinkSource(source.sourceId) }) { Text("重新授权") }
                        }
                    }
                }
            }

            item(key = "other-title") {
                Text("其他方式", color = AppSecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            singleFileImportError?.let { message ->
                item(key = "single-file-error") {
                    Text(message, color = AppError, fontSize = 12.sp)
                }
            }
            item(key = "file") {
                ImportActionRow(
                    label = "选择音频或视频文件",
                    icon = Icons.Outlined.AudioFile,
                    onClick = onPickMedia,
                )
            }
            item(key = "folder") {
                ImportActionRow(
                    label = "从其他位置查找音频",
                    icon = Icons.Outlined.FolderOpen,
                    onClick = onAddFolder,
                )
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ImportSourceCard(
    group: DiscoveryGroupSummary,
    source: SourceSummary?,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRelink: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised).clickable(onClick = onOpen).padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.displayName, color = AppPrimaryText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group.pendingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (group.isTrustedWorkPackage) "待整理" else "新增 ${group.pendingCount}",
                        color = AppAccent,
                        fontSize = 11.sp,
                    )
                }
            }
            Text(
                if (group.isTrustedWorkPackage) "完整作品 · ${group.totalCount} 个音频" else "${group.totalCount} 个音频",
                color = AppSecondaryText,
                fontSize = 12.sp,
            )
            if (group.sampleTitles.isNotEmpty()) {
                Text(group.sampleTitles.joinToString("、"), color = AppMutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            group.errorMessage?.let { Text(it, color = AppError, fontSize = 11.sp, maxLines = 1) }
        }
        Box {
            IconButton(
                onClick = { menuVisible = true },
                modifier = Modifier.semantics { contentDescription = "${group.displayName} 更多操作" },
            ) { Icon(Icons.Outlined.MoreVert, null, tint = AppSecondaryText) }
            DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                DropdownMenuItem(
                    text = { Text("刷新") },
                    onClick = { menuVisible = false; onRefresh() },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                )
                if (source?.kind == LibrarySourceKind.Folder) {
                    if (!source.hasAccess) {
                        DropdownMenuItem(text = { Text("重新授权") }, onClick = { menuVisible = false; onRelink() })
                    }
                    DropdownMenuItem(
                        text = { Text("移除来源") },
                        onClick = { menuVisible = false; onRemove() },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSourceDetail(
    group: DiscoveryGroupSummary,
    repository: ContentRepository,
    playlists: List<PlaylistSummary>,
    initialTargetPlaylistId: String?,
    onContentImported: suspend (List<String>) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val items by remember(repository, group.sourceKey) { repository.observeDiscoveryItems(group.sourceKey) }
        .collectAsState(initial = emptyList())
    val preferredTargets by remember(repository, group.sourceKey) { repository.observeImportTargets(group.sourceKey) }
        .collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var range by rememberSaveable(group.sourceKey) { mutableStateOf(ImportRange.New) }
    var selectedIds by remember(group.sourceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var selectionInitialized by remember(group.sourceKey) { mutableStateOf(false) }
    var automaticSelection by remember(group.sourceKey) { mutableStateOf(true) }
    var targetIds by remember(group.sourceKey, initialTargetPlaylistId) { mutableStateOf<Set<String>>(emptySet()) }
    var targetsInitialized by remember(group.sourceKey, initialTargetPlaylistId) { mutableStateOf(false) }
    var targetSheetVisible by rememberSaveable(group.sourceKey) { mutableStateOf(false) }
    var notice by remember(group.sourceKey) { mutableStateOf<String?>(null) }
    var completion by remember(group.sourceKey) { mutableStateOf<ImportCompletion?>(null) }
    var importing by remember(group.sourceKey) { mutableStateOf(false) }
    val contextualTargetId = initialTargetPlaylistId?.takeIf { id -> playlists.any { it.playlistId == id } }

    LaunchedEffect(items) {
        if (!selectionInitialized && items.isNotEmpty()) {
            selectedIds = items.filter(DiscoveryItem::isDefaultSelected).mapTo(linkedSetOf()) { it.contentId }
            selectionInitialized = true
            if (items.none { it.status == DiscoveryItemStatus.Pending }) range = ImportRange.All
        } else if (selectionInitialized) {
            selectedIds =
                if (automaticSelection) {
                    items.filter(DiscoveryItem::isDefaultSelected).mapTo(linkedSetOf()) { it.contentId }
                } else {
                    selectedIds.intersect(items.mapTo(hashSetOf(), DiscoveryItem::contentId))
                }
        }
    }
    LaunchedEffect(preferredTargets, contextualTargetId) {
        if (!targetsInitialized) {
            val initialTargets = contextualTargetId?.let(::setOf) ?: preferredTargets
            if (initialTargets.isNotEmpty()) {
                targetIds = initialTargets
                targetsInitialized = true
            }
        }
    }

    val visibleItems = if (range == ImportRange.New) items.filter { it.status == DiscoveryItemStatus.Pending } else items
    val visibleSelectedCount = visibleItems.count { it.contentId in selectedIds }
    val orderedSelected = items.filter { it.contentId in selectedIds }.map(DiscoveryItem::contentId)
    val targetNames = playlists.filter { it.playlistId in targetIds }.map(PlaylistSummary::name)
    val targetSummary =
        when {
            targetNames.isEmpty() -> "正在读取目标歌单…"
            targetNames.size == 1 -> targetNames.single()
            else -> targetNames.joinToString("、")
        }
    val importButtonLabel =
        when {
            targetIds.isEmpty() -> "选择导入歌单"
            targetNames.size == 1 -> "将 ${orderedSelected.size} 项加入「${targetNames.single()}」"
            else -> "将 ${orderedSelected.size} 项加入 ${targetIds.size} 个歌单"
        }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.displayName, color = AppPrimaryText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.totalCount} 个音频", color = AppSecondaryText, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "返回内容来源" },
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = AppPrimaryText) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .background(AppSurfaceSelected)
                            .clickable { targetSheetVisible = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("导入到", color = AppSecondaryText, fontSize = 12.sp)
                        Text(
                            targetSummary,
                            color = AppPrimaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("更改", color = AppAccent)
                }
                notice?.let { Text(it, color = AppAccent, fontSize = 12.sp) }
                Button(
                    enabled = orderedSelected.isNotEmpty() && targetIds.isNotEmpty() && !importing,
                    onClick = {
                        automaticSelection = false
                        scope.launch {
                            importing = true
                            runCatching {
                                val result =
                                    repository.importContent(
                                        orderedSelected,
                                        targetIds,
                                        group.sourceKey.takeIf { contextualTargetId == null },
                                    )
                                onContentImported(orderedSelected)
                                result
                            }.onSuccess { result ->
                                completion = ImportCompletion(importResultNotice(result, targetNames, targetIds.size))
                                notice = null
                                selectedIds = emptySet()
                                scope.launch { listState.scrollToItem(0) }
                            }.onFailure { notice = it.message ?: "添加失败" }
                            importing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(importButtonLabel)
                }
            }
        },
    ) { insets ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(insets).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
        ) {
            completion?.let { result ->
                item(key = "import-completion") {
                    Surface(color = AppSurfaceSelected, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("添加完成", color = AppPrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(result.message, color = AppSecondaryText, fontSize = 13.sp)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { range = ImportRange.All }) { Text("查看全部") }
                                Button(onClick = onDone) { Text("完成") }
                            }
                        }
                    }
                }
            }
            item(key = "controls") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("选择范围", color = AppSecondaryText, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        ImportRangeButton(
                            label = "新增 ${items.count { it.status == DiscoveryItemStatus.Pending }} 项",
                            selected = range == ImportRange.New,
                            onClick = { range = ImportRange.New },
                        )
                        ImportRangeButton(
                            label = "全部 ${items.size} 项",
                            selected = range == ImportRange.All,
                            onClick = { range = ImportRange.All },
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (visibleSelectedCount == selectedIds.size) {
                                "已选择 ${selectedIds.size} / ${visibleItems.size}"
                            } else {
                                "已选择 ${selectedIds.size} 项 · 当前范围 $visibleSelectedCount / ${visibleItems.size}"
                            },
                            color = AppSecondaryText,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                automaticSelection = false
                                selectedIds = selectedIds + visibleItems.map(DiscoveryItem::contentId)
                            },
                        ) { Text("全选") }
                        TextButton(
                            onClick = {
                                automaticSelection = false
                                selectedIds = selectedIds - visibleItems.map(DiscoveryItem::contentId).toSet()
                            },
                        ) { Text("取消全选") }
                    }
                }
            }
            if (visibleItems.isEmpty()) {
                item(key = "range-empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (range == ImportRange.New) "这个来源暂时没有新内容" else "这个来源没有可用内容",
                            color = AppPrimaryText,
                            fontSize = 16.sp,
                        )
                        if (range == ImportRange.New && items.isNotEmpty()) {
                            Text("可以查看已经收录的全部内容", color = AppSecondaryText, fontSize = 12.sp)
                        }
                    }
                }
            }
            items(visibleItems, key = DiscoveryItem::contentId) { item ->
                ImportItemRow(
                    item = item,
                    checked = item.contentId in selectedIds,
                    onChecked = { checked ->
                        automaticSelection = false
                        selectedIds = if (checked) selectedIds + item.contentId else selectedIds - item.contentId
                    },
                    onIgnore =
                        if (item.status == DiscoveryItemStatus.Pending) {
                            { scope.launch { repository.ignore(item.contentId) } }
                        } else {
                            null
                        },
                )
            }
        }
    }

    if (targetSheetVisible) {
        ImportTargetSheet(
            playlists = playlists,
            initialSelection = targetIds,
            onDismiss = { targetSheetVisible = false },
            onConfirm = { selected, newName ->
                scope.launch {
                    runCatching {
                        val created = newName?.let { repository.createPlaylist(it) }
                        val defaultId = playlists.firstOrNull { it.isDefaultFavorite }?.playlistId
                        val base = if (created != null && selected == setOfNotNull(defaultId)) emptySet() else selected
                        (base + listOfNotNull(created?.playlistId)).ifEmpty { setOfNotNull(defaultId) }
                    }.onSuccess {
                        targetIds = it
                        targetsInitialized = true
                        targetSheetVisible = false
                    }.onFailure { notice = it.message ?: "无法新建歌单" }
                }
            },
        )
    }
}

@Composable
private fun ImportRangeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

@Composable
private fun ImportItemRow(
    item: DiscoveryItem,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onIgnore: (() -> Unit)?,
) {
    var menuVisible by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onChecked(!checked) }.padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.semantics { contentDescription = "选择 ${item.title}" },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = AppPrimaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatDuration(item.durationMs), color = AppSecondaryText, fontSize = 11.sp)
                if (item.status == DiscoveryItemStatus.Collected) Text("已收录", color = AppAccent, fontSize = 11.sp)
            }
        }
        if (onIgnore != null) {
            Box {
                IconButton(
                    onClick = { menuVisible = true },
                    modifier = Modifier.semantics { contentDescription = "${item.title} 更多操作" },
                ) { Icon(Icons.Outlined.MoreVert, null, tint = AppSecondaryText) }
                DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                    DropdownMenuItem(
                        text = { Text("不再推荐") },
                        onClick = { menuVisible = false; onIgnore() },
                        leadingIcon = { Icon(Icons.Outlined.Close, null) },
                    )
                }
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
    HorizontalDivider(color = AppHairline)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportTargetSheet(
    playlists: List<PlaylistSummary>,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, String?) -> Unit,
) {
    val defaultId = playlists.firstOrNull { it.isDefaultFavorite }?.playlistId
    var selected by remember(initialSelection, defaultId) {
        mutableStateOf(initialSelection.ifEmpty { setOfNotNull(defaultId) })
    }
    var newName by remember { mutableStateOf("") }
    val confirmedTargetCount =
        when {
            newName.isBlank() -> selected.size
            selected == setOfNotNull(defaultId) -> 1
            else -> selected.size + 1
        }
    fun toggle(id: String, checked: Boolean) {
        selected =
            if (checked) {
                if (id != defaultId && selected == setOfNotNull(defaultId)) setOf(id) else selected + id
            } else {
                (selected - id).ifEmpty { setOfNotNull(defaultId) }
            }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppSurfaceRaised) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("加入歌单", color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("可以同时选择多个歌单", color = AppSecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(playlists, key = PlaylistSummary::playlistId) { playlist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { toggle(playlist.playlistId, playlist.playlistId !in selected) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = playlist.playlistId in selected,
                            onCheckedChange = { toggle(playlist.playlistId, it) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(playlist.name, color = AppPrimaryText)
                            if (playlist.isDefaultFavorite) {
                                Text(
                                    "默认落点；选择其他歌单时自动让位",
                                    color = AppSecondaryText,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        if (playlist.isDefaultFavorite) Icon(Icons.Outlined.Check, contentDescription = "默认落点", tint = AppMutedText, modifier = Modifier.size(18.dp))
                    }
                }
            }
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("或新建歌单") },
                singleLine = true,
            )
            Button(
                enabled = selected.isNotEmpty() || newName.isNotBlank(),
                onClick = { onConfirm(selected, newName.trim().takeIf(String::isNotBlank)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp),
            ) { Text("加入 $confirmedTargetCount 个歌单") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ImportActionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AppSecondaryText)
        Spacer(Modifier.width(12.dp))
        Text(label, color = AppPrimaryText, modifier = Modifier.weight(1f))
    }
}

private enum class ImportRange { New, All }

private data class ImportCompletion(val message: String)

private fun importResultNotice(
    result: ImportContentResult,
    targetNames: List<String>,
    targetCount: Int,
): String =
    when {
        result.changedContentCount == 0 -> "所选 ${result.contentCount} 项已在目标歌单"
        result.unchangedContentCount > 0 ->
            "已更新 ${result.changedContentCount} 项，另有 ${result.unchangedContentCount} 项已在目标歌单"
        targetNames.size == 1 -> "已将 ${result.changedContentCount} 项加入「${targetNames.single()}」"
        result.addedPlaylistMembershipCount == result.changedContentCount * targetCount ->
            "已将 ${result.changedContentCount} 项加入 $targetCount 个歌单"
        else -> "已更新 ${result.changedContentCount} 项的歌单归属"
    }

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
