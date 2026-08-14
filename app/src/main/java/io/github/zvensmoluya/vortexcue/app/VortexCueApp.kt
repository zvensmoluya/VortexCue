package io.github.zvensmoluya.vortexcue.app

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import io.github.zvensmoluya.vortexcue.core.model.ActiveSession
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import io.github.zvensmoluya.vortexcue.core.model.LoadedSubtitle
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.PlayerSurface
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.storage.displayName
import io.github.zvensmoluya.vortexcue.core.storage.persistReadPermission
import io.github.zvensmoluya.vortexcue.core.storage.readSubtitleBytes
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.data.ContentDisposition
import io.github.zvensmoluya.vortexcue.data.ContentSummary
import io.github.zvensmoluya.vortexcue.data.HomeLibraryProjection
import io.github.zvensmoluya.vortexcue.data.PlaybackRecoveryProjection
import io.github.zvensmoluya.vortexcue.data.SettingsState
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinator
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
import io.github.zvensmoluya.vortexcue.library.LibrarySourceIds
import io.github.zvensmoluya.vortexcue.library.TimedTextTrackCandidate
import io.github.zvensmoluya.vortexcue.library.mediaLibraryPermissionsToRequest
import io.github.zvensmoluya.vortexcue.library.toSubtitleSourceRef
import io.github.zvensmoluya.vortexcue.playback.PlaybackGateway
import io.github.zvensmoluya.vortexcue.playback.PlayerMaterializationState
import io.github.zvensmoluya.vortexcue.playback.ownsRuntimePlaybackState
import io.github.zvensmoluya.vortexcue.playback.applyEmbeddedTextTrackSelection
import io.github.zvensmoluya.vortexcue.playback.cancelSleepTimer
import io.github.zvensmoluya.vortexcue.playback.rememberEmbeddedTextRuntimeState
import io.github.zvensmoluya.vortexcue.playback.rememberSleepTimerState
import io.github.zvensmoluya.vortexcue.playback.setSleepTimer
import io.github.zvensmoluya.vortexcue.playback.stopAfterCurrentItem
import io.github.zvensmoluya.vortexcue.playback.stopAfterTonight
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleAssetStore
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleParser
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleTimeline
import io.github.zvensmoluya.vortexcue.ui.home.HomeScreen
import io.github.zvensmoluya.vortexcue.ui.home.HomeSection
import io.github.zvensmoluya.vortexcue.ui.player.PlayerScreen
import io.github.zvensmoluya.vortexcue.ui.player.rememberPlaybackSnapshot
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppDestination {
    Home,
    Player,
}

private enum class MediaPickTarget {
    Draft,
    Relink,
}

private data class SubtitlePickTarget(
    val contentId: String,
    val slot: TextTrackSlot,
)

