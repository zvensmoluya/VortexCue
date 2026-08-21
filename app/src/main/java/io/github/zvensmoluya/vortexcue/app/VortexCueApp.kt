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
import io.github.zvensmoluya.vortexcue.core.model.LoadedGeneratedText
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.PlayerSurface
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.storage.displayName
import io.github.zvensmoluya.vortexcue.core.storage.persistReadPermission
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.data.ContentDisposition
import io.github.zvensmoluya.vortexcue.data.PlaybackRecoveryProjection
import io.github.zvensmoluya.vortexcue.data.SettingsState
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinator
import io.github.zvensmoluya.vortexcue.library.LibraryCoordinatorState
import io.github.zvensmoluya.vortexcue.library.LibrarySourceIds
import io.github.zvensmoluya.vortexcue.library.TimedTextTrackCandidate
import io.github.zvensmoluya.vortexcue.library.mediaLibraryPermissionsToRequest
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
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleTimeline
import io.github.zvensmoluya.vortexcue.text.TextImportCoordinator
import io.github.zvensmoluya.vortexcue.text.TimelineRevisionReader
import io.github.zvensmoluya.vortexcue.synctext.GeneratedTextPresentation
import io.github.zvensmoluya.vortexcue.ui.player.PlayerScreen
import io.github.zvensmoluya.vortexcue.ui.player.GlowCalibrationScreen
import io.github.zvensmoluya.vortexcue.ui.player.rememberPlaybackSnapshot
import io.github.zvensmoluya.vortexcue.ui.main.MainTab
import io.github.zvensmoluya.vortexcue.ui.main.MainTabScreen
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextAction
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextUiHost
import java.io.FileNotFoundException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AppDestination {
    Main,
    Player,
}

internal data class AppRouteSnapshot(
    val destination: AppDestination,
    val mainTab: MainTab,
    val playerSurface: PlayerSurface,
)

internal fun resolveBackRoute(
    destination: AppDestination,
    mainTab: MainTab,
    playerOriginTab: MainTab,
    playerSurface: PlayerSurface,
): AppRouteSnapshot =
    when {
        destination == AppDestination.Player &&
            playerSurface == PlayerSurface.Calibration &&
            playerOriginTab == MainTab.Settings ->
            AppRouteSnapshot(AppDestination.Main, playerOriginTab, PlayerSurface.Normal)
        destination == AppDestination.Player && playerSurface != PlayerSurface.Normal ->
            AppRouteSnapshot(AppDestination.Player, mainTab, PlayerSurface.Normal)
        destination == AppDestination.Player ->
            AppRouteSnapshot(AppDestination.Main, playerOriginTab, PlayerSurface.Normal)
        mainTab != MainTab.Content ->
            AppRouteSnapshot(AppDestination.Main, MainTab.Content, PlayerSurface.Normal)
        else -> AppRouteSnapshot(destination, mainTab, playerSurface)
    }

internal enum class MediaPickTarget {
    Import,
    External,
    Relink,
}

