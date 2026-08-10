package io.github.zvensmoluya.vortexcue.app

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.core.storage.*
import io.github.zvensmoluya.vortexcue.library.*
import io.github.zvensmoluya.vortexcue.playback.*
import io.github.zvensmoluya.vortexcue.subtitle.*
import io.github.zvensmoluya.vortexcue.ui.home.*
import io.github.zvensmoluya.vortexcue.ui.player.*
import io.github.zvensmoluya.vortexcue.ui.theme.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppDestination {
    Home,
    Player,
}

private enum class MediaPickTarget {
    Draft,
    LastSession,
}

@Composable
fun VortexCueApp(
    player: MediaController,
    onWindowBrightnessChanged: (Float?) -> Unit,
    onImmersiveModeChanged: (Boolean) -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val playback = rememberPlaybackSnapshot(player)
    var sleepTimerRevision by remember { mutableIntStateOf(0) }
    val sleepTimer = rememberSleepTimerState(player, sleepTimerRevision)
    val sessionStore = remember(context) { SessionStore(context) }
    val libraryStateHolder =
        remember(context, coroutineScope) { LibraryStateHolder(context, coroutineScope) }
    val subtitleCache = remember(context) { SubtitleCache(context) }
    val initialSession = remember(player) { player.currentMediaItem?.toActiveSession() }

    var destination by
        rememberSaveable {
            mutableStateOf(
                if (initialSession == null) AppDestination.Home else AppDestination.Player,
            )
        }
    var homeSection by rememberSaveable { mutableStateOf(HomeSection.Library) }
    var playerSurface by rememberSaveable { mutableStateOf(PlayerSurface.Normal) }
    var activeSession by remember { mutableStateOf(initialSession) }
    var lastSession by remember { mutableStateOf<RestorableSession?>(null) }
    var draft by remember { mutableStateOf(initialSession?.toDraft() ?: SessionDraft()) }
    var displayProfile by remember { mutableStateOf(DisplayProfile.Balanced) }
    var glowCalibrationCompleted by remember { mutableStateOf(false) }
    var glowGestureHintSeen by remember { mutableStateOf(false) }
    var restorationCompleted by remember { mutableStateOf(false) }
    var mediaPickTarget by rememberSaveable { mutableStateOf(MediaPickTarget.Draft) }
    var mediaLoadJob by remember { mutableStateOf<Job?>(null) }
    var mediaRequestId by remember { mutableLongStateOf(0L) }
    var subtitleLoadJob by remember { mutableStateOf<Job?>(null) }
    var subtitleRequestId by remember { mutableLongStateOf(0L) }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var pendingAutoPlay by rememberSaveable { mutableStateOf(false) }
    var openImportOnHome by rememberSaveable { mutableStateOf(false) }
    val libraryState = libraryStateHolder.state
    fun startPlayer() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
        }
        player.play()
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (pendingAutoPlay) {
                pendingAutoPlay = false
                startPlayer()
            }
        }

    fun playWithNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationPermissionRequested &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            pendingAutoPlay = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPlayer()
        }
    }

    fun togglePlayback() {
        if (player.isPlaying) player.pause() else playWithNotificationPermission()
    }

    fun currentRestorableSession(session: ActiveSession): RestorableSession {
        val previous = lastSession?.takeIf { it.session.media.uri == session.media.uri }
        val queue =
            (0 until player.mediaItemCount)
                .mapNotNull { index ->
                    val item = player.getMediaItemAt(index)
                    val decoded = item.toMediaSourceRef() ?: return@mapNotNull null
                    libraryState.items.firstOrNull { it.uri == decoded.uri }
                        ?: previous?.queue?.firstOrNull { it.uri == decoded.uri }
                        ?: decoded
                }
                .ifEmpty { listOf(session.media) }
        return RestorableSession(
            session = session,
            positionMs =
                resolveRestorablePosition(
                    currentPositionMs = player.currentPosition,
                    currentDurationMs = playback.durationMs,
                    previousPositionMs = previous?.positionMs,
                ),
            durationMs =
                resolveRestorableDuration(
                    currentDurationMs = playback.durationMs,
                    previousDurationMs = previous?.durationMs,
                ),
            playbackSpeed = player.playbackParameters.speed,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    fun persistSession(session: ActiveSession) {
        val saved = currentRestorableSession(session)
        lastSession = saved
        coroutineScope.launch { sessionStore.saveLastSession(saved) }
    }

    fun restoreAssociatedSubtitle(media: MediaSourceRef) {
        val subtitleSource =
            libraryState.config.subtitleAssociations
                .firstOrNull { it.mediaUri == media.uri }
                ?.subtitle
                ?: return
        coroutineScope.launch {
            runCatching {
                loadSubtitleSource(contentResolver, subtitleCache, subtitleSource)
            }.onSuccess { loaded ->
                val current = activeSession
                if (current?.media?.uri == media.uri && current.subtitle == null) {
                    val updated = current.copy(subtitle = loaded)
                    activeSession = updated
                    draft = updated.toDraft()
                    libraryStateHolder.updateSubtitleAssociation(media.uri, loaded.source)
                    persistSession(updated)
                }
            }
        }
    }

    fun resumeSession(saved: RestorableSession) {
        val queue = saved.queue.ifEmpty { listOf(saved.session.media) }
        val index = saved.currentIndex.coerceIn(queue.indices)
        player.setMediaItems(queue.map { it.toMediaItem() }, index, saved.positionMs)
        player.prepare()
        player.setPlaybackSpeed(saved.playbackSpeed)
        player.shuffleModeEnabled = saved.shuffleEnabled
        player.repeatMode = saved.repeatMode
        activeSession = saved.session
        lastSession = saved.copy(mediaAvailability = MediaAvailability.Ready)
        draft = saved.session.toDraft()
        playerSurface = PlayerSurface.Normal
        destination = AppDestination.Player
        playWithNotificationPermission()
    }

    fun cancelMediaLoad() {
        mediaRequestId += 1L
        mediaLoadJob?.cancel()
        mediaLoadJob = null
    }

    fun loadMedia(
        uri: Uri,
        target: MediaPickTarget,
    ) {
        cancelMediaLoad()
        val source =
            MediaSourceRef(
                uri = uri,
                displayName = contentResolver.displayName(uri),
                mimeType = contentResolver.safeMimeType(uri),
                origin = MediaOrigin.OpenDocument,
                sourceId = LibrarySourceIds.LooseFiles,
            )
        val requestId = mediaRequestId
        val relinkedSession =
            if (target == MediaPickTarget.LastSession) {
                lastSession?.let { saved ->
                    saved.copy(
                        session = saved.session.copy(media = source),
                        mediaAvailability = MediaAvailability.Checking,
                    )
                }
            } else {
                null
            }

        when (target) {
            MediaPickTarget.Draft -> draft = draft.copy(media = DraftMedia.Checking(source))
            MediaPickTarget.LastSession -> relinkedSession?.let { lastSession = it }
        }

        mediaLoadJob =
            coroutineScope.launch {
                try {
                    ensureMediaReadable(contentResolver, source)
                    val readySource = libraryStateHolder.inspectMedia(source)
                    if (requestId != mediaRequestId) return@launch
                    libraryStateHolder.rememberManualMedia(readySource)
                    when (target) {
                        MediaPickTarget.Draft ->
                            draft = draft.copy(media = DraftMedia.Ready(readySource))
                        MediaPickTarget.LastSession ->
                            relinkedSession?.let { session ->
                                val ready =
                                    session.copy(
                                        session = session.session.copy(media = readySource),
                                        mediaAvailability = MediaAvailability.Ready,
                                    )
                                lastSession = ready
                                sessionStore.saveLastSession(ready)
                                resumeSession(ready)
                            }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (requestId != mediaRequestId) return@launch
                    val message = mediaAccessMessage(error)
                    when (target) {
                        MediaPickTarget.Draft ->
                            draft =
                                draft.copy(
                                    media = DraftMedia.Unavailable(source, message),
                                )
                        MediaPickTarget.LastSession ->
                            relinkedSession?.let { session ->
                                val unavailable =
                                    session.copy(
                                        mediaAvailability =
                                            MediaAvailability.Unavailable(message),
                                    )
                                lastSession = unavailable
                                sessionStore.saveLastSession(unavailable)
                            }
                    }
                }
            }
    }

    fun cancelSubtitleLoad() {
        subtitleRequestId += 1L
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
    }

    fun loadSubtitle(uri: Uri) {
        cancelSubtitleLoad()
        val source =
            SubtitleSourceRef(
                uri = uri,
                displayName = contentResolver.displayName(uri),
                mimeType = contentResolver.safeMimeType(uri),
            )
        val requestId = subtitleRequestId
        draft = draft.copy(subtitle = DraftSubtitle.Loading(source))
        subtitleLoadJob =
            coroutineScope.launch {
                try {
                    contentResolver.persistReadPermission(uri)
                    val loaded = loadSubtitleSource(contentResolver, subtitleCache, source)
                    if (requestId == subtitleRequestId) {
                        draft = draft.copy(subtitle = DraftSubtitle.Ready(loaded))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (requestId == subtitleRequestId) {
                        draft =
                            draft.copy(
                                subtitle =
                                    DraftSubtitle.Failed(
                                        source = source,
                                        message = error.message ?: "无法读取所选字幕",
                                    ),
                            )
                    }
                }
            }
    }

    LaunchedEffect(Unit) {
        val storedLibrary = libraryStateHolder.restoreConfig()
        val stored =
            runCatching { sessionStore.load() }
                .getOrElse {
                    StoredAppState(
                        draft = StoredDraft(null, null),
                        lastSession = null,
                        displayProfile = DisplayProfile.Balanced,
                    )
                }
        displayProfile = stored.displayProfile
        glowCalibrationCompleted = stored.glowCalibrationCompleted
        glowGestureHintSeen = stored.glowGestureHintSeen

        val storedLast = stored.lastSession
        if (storedLast != null) {
            val mediaError =
                runCatching {
                    ensureMediaReadable(contentResolver, storedLast.media)
                }.exceptionOrNull()
            val loadedSubtitle =
                storedLast.subtitle?.let { source ->
                    runCatching {
                        loadSubtitleSource(contentResolver, subtitleCache, source)
                    }.getOrNull()
                }
            val restored =
                RestorableSession(
                    session =
                        ActiveSession(
                            media = storedLast.media,
                            subtitle = loadedSubtitle,
                            subtitleOffsetMs = storedLast.subtitleOffsetMs,
                        ),
                    positionMs = storedLast.positionMs,
                    durationMs = storedLast.durationMs,
                    playbackSpeed = storedLast.playbackSpeed,
                    queue = storedLast.queue,
                    currentIndex =
                        storedLast.currentIndex.coerceIn(storedLast.queue.indices),
                    shuffleEnabled = storedLast.shuffleEnabled,
                    repeatMode = storedLast.repeatMode,
                    mediaAvailability =
                        mediaError?.let {
                            MediaAvailability.Unavailable(mediaAccessMessage(it))
                        } ?: MediaAvailability.Ready,
                )
            lastSession = restored
            if (initialSession?.media?.uri == storedLast.media.uri) {
                activeSession =
                    initialSession.copy(
                        subtitle = loadedSubtitle,
                        subtitleOffsetMs = storedLast.subtitleOffsetMs,
                    )
                player.setPlaybackSpeed(storedLast.playbackSpeed)
            }
        }

        val storedDraft = stored.draft
        if (storedDraft.media != null || storedDraft.subtitle != null) {
            val storedMedia = storedDraft.media
            val storedSubtitle = storedDraft.subtitle
            draft =
                SessionDraft(
                    media =
                        storedMedia?.let(DraftMedia::Checking)
                            ?: DraftMedia.None,
                    subtitle =
                        storedSubtitle?.let(DraftSubtitle::Loading)
                            ?: DraftSubtitle.None,
                )
            if (storedMedia != null) {
                draft =
                    try {
                        ensureMediaReadable(contentResolver, storedMedia)
                        draft.copy(media = DraftMedia.Ready(storedMedia))
                    } catch (error: Exception) {
                        draft.copy(
                            media =
                                DraftMedia.Unavailable(
                                    source = storedMedia,
                                    message = mediaAccessMessage(error),
                                ),
                        )
                    }
            }
            if (storedSubtitle != null) {
                draft =
                    try {
                        val loaded =
                            loadSubtitleSource(contentResolver, subtitleCache, storedSubtitle)
                        draft.copy(subtitle = DraftSubtitle.Ready(loaded))
                    } catch (error: Exception) {
                        draft.copy(
                            subtitle =
                                DraftSubtitle.Failed(
                                    source = storedSubtitle,
                                    message = error.message ?: "无法恢复保存的字幕",
                                ),
                        )
                    }
            }
        }
        restorationCompleted = true
        libraryStateHolder.scan(storedLibrary)
    }

    LaunchedEffect(restorationCompleted, draft) {
        if (restorationCompleted) sessionStore.saveDraft(draft)
    }
    LaunchedEffect(restorationCompleted, displayProfile) {
        if (restorationCompleted) {
            delay(300L)
            sessionStore.saveDisplayProfile(displayProfile)
        }
    }
    LaunchedEffect(restorationCompleted, activeSession) {
        if (!restorationCompleted) return@LaunchedEffect
        val session = activeSession ?: return@LaunchedEffect
        while (true) {
            delay(5_000L)
            persistSession(session)
        }
    }
    LaunchedEffect(restorationCompleted, playback.isPlaying) {
        if (restorationCompleted && !playback.isPlaying) {
            activeSession?.let(::persistSession)
        }
    }
    LaunchedEffect(restorationCompleted, playback.speed) {
        if (restorationCompleted) activeSession?.let(::persistSession)
    }

    DisposableEffect(player, restorationCompleted, activeSession) {
        val session = activeSession
        val listener =
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (
                        restorationCompleted &&
                            session != null &&
                            player.currentMediaItem?.localConfiguration?.uri == session.media.uri
                    ) {
                        persistSession(session)
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    val decoded = mediaItem?.toMediaSourceRef() ?: return
                    val media =
                        libraryState.items.firstOrNull { it.uri == decoded.uri }
                            ?: lastSession?.queue?.firstOrNull { it.uri == decoded.uri }
                            ?: decoded
                    val updated =
                        if (session?.media?.uri == media.uri) {
                            session.copy(media = media)
                        } else {
                            ActiveSession(media = media, subtitle = null)
                        }
                    activeSession = updated
                    draft = updated.toDraft()
                    if (restorationCompleted) persistSession(updated)
                    if (updated.subtitle == null) restoreAssociatedSubtitle(media)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner, restorationCompleted, activeSession, displayProfile) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && restorationCompleted) {
                    activeSession?.let(::persistSession)
                    coroutineScope.launch {
                        sessionStore.saveDisplayProfile(displayProfile)
                    }
                }
            }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val mediaPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadMedia(it, mediaPickTarget) }
        }
    val subtitlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::loadSubtitle)
        }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(libraryStateHolder::addFolder)
        }
    val libraryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            libraryStateHolder.scan()
        }

    val glowActive =
        destination == AppDestination.Player && playerSurface != PlayerSurface.Normal

    LaunchedEffect(glowActive, displayProfile) {
        onImmersiveModeChanged(glowActive)
        onWindowBrightnessChanged(
            if (glowActive) displayProfile.glowWindowBrightness else null,
        )
    }
    LaunchedEffect(destination, playback.isPlaying) {
        onPlaybackActiveChanged(destination == AppDestination.Player && playback.isPlaying)
    }
    DisposableEffect(Unit) {
        onDispose {
            mediaLoadJob?.cancel()
            subtitleLoadJob?.cancel()
            libraryStateHolder.cancelScan()
            onPlaybackActiveChanged(false)
            onImmersiveModeChanged(false)
            onWindowBrightnessChanged(null)
        }
    }

    if (!restorationCompleted) {
        Box(
            modifier = modifier.fillMaxSize().background(AppBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "正在恢复会话…",
                color = AppSecondaryText,
                fontSize = 14.sp,
            )
        }
        return
    }

    BackHandler(enabled = destination == AppDestination.Player) {
        if (playerSurface != PlayerSurface.Normal) {
            playerSurface = PlayerSurface.Normal
        } else {
            homeSection = HomeSection.Library
            destination = AppDestination.Home
        }
    }
    BackHandler(
        enabled = destination == AppDestination.Home && homeSection != HomeSection.Library,
    ) {
        homeSection = HomeSection.Library
    }

    when (destination) {
        AppDestination.Home ->
            HomeScreen(
                section = homeSection,
                onSectionChanged = { homeSection = it },
                draft = draft,
                activeSession = activeSession,
                lastSession = lastSession,
                playback = playback,
                displayProfile = displayProfile,
                library = libraryState,
                openImportRequested = openImportOnHome,
                onImportRequestConsumed = { openImportOnHome = false },
                onPickMedia = {
                    mediaPickTarget = MediaPickTarget.Draft
                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                },
                onPickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
                onRequestLibraryAccess = {
                    val permissions = mediaLibraryPermissionsToRequest(context)
                    if (permissions.isEmpty()) {
                        libraryStateHolder.scan()
                    } else {
                        libraryPermissionLauncher.launch(permissions)
                    }
                },
                onRefreshLibrary = { libraryStateHolder.scan() },
                onAddFolder = { folderPicker.launch(null) },
                onRemoveFolder = libraryStateHolder::removeFolder,
                onClearLooseFiles = libraryStateHolder::clearLooseFiles,
                onAcceptCandidates = libraryStateHolder::acceptCandidates,
                onIgnoreCandidates = libraryStateHolder::ignoreCandidates,
                onRemoveIncluded = libraryStateHolder::removeIncluded,
                onRestoreIgnored = libraryStateHolder::restoreIgnored,
                onToggleHomeShortcut = libraryStateHolder::toggleHomeShortcut,
                onPlayLibraryItems = { items, index ->
                    items.getOrNull(index)?.let { media ->
                        player.setMediaItems(
                            items.map { it.toMediaItem() },
                            index,
                            0L,
                        )
                        player.prepare()
                        val session =
                            ActiveSession(
                                media = media,
                                subtitle =
                                    activeSession
                                        ?.takeIf { it.media.uri == media.uri }
                                        ?.subtitle,
                                subtitleOffsetMs =
                                    activeSession
                                        ?.takeIf { it.media.uri == media.uri }
                                        ?.subtitleOffsetMs
                                        ?: 0L,
                            )
                        activeSession = session
                        draft = session.toDraft()
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Player
                        playWithNotificationPermission()
                        persistSession(session)
                        if (session.subtitle == null) restoreAssociatedSubtitle(media)
                    }
                },
                onPlayNext = { media ->
                    val targetIndex =
                        if (player.mediaItemCount == 0) {
                            0
                        } else {
                            (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
                        }
                    player.addMediaItem(targetIndex, media.toMediaItem())
                },
                onAddToQueue = { media ->
                    player.addMediaItem(media.toMediaItem())
                },
                onCreatePlaylist = libraryStateHolder::createPlaylist,
                onAddToPlaylist = libraryStateHolder::addToPlaylist,
                onRemoveFromPlaylist = libraryStateHolder::removeFromPlaylist,
                onDeletePlaylist = libraryStateHolder::deletePlaylist,
                onRemoveSubtitle = {
                    cancelSubtitleLoad()
                    draft = draft.copy(subtitle = DraftSubtitle.None)
                },
                onClearDraft = {
                    cancelMediaLoad()
                    cancelSubtitleLoad()
                    draft = SessionDraft()
                },
                onApplyDraft = {
                    (draft.media as? DraftMedia.Ready)?.source?.let { media ->
                        val loadedSubtitle =
                            (draft.subtitle as? DraftSubtitle.Ready)?.subtitle
                        val mediaChanged =
                            player.currentMediaItem
                                ?.localConfiguration
                                ?.uri != media.uri
                        if (mediaChanged) {
                            player.setMediaItem(media.toMediaItem())
                        }
                        if (mediaChanged || player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                        }
                        if (!mediaChanged && player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(0L)
                        }
                        val preservedOffsetMs =
                            activeSession
                                ?.takeIf { it.media.uri == media.uri }
                                ?.subtitleOffsetMs
                                ?: 0L
                        val applied =
                            ActiveSession(
                                media = media,
                                subtitle = loadedSubtitle,
                                subtitleOffsetMs = preservedOffsetMs,
                            )
                        activeSession = applied
                        libraryStateHolder.updateSubtitleAssociation(
                            media.uri,
                            loadedSubtitle?.source,
                        )
                        draft = applied.toDraft()
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Player
                        playWithNotificationPermission()
                        persistSession(applied)
                    }
                },
                onContinue = {
                    val currentSession = activeSession
                    if (currentSession != null) {
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Player
                    } else {
                        lastSession?.let { saved ->
                            when (saved.mediaAvailability) {
                                MediaAvailability.Ready -> resumeSession(saved)
                                MediaAvailability.Checking -> Unit
                                is MediaAvailability.Unavailable -> {
                                    mediaPickTarget = MediaPickTarget.LastSession
                                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                                }
                            }
                        }
                    }
                },
                onPlayPause = ::togglePlayback,
                onDisplayProfileChanged = { displayProfile = it },
                modifier = modifier,
            )
        AppDestination.Player -> {
            val session = activeSession
            if (session == null) {
                destination = AppDestination.Home
            } else {
                PlayerScreen(
                    player = player,
                    session = session,
                    playback = playback,
                    surface = playerSurface,
                    displayProfile = displayProfile,
                    sleepTimer = sleepTimer,
                    showGestureHint = !glowGestureHintSeen,
                    onPlayPause = ::togglePlayback,
                    onSurfaceChanged = { requested ->
                        playerSurface =
                            if (
                                requested == PlayerSurface.Glow &&
                                    !glowCalibrationCompleted
                            ) {
                                PlayerSurface.Calibration
                            } else {
                                requested
                            }
                    },
                    onDisplayProfileChanged = { displayProfile = it },
                    onCalibrationCompleted = {
                        glowCalibrationCompleted = true
                        playerSurface = PlayerSurface.Glow
                        coroutineScope.launch {
                            sessionStore.saveGlowOnboarding(
                                calibrationCompleted = true,
                                gestureHintSeen = glowGestureHintSeen,
                            )
                        }
                    },
                    onGestureHintSeen = {
                        if (!glowGestureHintSeen) {
                            glowGestureHintSeen = true
                            coroutineScope.launch {
                                sessionStore.saveGlowOnboarding(
                                    calibrationCompleted = glowCalibrationCompleted,
                                    gestureHintSeen = true,
                                )
                            }
                        }
                    },
                    onBackToLibrary = {
                        homeSection = HomeSection.Library
                        destination = AppDestination.Home
                    },
                    onOpenSources = {
                        homeSection = HomeSection.Manage
                        destination = AppDestination.Home
                    },
                    onAddSubtitle = {
                        draft = session.toDraft()
                        homeSection = HomeSection.Library
                        openImportOnHome = true
                        destination = AppDestination.Home
                        subtitlePicker.launch(arrayOf("*/*"))
                    },
                    onSubtitleOffsetChanged = { offsetMs ->
                        val updated = session.copy(subtitleOffsetMs = offsetMs)
                        activeSession = updated
                        persistSession(updated)
                    },
                    onSetSleepTimer = { durationMs ->
                        player.setSleepTimer(durationMs)
                        sleepTimerRevision += 1
                    },
                    onStopAfterCurrent = {
                        player.stopAfterCurrentItem()
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

private suspend fun loadSubtitleSource(
    contentResolver: ContentResolver,
    subtitleCache: SubtitleCache,
    source: SubtitleSourceRef,
): LoadedSubtitle =
    withContext(Dispatchers.IO) {
        val providerBytes = runCatching { contentResolver.readSubtitleBytes(source.uri) }
        val bytes =
            providerBytes.getOrElse { providerError ->
                subtitleCache.read(source.cachedFileName) ?: throw providerError
            }
        val parseName =
            if (providerBytes.isSuccess) source.displayName else source.cachedFileName ?: source.displayName
        val cues = SubtitleParser.parse(parseName, bytes)
        val cachedFileName =
            if (providerBytes.isSuccess) {
                runCatching { subtitleCache.store(source.uri, cues) }
                    .getOrNull()
                    ?: source.cachedFileName
            } else {
                source.cachedFileName
            }
        val cachedSource = source.copy(cachedFileName = cachedFileName)
        LoadedSubtitle(cachedSource, SubtitleTimeline(cues))
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

private fun mediaAccessMessage(error: Throwable): String =
    when (error) {
        is SecurityException -> "播放文件授权已失效，请重新定位。"
        is FileNotFoundException -> "播放文件已移动、删除或无法访问，请重新定位。"
        is IOException -> "无法读取播放文件，请重新定位后重试。"
        else -> "无法验证播放文件，请重新定位后重试。"
    }

private fun ContentResolver.safeMimeType(uri: Uri): String? =
    runCatching { getType(uri) }.getOrNull()

private fun MediaSourceRef.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setMimeType(mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setArtist(displayArtist)
                .setAlbumTitle(album)
                .setExtras(
                    Bundle().apply {
                        putString(MediaExtraDisplayName, displayName)
                        putString(MediaExtraFolder, folder)
                        putString(MediaExtraOrigin, origin.name)
                        putString(MediaExtraSourceId, sourceId)
                        putLong(MediaExtraDuration, durationMs)
                        putLong(MediaExtraDateAdded, dateAddedSeconds)
                        putString(MediaExtraKind, kind.name)
                        putLong(MediaExtraSize, sizeBytes)
                        putInt(MediaExtraVideoWidth, videoWidth)
                        putInt(MediaExtraVideoHeight, videoHeight)
                    },
                )
                .build(),
        )
        .build()

private fun MediaItem.toMediaSourceRef(): MediaSourceRef? {
    val uri = localConfiguration?.uri ?: return null
    val title = mediaMetadata.title?.toString()?.takeIf(String::isNotBlank)
    val extras = mediaMetadata.extras
    return MediaSourceRef(
        uri = uri,
        displayName =
            extras?.getString(MediaExtraDisplayName)
                ?: title
                ?: uri.lastPathSegment
                ?: "未命名媒体",
        mimeType = localConfiguration?.mimeType,
        title = title,
        artist = mediaMetadata.artist?.toString(),
        album = mediaMetadata.albumTitle?.toString(),
        durationMs = extras?.getLong(MediaExtraDuration) ?: 0L,
        folder = extras?.getString(MediaExtraFolder),
        dateAddedSeconds = extras?.getLong(MediaExtraDateAdded) ?: 0L,
        origin =
            runCatching {
                MediaOrigin.valueOf(extras?.getString(MediaExtraOrigin).orEmpty())
            }.getOrDefault(MediaOrigin.OpenDocument),
        sourceId = extras?.getString(MediaExtraSourceId),
        kind =
            runCatching { MediaKind.valueOf(extras?.getString(MediaExtraKind).orEmpty()) }
                .getOrDefault(
                    MediaKind.infer(
                        localConfiguration?.mimeType,
                        extras?.getString(MediaExtraDisplayName) ?: title.orEmpty(),
                    ),
                ),
        sizeBytes = extras?.getLong(MediaExtraSize) ?: 0L,
        videoWidth = extras?.getInt(MediaExtraVideoWidth) ?: 0,
        videoHeight = extras?.getInt(MediaExtraVideoHeight) ?: 0,
    )
}

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

private fun MediaItem.toActiveSession(): ActiveSession? {
    val source = toMediaSourceRef() ?: return null
    return ActiveSession(
        media = source,
        subtitle = null,
    )
}

private fun ActiveSession.toDraft(): SessionDraft =
    SessionDraft(
        media = DraftMedia.Ready(media),
        subtitle = subtitle?.let(DraftSubtitle::Ready) ?: DraftSubtitle.None,
    )

private const val MediaExtraDisplayName = "vortexcue.display_name"
private const val MediaExtraFolder = "vortexcue.folder"
private const val MediaExtraOrigin = "vortexcue.origin"
private const val MediaExtraSourceId = "vortexcue.source_id"
private const val MediaExtraDuration = "vortexcue.duration_ms"
private const val MediaExtraDateAdded = "vortexcue.date_added_seconds"
private const val MediaExtraKind = "vortexcue.kind"
private const val MediaExtraSize = "vortexcue.size_bytes"
private const val MediaExtraVideoWidth = "vortexcue.video_width"
private const val MediaExtraVideoHeight = "vortexcue.video_height"