@Composable
internal fun VortexCueApp(
    player: MediaController,
    onWindowBrightnessChanged: (Float?) -> Unit,
    onImmersiveModeChanged: (Boolean) -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    externalMediaRequest: ExternalMediaRequest?,
    onExternalMediaRequestConsumed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember(context) { context.appContainer }
    val repository = container.contentRepository
    val playbackRepository = container.playbackStateRepository
    val settingsStore = container.settingsStore
    val subtitleAssetStore = container.subtitleAssetStore
    val contentResolver = context.contentResolver
    val gateway = remember(player) { PlaybackGateway(player) }
    val coordinator =
        remember(context, scope, repository) {
            LibraryCoordinator(context, scope, repository)
        }

    val home by repository.homeProjection.collectAsState(initial = HomeLibraryProjection())
    val recovery by playbackRepository.recovery.collectAsState(initial = PlaybackRecoveryProjection())
    val draftId by repository.observeDraft().collectAsState(initial = null)
    val draftFlow: Flow<ContentSummary?> =
        remember(repository, draftId) {
            draftId?.let(repository::observeSummary) ?: flowOf(null)
        }
    val draftSummary by draftFlow.collectAsState(initial = null)
    val persistedSettings by settingsStore.state.collectAsState(initial = SettingsState())
    val playback = rememberPlaybackSnapshot(player)
    val embeddedTextRuntime = rememberEmbeddedTextRuntimeState(player)

    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    var homeSection by rememberSaveable { mutableStateOf(HomeSection.Home) }
    var playerSurface by rememberSaveable { mutableStateOf(PlayerSurface.Normal) }
    var mediaPickTarget by remember { mutableStateOf(MediaPickTarget.Draft) }
    var relinkContentId by remember { mutableStateOf<String?>(null) }
    var subtitlePickTarget by remember { mutableStateOf<SubtitlePickTarget?>(null) }
    var folderRelinkSourceId by remember { mutableStateOf<String?>(null) }
    var materialization by remember { mutableStateOf(PlayerMaterializationState.NotLoaded) }
    var displayProfile by remember { mutableStateOf(DisplayProfile.Balanced) }
    var glowCalibrationCompleted by remember { mutableStateOf(false) }
    var glowGestureHintSeen by remember { mutableStateOf(false) }
    var primarySubtitle by remember { mutableStateOf<LoadedSubtitle?>(null) }
    var secondarySubtitle by remember { mutableStateOf<LoadedSubtitle?>(null) }
    var sleepTimerRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(persistedSettings) {
        displayProfile = persistedSettings.displayProfile
        glowCalibrationCompleted = persistedSettings.glowCalibrationCompleted
        glowGestureHintSeen = persistedSettings.glowGestureHintSeen
    }
    LaunchedEffect(player) {
        materialization = runCatching { gateway.materializationState() }
            .getOrDefault(PlayerMaterializationState.NotLoaded)
    }
    LaunchedEffect(playback.queue) {
        if (playback.queue.isNotEmpty()) materialization = PlayerMaterializationState.Materialized
    }
    val runtimeOwnsState = materialization.ownsRuntimePlaybackState()
    val runtimeCurrentId =
        playback.queue.getOrNull(playback.currentIndex)?.mediaId?.takeIf(String::isNotBlank)
    val currentContentId =
        if (runtimeOwnsState) runtimeCurrentId else recovery.currentItem?.contentId
    val currentDetailsFlow: Flow<ContentDetails?> =
        remember(repository, currentContentId) {
            currentContentId?.let(repository::observeDetails) ?: flowOf(null)
        }
    val currentDetails by currentDetailsFlow.collectAsState(initial = null)
    val currentUnit = currentDetails?.contentUnit
    val selectedPrimaryTrack = currentDetails?.selectedTrack(TextTrackSlot.Primary)
    val selectedSecondaryTrack = currentDetails?.selectedTrack(TextTrackSlot.Secondary)

    LaunchedEffect(currentContentId, selectedPrimaryTrack?.stableId) {
        primarySubtitle =
            loadSelectedTrack(
                contentId = currentContentId,
                track = selectedPrimaryTrack,
                contentResolver = contentResolver,
                subtitleAssetStore = subtitleAssetStore,
                repository = repository,
            )
    }
    LaunchedEffect(currentContentId, selectedSecondaryTrack?.stableId) {
        secondarySubtitle =
            loadSelectedTrack(
                contentId = currentContentId,
                track = selectedSecondaryTrack,
                contentResolver = contentResolver,
                subtitleAssetStore = subtitleAssetStore,
                repository = repository,
            )
    }

    val activeSession =
        currentDetails?.let {
            ActiveSession(
                media = it.activeMedia,
                subtitle = primarySubtitle,
                secondarySubtitle = secondarySubtitle,
                subtitleOffsetMs = it.subtitleOffsetMs,
            )
        }
    val selectedEmbeddedTrackId =
        listOfNotNull(selectedPrimaryTrack, selectedSecondaryTrack)
            .firstOrNull { it.origin == TimedTextOrigin.Embedded }
            ?.stableId

    LaunchedEffect(
        currentContentId,
        embeddedTextRuntime.mediaUriValue,
        embeddedTextRuntime.discoveryRevision,
    ) {
        val contentId = currentContentId ?: return@LaunchedEffect
        val media = currentDetails?.activeMedia ?: return@LaunchedEffect
        if (!embeddedTextRuntime.isDiscoveryComplete || embeddedTextRuntime.mediaUriValue != media.uri.toString()) {
            return@LaunchedEffect
        }
        repository.mergeTextTracks(contentId, embeddedTextRuntime.candidates.map(TimedTextTrackCandidate::track))
    }
    LaunchedEffect(
        currentContentId,
        embeddedTextRuntime.mediaUriValue,
        embeddedTextRuntime.discoveryRevision,
        selectedEmbeddedTrackId,
    ) {
        val media = currentDetails?.activeMedia ?: return@LaunchedEffect
        if (embeddedTextRuntime.mediaUriValue != media.uri.toString()) return@LaunchedEffect
        player.applyEmbeddedTextTrackSelection(
            tracks = embeddedTextRuntime.tracks,
            selectedTrackId = selectedEmbeddedTrackId,
        )
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startPlayback() {
        requestNotificationPermission()
        player.prepare()
        player.play()
    }

    fun togglePlayback() {
        if (player.isPlaying) player.pause() else startPlayback()
    }

    suspend fun openContentNow(contentIds: List<String>, index: Int): Boolean {
        if (contentIds.isEmpty() || index !in contentIds.indices) return false
        val result = gateway.replace(contentIds, index, 0L)
        if (result.resultCode != SessionResult.RESULT_SUCCESS) return false
        repository.recordOpened(contentIds[index])
        materialization = PlayerMaterializationState.Materialized
        playerSurface = PlayerSurface.Normal
        destination = AppDestination.Player
        startPlayback()
        return true
    }

    fun openContent(contentIds: List<String>, index: Int) {
        scope.launch { openContentNow(contentIds, index) }
    }

    fun continuePlayback() {
        if (recovery.queue.isEmpty() && playback.queue.isEmpty()) return
        scope.launch {
            val result = gateway.materialize()
            if (result.resultCode != SessionResult.RESULT_SUCCESS) return@launch
            materialization = PlayerMaterializationState.Materialized
            playerSurface = resolveResumeSurface(recovery.lastSurface, glowCalibrationCompleted)
            destination = AppDestination.Player
            startPlayback()
        }
    }

    fun selectTonight(contentId: String) {
        val index = recovery.queue.indexOfFirst { it.contentId == contentId }
        if (index < 0) return
        scope.launch {
            val result = gateway.materialize()
            if (result.resultCode != SessionResult.RESULT_SUCCESS) return@launch
            materialization = PlayerMaterializationState.Materialized
            player.seekToDefaultPosition(index)
            playerSurface = PlayerSurface.Normal
            destination = AppDestination.Player
            startPlayback()
        }
    }

    suspend fun inspectOpenedMedia(uri: Uri): MediaSourceRef {
        val initial =
            MediaSourceRef(
                uri = uri,
                displayName = contentResolver.displayName(uri),
                mimeType = contentResolver.safeMimeType(uri),
                origin = MediaOrigin.OpenDocument,
                sourceId = LibrarySourceIds.LooseFiles,
            )
        contentResolver.persistReadPermission(uri)
        ensureMediaReadable(contentResolver, initial)
        return coordinator.inspect(initial).copy(sourceId = LibrarySourceIds.LooseFiles)
    }

    suspend fun handleMediaUri(uri: Uri, target: MediaPickTarget, playImmediately: Boolean) {
        runCatchingSuspend { inspectOpenedMedia(uri) }
            .onSuccess { replacement ->
                when (target) {
                    MediaPickTarget.Draft -> {
                        val contentId = repository.resolveOrCreate(replacement)
                        repository.setDraft(contentId)
                        repository.recordOpened(contentId)
                        if (playImmediately) openContentNow(listOf(contentId), 0)
                    }
                    MediaPickTarget.Relink -> {
                        val targetId = relinkContentId ?: return@onSuccess
                        gateway.relink(targetId, replacement)
                        relinkContentId = null
                    }
                }
            }.onFailure { error ->
                if (target == MediaPickTarget.Relink) relinkContentId = null
                if (error is CancellationException) throw error
            }
    }

    fun consumeMediaUri(uri: Uri, target: MediaPickTarget, playImmediately: Boolean) {
        scope.launch { handleMediaUri(uri, target, playImmediately) }
    }

    val mediaPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { consumeMediaUri(it, mediaPickTarget, playImmediately = false) }
        }
    val subtitlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = subtitlePickTarget
            subtitlePickTarget = null
            if (uri != null && target != null) {
                scope.launch {
                    contentResolver.persistReadPermission(uri)
                    repository.addManualTextTrack(
                        contentId = target.contentId,
                        slot = target.slot,
                        source =
                            SubtitleSourceRef(
                                uri = uri,
                                displayName = contentResolver.displayName(uri),
                                mimeType = contentResolver.safeMimeType(uri),
                            ),
                    )
                }
            }
        }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val sourceId = folderRelinkSourceId
            folderRelinkSourceId = null
            if (uri != null) {
                if (sourceId == null) coordinator.addFolder(uri) else coordinator.replaceFolder(sourceId, uri)
            }
        }
    val libraryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            coordinator.scanAll()
        }

    LaunchedEffect(externalMediaRequest?.id) {
        val request = externalMediaRequest ?: return@LaunchedEffect
        handleMediaUri(request.uri, MediaPickTarget.Draft, playImmediately = true)
        onExternalMediaRequestConsumed(request.id)
    }

    DisposableEffect(player, currentDetails) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (!isMediaAccessErrorCode(error.errorCode)) return
                    val details = currentDetails ?: return
                    scope.launch {
                        repository.updateAvailability(
                            contentId = details.contentId,
                            locatorId = details.activeMediaLocatorId,
                            availability = ContentAvailability.Unavailable,
                        )
                    }
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val glowActive = destination == AppDestination.Player && playerSurface != PlayerSurface.Normal
    LaunchedEffect(glowActive, displayProfile) {
        onImmersiveModeChanged(glowActive)
        onWindowBrightnessChanged(if (glowActive) displayProfile.glowWindowBrightness else null)
    }
    LaunchedEffect(destination, playerSurface, playback.isPlaying) {
        onPlaybackActiveChanged(
            shouldKeepPlaybackScreenOn(
                isPlayerDestination = destination == AppDestination.Player,
                surface = playerSurface,
                isPlaying = playback.isPlaying,
            ),
        )
    }
    DisposableEffect(coordinator) {
        onDispose {
            coordinator.cancelScan()
            onPlaybackActiveChanged(false)
            onImmersiveModeChanged(false)
            onWindowBrightnessChanged(null)
        }
    }

    BackHandler(enabled = destination == AppDestination.Player) {
        if (playerSurface != PlayerSurface.Normal) playerSurface = PlayerSurface.Normal
        else {
            homeSection = HomeSection.Home
            destination = AppDestination.Home
        }
    }

    val sleepTimer = rememberSleepTimerState(player, sleepTimerRevision)

    when (destination) {
        AppDestination.Home ->
            HomeScreen(
                section = homeSection,
                onSectionChanged = { homeSection = it },
                repository = repository,
                home = home,
                coordinator = coordinator.state,
                draft = draftSummary,
                recovery = recovery,
                runtimeCurrentIndex = if (runtimeOwnsState) playback.currentIndex else recovery.currentIndex,
                runtimePositionMs = if (runtimeOwnsState) playback.positionMs else recovery.positionMs,
                playback = playback,
                displayProfile = displayProfile,
                onPickMedia = {
                    mediaPickTarget = MediaPickTarget.Draft
                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                },
                onRequestLibraryAccess = {
                    val permissions = mediaLibraryPermissionsToRequest(context)
                    if (permissions.isEmpty()) coordinator.scanAll()
                    else libraryPermissionLauncher.launch(permissions)
                },
                onRefreshLibrary = coordinator::scanAll,
                onRefreshSource = coordinator::refreshSource,
                onCancelScan = coordinator::cancelScan,
                onAddFolder = {
                    folderRelinkSourceId = null
                    folderPicker.launch(null)
                },
                onRelinkFolder = { sourceId ->
                    folderRelinkSourceId = sourceId
                    folderPicker.launch(null)
                },
                onRemoveSource = coordinator::removeSource,
                onCollect = { id -> scope.launch { repository.collect(id) } },
                onUncollect = { id -> scope.launch { repository.clearDisposition(id) } },
                onIgnore = { id -> scope.launch { repository.ignore(id) } },
                onRestoreIgnored = { id -> scope.launch { repository.clearDisposition(id) } },
                onPlaySingle = { id -> openContent(listOf(id), 0) },
                onPlayGroup = ::openContent,
                onSelectTonight = ::selectTonight,
                onAddToTonight = { id ->
                    scope.launch {
                        gateway.append(listOf(id))
                        materialization = PlayerMaterializationState.Materialized
                    }
                },
                onRemoveFromTonight = { id -> scope.launch { gateway.remove(id) } },
                onMoveTonight = { from, to -> scope.launch { gateway.move(from, to) } },
                onClearTonight = {
                    scope.launch {
                        gateway.clear()
                        materialization = PlayerMaterializationState.ExplicitlyCleared
                    }
                },
                onStartTonight = ::continuePlayback,
                onCreatePlaylist = { name, seed ->
                    scope.launch {
                        val id = repository.createPlaylist(name)
                        seed?.let { repository.addToPlaylist(id, it) }
                    }
                },
                onAddToPlaylist = { playlistId, contentId ->
                    scope.launch { repository.addToPlaylist(playlistId, contentId) }
                },
                onRemoveFromPlaylist = { playlistId, contentId ->
                    scope.launch { repository.removeFromPlaylist(playlistId, contentId) }
                },
                onDeletePlaylist = { id -> scope.launch { repository.deletePlaylist(id) } },
                onContinue = ::continuePlayback,
                onPlayPause = ::togglePlayback,
                onDisplayProfileChanged = { profile ->
                    displayProfile = profile
                    scope.launch { settingsStore.saveDisplayProfile(profile) }
                },
                onRelinkContent = { id ->
                    relinkContentId = id
                    mediaPickTarget = MediaPickTarget.Relink
                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                },
                onSelectContentTextTrack = { contentId, slot, trackId ->
                    scope.launch { repository.selectManualTextTrack(contentId, slot, trackId) }
                },
                onRestoreAutomaticContentTextTrack = { contentId, slot ->
                    scope.launch { repository.restoreAutomaticTextTrack(contentId, slot) }
                },
                onAddContentTextTrack = { contentId, slot ->
                    subtitlePickTarget = SubtitlePickTarget(contentId, slot)
                    subtitlePicker.launch(arrayOf("*/*"))
                },
                modifier = modifier,
            )
        AppDestination.Player -> {
            val session = activeSession
            if (session == null) {
                Box(
                    modifier = modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                PlayerScreen(
                    player = player,
                    session = session,
                    playback = playback,
                    surface = playerSurface,
                    displayProfile = displayProfile,
                    sleepTimer = sleepTimer,
                    showGestureHint = !glowGestureHintSeen,
                    isCollected = currentDetails?.disposition == ContentDisposition.Collected,
                    contentUnit = currentUnit,
                    embeddedCueTrackId = embeddedTextRuntime.cueTrackId,
                    embeddedCueText = embeddedTextRuntime.cueText,
                    onPlayPause = ::togglePlayback,
                    onSurfaceChanged = { requested ->
                        val resolved =
                            if (requested == PlayerSurface.Glow && !glowCalibrationCompleted) {
                                PlayerSurface.Calibration
                            } else {
                                requested
                            }
                        playerSurface = resolved
                        scope.launch { gateway.setLastSurface(resolved) }
                    },
                    onBackToLibrary = {
                        homeSection = HomeSection.Home
                        destination = AppDestination.Home
                    },
                    onOpenSources = {
                        homeSection = HomeSection.Sources
                        destination = AppDestination.Home
                    },
                    onRelinkMedia = {
                        currentContentId?.let { id ->
                            relinkContentId = id
                            mediaPickTarget = MediaPickTarget.Relink
                            mediaPicker.launch(arrayOf("audio/*", "video/*"))
                        }
                    },
                    onToggleCollected = {
                        val details = currentDetails ?: return@PlayerScreen
                        scope.launch {
                            if (details.disposition == ContentDisposition.Collected) {
                                repository.clearDisposition(details.contentId)
                            } else {
                                repository.collect(details.contentId)
                            }
                        }
                    },
                    onOpenTonight = {
                        homeSection = HomeSection.Tonight
                        destination = AppDestination.Home
                    },
                    onQueueSelect = { index ->
                        if (index in playback.queue.indices) {
                            player.seekToDefaultPosition(index)
                            startPlayback()
                        }
                    },
                    onQueueMove = { from, to -> scope.launch { gateway.move(from, to) } },
                    onQueueRemove = { index ->
                        val contentId =
                            if (runtimeOwnsState) {
                                playback.queue.getOrNull(index)?.mediaId
                            } else {
                                recovery.queue.getOrNull(index)?.contentId
                            }
                        contentId?.takeIf(String::isNotBlank)?.let { id ->
                            scope.launch { gateway.remove(id) }
                        }
                    },
                    onSelectTextTrack = { slot, trackId ->
                        currentContentId?.let { id ->
                            scope.launch { repository.selectManualTextTrack(id, slot, trackId) }
                        }
                    },
                    onRestoreAutomaticTextTrack = { slot ->
                        currentContentId?.let { id ->
                            scope.launch { repository.restoreAutomaticTextTrack(id, slot) }
                        }
                    },
                    onAddTextTrack = { slot ->
                        currentContentId?.let { id ->
                            subtitlePickTarget = SubtitlePickTarget(id, slot)
                            subtitlePicker.launch(arrayOf("*/*"))
                        }
                    },
                    onSubtitleOffsetChanged = { offset ->
                        currentContentId?.let { id -> scope.launch { repository.updateSubtitleOffset(id, offset) } }
                    },
                    onDisplayProfileChanged = { profile ->
                        displayProfile = profile
                        scope.launch { settingsStore.saveDisplayProfile(profile) }
                    },
                    onCalibrationCompleted = {
                        glowCalibrationCompleted = true
                        playerSurface = PlayerSurface.Glow
                        scope.launch {
                            settingsStore.saveGlowOnboarding(true, glowGestureHintSeen)
                            gateway.setLastSurface(PlayerSurface.Glow)
                        }
                    },
                    onGestureHintSeen = {
                        if (!glowGestureHintSeen) {
                            glowGestureHintSeen = true
                            scope.launch {
                                settingsStore.saveGlowOnboarding(glowCalibrationCompleted, true)
                            }
                        }
                    },
                    onSetSleepTimer = {
                        player.setSleepTimer(it)
                        sleepTimerRevision += 1
                    },
                    onStopAfterCurrent = {
                        player.stopAfterCurrentItem()
                        sleepTimerRevision += 1
                    },
                    onStopAfterTonight = {
                        player.stopAfterTonight()
                        sleepTimerRevision += 1
                    },
                    onCancelSleepTimer = {
                        player.cancelSleepTimer()
                        sleepTimerRevision += 1
                    },
                    modifier = modifier,
                )
            }
        }
    }
}