internal val MediaPickTarget.collectsIntoContent: Boolean
    get() = this == MediaPickTarget.Import

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
    val timelineRevisionReader = container.timelineRevisionReader
    val textImportCoordinator = container.textImportCoordinator
    val contentResolver = context.contentResolver
    val gateway = remember(player) { PlaybackGateway(player) }
    val coordinator =
        remember(context, scope, repository) {
            LibraryCoordinator(context, scope, repository)
        }

    val sourceSummaries by repository.sources.collectAsState(initial = emptyList())
    val recovery by playbackRepository.recovery.collectAsState(initial = PlaybackRecoveryProjection())
    val persistedSettings by settingsStore.state.collectAsState(initial = SettingsState())
    val playback = rememberPlaybackSnapshot(player)
    val embeddedTextRuntime = rememberEmbeddedTextRuntimeState(player)
    val syncTextUiState by container.syncTextUiController.state.collectAsState()
    val dispatchSyncTextAction = container.syncTextUiController::dispatch

    var destination by rememberSaveable { mutableStateOf(AppDestination.Main) }
    var mainTab by rememberSaveable { mutableStateOf(MainTab.Content) }
    var playerOriginTab by rememberSaveable { mutableStateOf(MainTab.Content) }
    var importHubRequest by rememberSaveable { mutableIntStateOf(0) }
    var playerSurface by rememberSaveable { mutableStateOf(PlayerSurface.Normal) }
    var mediaPickTarget by remember { mutableStateOf(MediaPickTarget.Import) }
    var relinkContentId by remember { mutableStateOf<String?>(null) }
    var subtitlePickTarget by remember { mutableStateOf<SubtitlePickTarget?>(null) }
    var folderRelinkSourceId by remember { mutableStateOf<String?>(null) }
    var materialization by remember { mutableStateOf(PlayerMaterializationState.NotLoaded) }
    var displayProfile by remember { mutableStateOf(DisplayProfile.Balanced) }
    var contentSort by remember { mutableStateOf(io.github.zvensmoluya.vortexcue.data.ContentSort.RecentAdded) }
    var glowCalibrationCompleted by remember { mutableStateOf(false) }
    var glowGestureHintSeen by remember { mutableStateOf(false) }
    var primarySubtitle by remember { mutableStateOf<LoadedSubtitle?>(null) }
    var secondarySubtitle by remember { mutableStateOf<LoadedSubtitle?>(null) }
    var sleepTimerRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(persistedSettings) {
        displayProfile = persistedSettings.displayProfile
        glowCalibrationCompleted = persistedSettings.glowCalibrationCompleted
        glowGestureHintSeen = persistedSettings.glowGestureHintSeen
        contentSort = persistedSettings.contentSort
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
    val automaticCandidate =
        currentDetails
            ?.takeIf {
                it.activeText.primaryMode == TextSelectionMode.Automatic &&
                    it.activeText.primaryTrackId == null
            }
            ?.discoveredTextCandidates
            ?.filter { it.failureMessage == null && it.matchBasis == TimedTextMatchBasis.ExactStem }
            ?.singleOrNull()

    LaunchedEffect(currentContentId, automaticCandidate?.candidateId) {
        val candidate = automaticCandidate ?: return@LaunchedEffect
        runCatchingSuspend {
            textImportCoordinator.importCandidate(
                candidateId = candidate.candidateId,
                slot = TextTrackSlot.Primary,
                selectionMode = TextSelectionMode.Automatic,
            )
        }
    }
    val generatedSlotId = selectedPrimaryTrack?.locator?.generatedSlotId
    val generatedPresentationFlow =
        remember(container.syncTextRepository, generatedSlotId) {
            generatedSlotId?.let(container.syncTextRepository::observePresentation) ?: flowOf(null)
        }
    val generatedPresentation by generatedPresentationFlow.collectAsState(initial = null)
    var effectiveGeneratedPresentation by remember(currentContentId, generatedSlotId) {
        mutableStateOf<GeneratedTextPresentation?>(null)
    }
    var deferredGeneratedResultId by remember(currentContentId, generatedSlotId) {
        mutableStateOf<String?>(null)
    }
    var deferredAtCueKey by remember(currentContentId, generatedSlotId) {
        mutableStateOf<String?>(null)
    }
    val loadedGeneratedText =
        remember(
            effectiveGeneratedPresentation?.result?.resultId,
            effectiveGeneratedPresentation?.result?.presentationMode,
        ) {
            effectiveGeneratedPresentation?.let { presentation ->
                LoadedGeneratedText(
                    resultId = presentation.result.resultId,
                    slotId = presentation.result.slotId,
                    sourceTimeline = SubtitleTimeline(presentation.sourceCues),
                    targetTimeline = presentation.targetCues?.let(::SubtitleTimeline),
                    presentationMode = presentation.result.presentationMode,
                    completeness = presentation.result.completeness,
                )
            }
        }
    val effectiveGeneratedBoundaryTimeline =
        loadedGeneratedText?.targetTimeline ?: loadedGeneratedText?.sourceTimeline
    val effectiveCueKey =
        effectiveGeneratedBoundaryTimeline
            ?.resolve((playback.positionMs + (currentDetails?.subtitleOffsetMs ?: 0L)).coerceAtLeast(0L))
            ?.let { cue -> "${cue.stableId}:${cue.startMs}:${cue.endMs}" }

    LaunchedEffect(
        generatedPresentation?.result?.resultId,
        generatedPresentation?.result?.presentationMode,
        playback.isPlaying,
        effectiveCueKey,
    ) {
        val incoming = generatedPresentation
        val current = effectiveGeneratedPresentation
        when {
            incoming == null -> {
                effectiveGeneratedPresentation = null
                deferredGeneratedResultId = null
                deferredAtCueKey = null
            }
            current == null ||
                current.result.resultId == incoming.result.resultId ||
                !playback.isPlaying ||
                effectiveCueKey == null -> {
                effectiveGeneratedPresentation = incoming
                deferredGeneratedResultId = null
                deferredAtCueKey = null
            }
            deferredGeneratedResultId != incoming.result.resultId -> {
                deferredGeneratedResultId = incoming.result.resultId
                deferredAtCueKey = effectiveCueKey
            }
            deferredAtCueKey != effectiveCueKey -> {
                effectiveGeneratedPresentation = incoming
                deferredGeneratedResultId = null
                deferredAtCueKey = null
            }
        }
    }
    LaunchedEffect(currentContentId, selectedPrimaryTrack?.stableId) {
        primarySubtitle =
            loadSelectedTrack(
                contentId = currentContentId,
                track = selectedPrimaryTrack,
                timelineRevisionReader = timelineRevisionReader,
                repository = repository,
            )
    }
    LaunchedEffect(currentContentId, selectedSecondaryTrack?.stableId) {
        secondarySubtitle =
            loadSelectedTrack(
                contentId = currentContentId,
                track = selectedSecondaryTrack,
                timelineRevisionReader = timelineRevisionReader,
                repository = repository,
            )
    }

    val activeSession =
        currentDetails?.let {
            ActiveSession(
                media = it.activeMedia,
                subtitle = primarySubtitle,
                secondarySubtitle = secondarySubtitle,
                generatedText = loadedGeneratedText,
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
        if (player.isPlaying) {
            player.pause()
        } else if (runtimeOwnsState) {
            startPlayback()
        } else if (recovery.queue.isNotEmpty()) {
            scope.launch {
                val result = gateway.materialize()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    materialization = PlayerMaterializationState.Materialized
                    startPlayback()
                }
            }
        }
    }

    suspend fun openContentNow(contentIds: List<String>, index: Int): Boolean {
        if (contentIds.isEmpty() || index !in contentIds.indices) return false
        val result = gateway.replace(contentIds, index, 0L)
        if (result.resultCode != SessionResult.RESULT_SUCCESS) return false
        repository.recordOpened(contentIds[index])
        materialization = PlayerMaterializationState.Materialized
        playerSurface = PlayerSurface.Normal
        playerOriginTab = mainTab
        destination = AppDestination.Player
        startPlayback()
        return true
    }

    fun openContent(contentIds: List<String>, index: Int) {
        playerOriginTab = mainTab
        scope.launch { openContentNow(contentIds, index) }
    }

    fun continuePlayback() {
        if (recovery.queue.isEmpty() && playback.queue.isEmpty()) return
        playerOriginTab = mainTab
        scope.launch {
            val result = gateway.materialize()
            if (result.resultCode != SessionResult.RESULT_SUCCESS) return@launch
            materialization = PlayerMaterializationState.Materialized
            // A cold launch always enters the readable Normal Player. Glow is an explicit action.
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
                    MediaPickTarget.Import,
                    MediaPickTarget.External,
                    -> {
                        val contentId = repository.resolveOrCreate(replacement)
                        if (target.collectsIntoContent) repository.collect(contentId)
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
            uri?.let {
                consumeMediaUri(
                    it,
                    mediaPickTarget,
                    playImmediately = mediaPickTarget == MediaPickTarget.Import,
                )
            }
        }
    val subtitlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = subtitlePickTarget
            subtitlePickTarget = null
            if (uri != null && target != null) {
                scope.launch {
                    runCatchingSuspend {
                        contentResolver.persistReadPermission(uri)
                        textImportCoordinator.importManual(
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
            coordinator.scanDevice(admitNew = true, audioOnly = true)
        }

    LaunchedEffect(externalMediaRequest?.id) {
        val request = externalMediaRequest ?: return@LaunchedEffect
        handleMediaUri(request.uri, MediaPickTarget.External, playImmediately = true)
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

    fun navigateBack() {
        val next = resolveBackRoute(destination, mainTab, playerOriginTab, playerSurface)
        destination = next.destination
        mainTab = next.mainTab
        playerSurface = next.playerSurface
    }

    BackHandler(enabled = destination == AppDestination.Player) {
        navigateBack()
    }

    val sleepTimer = rememberSleepTimerState(player, sleepTimerRevision)

    when (destination) {
        AppDestination.Main ->
            MainTabScreen(
                selectedTab = mainTab,
                onTabChanged = { mainTab = it },
                openImportHubRequest = importHubRequest,
                onImportHubRequestConsumed = { importHubRequest = 0 },
                repository = repository,
                sources = sourceSummaries,
                coordinator = coordinator.state,
                recovery = recovery,
                playback = playback,
                gateway = gateway,
                displayProfile = displayProfile,
                contentSort = contentSort,
                onContentSortChanged = { sort ->
                    contentSort = sort
                    scope.launch { settingsStore.saveContentSort(sort) }
                },
                onPickMedia = {
                    mediaPickTarget = MediaPickTarget.Import
                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                },
                onRequestLibraryAccess = {
                    val permissions = mediaLibraryPermissionsToRequest(context)
                    if (permissions.isEmpty()) coordinator.scanDevice(admitNew = true, audioOnly = true)
                    else libraryPermissionLauncher.launch(permissions)
                },
                onRefreshLibrary = { coordinator.scanAll() },
                onRefreshSource = coordinator::refreshSource,
                onRelinkSource = { sourceId ->
                    folderRelinkSourceId = sourceId
                    folderPicker.launch(null)
                },
                onCancelScan = coordinator::cancelScan,
                onAddFolder = {
                    folderRelinkSourceId = null
                    folderPicker.launch(null)
                },
                onRemoveSource = coordinator::removeSource,
                onPendingImportConsumed = coordinator::clearPendingImport,
                onPlaySingle = { id -> openContent(listOf(id), 0) },
                onQueueSelect = { index ->
                    if (index in recovery.queue.indices) {
                        playerOriginTab = MainTab.Queue
                        scope.launch {
                            val result = gateway.materialize()
                            if (result.resultCode != SessionResult.RESULT_SUCCESS) return@launch
                            materialization = PlayerMaterializationState.Materialized
                            player.seekToDefaultPosition(index)
                            recovery.queue.getOrNull(index)?.contentId?.let { repository.recordOpened(it) }
                            playerSurface = PlayerSurface.Normal
                            destination = AppDestination.Player
                            startPlayback()
                        }
                    }
                },
                onContinue = ::continuePlayback,
                onPlayPause = ::togglePlayback,
                onOpenSyncText = { summary ->
                    scope.launch {
                        repository.details(summary.contentId)?.let { details ->
                            dispatchSyncTextAction(SyncTextAction.OpenGeneration(details))
                        }
                    }
                },
                syncTextUiState = syncTextUiState,
                onSyncTextAction = dispatchSyncTextAction,
                onDisplayProfileChanged = { profile ->
                    displayProfile = profile
                    scope.launch { settingsStore.saveDisplayProfile(profile) }
                },
                onCalibrateGlow = {
                    playerOriginTab = MainTab.Settings
                    playerSurface = PlayerSurface.Calibration
                    destination = AppDestination.Player
                },
                modifier = modifier,
            )
        AppDestination.Player -> {
            val session = activeSession
            if (playerSurface == PlayerSurface.Calibration && playerOriginTab == MainTab.Settings) {
                GlowCalibrationScreen(
                    profile = displayProfile,
                    onProfileChanged = { profile ->
                        displayProfile = profile
                        scope.launch { settingsStore.saveDisplayProfile(profile) }
                    },
                    onComplete = {
                        glowCalibrationCompleted = true
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Main
                        scope.launch { settingsStore.saveGlowOnboarding(true, glowGestureHintSeen) }
                    },
                    onCancel = {
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Main
                    },
                    modifier = modifier,
                )
            } else if (session == null) {
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
                    },
                    onBackToLibrary = {
                        mainTab = playerOriginTab
                        destination = AppDestination.Main
                    },
                    onOpenSources = {
                        mainTab = MainTab.Content
                        importHubRequest += 1
                        destination = AppDestination.Main
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
                                repository.ignore(details.contentId)
                            } else {
                                repository.collect(details.contentId)
                            }
                        }
                    },
                    onOpenQueue = {
                        mainTab = MainTab.Queue
                        destination = AppDestination.Main
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
                            scope.launch {
                                val candidate =
                                    currentDetails?.discoveredTextCandidates
                                        ?.firstOrNull { it.candidateId == trackId }
                                if (candidate != null) {
                                    container.textImportCoordinator.importCandidate(
                                        candidateId = candidate.candidateId,
                                        slot = slot,
                                        selectionMode = TextSelectionMode.Manual,
                                    )
                                } else {
                                    repository.selectManualTextTrack(id, slot, trackId)
                                }
                            }
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
                    syncTextTask = syncTextUiState.tasks.firstOrNull { it.contentId == currentContentId },
                    onGenerateSyncText = {
                        currentDetails?.let { details ->
                            dispatchSyncTextAction(SyncTextAction.OpenGeneration(details))
                        }
                    },
                    onTranslateSyncText = {
                        currentContentId?.let { id ->
                            dispatchSyncTextAction(SyncTextAction.TranslateForContent(id))
                        }
                    },
                    onCancelSyncText = {
                        currentContentId?.let { id ->
                            dispatchSyncTextAction(SyncTextAction.CancelForContent(id))
                        }
                    },
                    onRetrySyncText = {
                        currentContentId?.let { id ->
                            dispatchSyncTextAction(SyncTextAction.RetryForContent(id))
                        }
                    },
                    onAllowMeteredSyncText = {
                        currentContentId?.let { id ->
                            dispatchSyncTextAction(SyncTextAction.AllowMeteredNetworkForContent(id))
                        }
                    },
                    onGeneratedPresentationModeChanged = { slotId, mode ->
                        dispatchSyncTextAction(SyncTextAction.SetPresentationMode(slotId, mode))
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
                    onStopAfterQueue = {
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

    BackHandler(enabled = destination == AppDestination.Main && mainTab != MainTab.Content) {
        navigateBack()
    }

    SyncTextUiHost(
        state = syncTextUiState,
        onAction = dispatchSyncTextAction,
    )
}

private suspend fun loadSelectedTrack(
    contentId: String?,
    track: TimedTextTrack?,
    timelineRevisionReader: TimelineRevisionReader,
    repository: io.github.zvensmoluya.vortexcue.data.ContentRepository,
): LoadedSubtitle? {
    val id = contentId ?: return null
    val revisionId = track?.locator?.revisionId ?: return null
    return try {
        val loaded = LoadedSubtitle(SubtitleTimeline(timelineRevisionReader.read(revisionId).toSubtitleCues()))
        repository.updateTextTrackReadiness(
            contentId = id,
            trackId = track.stableId,
            readiness = TimedTextReadiness.Ready,
        )
        loaded
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        repository.updateTextTrackReadiness(
            contentId = id,
            trackId = track.stableId,
            readiness = TimedTextReadiness.MissingArtifact,
            failureMessage = error.message ?: "内部同步文字资产不可用，请重新导入。",
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