private suspend fun loadSelectedTrack(
    contentId: String?,
    track: TimedTextTrack?,
    contentResolver: ContentResolver,
    subtitleAssetStore: SubtitleAssetStore,
    repository: io.github.zvensmoluya.vortexcue.data.ContentRepository,
): LoadedSubtitle? {
    val id = contentId ?: return null
    val source = track?.toSubtitleSourceRef() ?: return null
    return try {
        val loaded = loadSubtitleSource(contentResolver, subtitleAssetStore, source)
        repository.updateTextTrackReadiness(
            contentId = id,
            trackId = track.stableId,
            readiness = loaded.readiness,
            resolvedSource = loaded.source,
        )
        loaded
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val failure = subtitleFailure(error)
        repository.updateTextTrackReadiness(
            contentId = id,
            trackId = track.stableId,
            readiness = failure.readiness,
            failureMessage = failure.message,
        )
        null
    }
}

private fun ContentDetails.selectedTrack(slot: TextTrackSlot): TimedTextTrack? {
    val id =
        when (slot) {
            TextTrackSlot.Primary -> activeText.primaryTrackId
            TextTrackSlot.Secondary -> activeText.secondaryTrackId
        }
    return timedTextTracks.firstOrNull { it.stableId == id }
}

private suspend inline fun <T> runCatchingSuspend(
    crossinline block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

private suspend fun loadSubtitleSource(
    contentResolver: ContentResolver,
    subtitleAssetStore: SubtitleAssetStore,
    source: SubtitleSourceRef,
): LoadedSubtitle =
    withContext(Dispatchers.IO) {
        val providerBytes = runCatching { contentResolver.readSubtitleBytes(source.uri) }
        val bytes =
            providerBytes.getOrElse { providerError ->
                subtitleAssetStore.read(source.cachedFileName) ?: throw providerError
            }
        val parseName =
            if (providerBytes.isSuccess) source.displayName else source.cachedFileName ?: source.displayName
        val cues = SubtitleParser.parse(parseName, bytes)
        require(cues.isNotEmpty()) { "字幕中没有可显示的同步文字" }
        val cachedFileName =
            if (providerBytes.isSuccess) {
                runCatching { subtitleAssetStore.store(source.uri, cues) }.getOrNull()
                    ?: source.cachedFileName
            } else {
                source.cachedFileName
            }
        LoadedSubtitle(
            source = source.copy(cachedFileName = cachedFileName),
            timeline = SubtitleTimeline(cues),
            usedPrivateCopy = providerBytes.isFailure,
        )
    }

private val LoadedSubtitle.readiness: TimedTextReadiness
    get() = if (usedPrivateCopy) TimedTextReadiness.PrivateCopy else TimedTextReadiness.Ready

private data class SubtitleFailure(
    val readiness: TimedTextReadiness,
    val message: String,
)

private fun subtitleFailure(error: Throwable): SubtitleFailure =
    when (error) {
        is SecurityException -> SubtitleFailure(TimedTextReadiness.Unreadable, "字幕授权已失效，请重新选择文件。")
        is FileNotFoundException -> SubtitleFailure(TimedTextReadiness.Unreadable, "字幕文件已移动、删除或无法访问，请重新选择。")
        is IOException -> SubtitleFailure(TimedTextReadiness.Unreadable, "无法读取字幕文件，请重新选择后重试。")
        else -> SubtitleFailure(TimedTextReadiness.ParseFailed, error.message ?: "无法解析字幕，请选择其他文件。")
    }

private suspend fun ensureMediaReadable(
    contentResolver: ContentResolver,
    source: MediaSourceRef,
) {
    withContext(Dispatchers.IO) {
        contentResolver.persistReadPermission(source.uri)
        val descriptor =
            contentResolver.openAssetFileDescriptor(source.uri, "r")
                ?: throw FileNotFoundException("Media descriptor is unavailable")
        descriptor.use { }
    }
}

private fun ContentResolver.safeMimeType(uri: Uri): String? =
    runCatching { getType(uri) }.getOrNull()

internal fun resolveRestorablePosition(
    currentPositionMs: Long,
    currentDurationMs: Long,
    previousPositionMs: Long?,
): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    return if (current == 0L && currentDurationMs <= 0L) {
        previousPositionMs?.coerceAtLeast(0L) ?: 0L
    } else {
        current
    }
}

internal fun resolveRestorableDuration(
    currentDurationMs: Long,
    previousDurationMs: Long?,
): Long =
    currentDurationMs.takeIf { it > 0L }
        ?: previousDurationMs?.coerceAtLeast(0L)
        ?: 0L

internal fun isMediaAccessErrorCode(errorCode: Int): Boolean =
    errorCode in
        setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        )

internal fun resolveResumeSurface(
    lastSurface: PlayerSurface,
    glowCalibrationCompleted: Boolean,
): PlayerSurface =
    when {
        lastSurface.asLastSurface() != PlayerSurface.Glow -> PlayerSurface.Normal
        glowCalibrationCompleted -> PlayerSurface.Glow
        else -> PlayerSurface.Calibration
    }
