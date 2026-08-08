package io.github.zvensmoluya.vortexcue

import android.content.ContentResolver
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
    player: Player,
    onWindowBrightnessChanged: (Float?) -> Unit,
    onImmersiveModeChanged: (Boolean) -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val playback = rememberPlaybackSnapshot(player)
    val sessionStore = remember(context) { SessionStore(context) }
    val subtitleCache = remember(context) { SubtitleCache(context) }
    val initialSession = remember(player) { player.currentMediaItem?.toActiveSession() }

    var destination by
        rememberSaveable {
            mutableStateOf(
                if (initialSession == null) AppDestination.Home else AppDestination.Player,
            )
        }
    var playerSurface by rememberSaveable { mutableStateOf(PlayerSurface.Normal) }
    var activeSession by remember { mutableStateOf(initialSession) }
    var lastSession by remember { mutableStateOf<RestorableSession?>(null) }
    var draft by remember { mutableStateOf(initialSession?.toDraft() ?: SessionDraft()) }
    var displayProfile by remember { mutableStateOf(DisplayProfile.Balanced) }
    var restorationCompleted by remember { mutableStateOf(false) }
    var mediaPickTarget by rememberSaveable { mutableStateOf(MediaPickTarget.Draft) }
    var mediaLoadJob by remember { mutableStateOf<Job?>(null) }
    var mediaRequestId by remember { mutableLongStateOf(0L) }
    var subtitleLoadJob by remember { mutableStateOf<Job?>(null) }
    var subtitleRequestId by remember { mutableLongStateOf(0L) }

    fun currentRestorableSession(session: ActiveSession): RestorableSession =
        RestorableSession(
            session = session,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = playback.durationMs,
            playbackSpeed = player.playbackParameters.speed,
        )

    fun persistSession(session: ActiveSession) {
        val saved = currentRestorableSession(session)
        lastSession = saved
        coroutineScope.launch { sessionStore.saveLastSession(saved) }
    }

    fun resumeSession(saved: RestorableSession) {
        player.setMediaItem(saved.session.media.toMediaItem())
        player.prepare()
        player.seekTo(saved.positionMs)
        player.setPlaybackSpeed(saved.playbackSpeed)
        activeSession = saved.session
        lastSession = saved.copy(mediaAvailability = MediaAvailability.Ready)
        draft = saved.session.toDraft()
        playerSurface = PlayerSurface.Normal
        destination = AppDestination.Player
        player.play()
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
                    if (requestId != mediaRequestId) return@launch
                    when (target) {
                        MediaPickTarget.Draft ->
                            draft = draft.copy(media = DraftMedia.Ready(source))
                        MediaPickTarget.LastSession ->
                            relinkedSession?.let { session ->
                                val ready =
                                    session.copy(mediaAvailability = MediaAvailability.Ready)
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

    val glowActive = destination == AppDestination.Player && playerSurface == PlayerSurface.Glow

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
            onPlaybackActiveChanged(false)
            onImmersiveModeChanged(false)
            onWindowBrightnessChanged(null)
        }
    }

    if (!restorationCompleted) {
        Box(
            modifier = modifier.fillMaxSize().background(WorkBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "正在恢复会话…",
                color = WorkSecondaryText,
                fontSize = 14.sp,
            )
        }
        return
    }

    BackHandler(enabled = destination == AppDestination.Player) {
        if (playerSurface == PlayerSurface.Glow) {
            playerSurface = PlayerSurface.Normal
        } else {
            destination = AppDestination.Home
        }
    }

    when (destination) {
        AppDestination.Home ->
            HomeScreen(
                draft = draft,
                activeSession = activeSession,
                lastSession = lastSession,
                playback = playback,
                displayProfile = displayProfile,
                onPickMedia = {
                    mediaPickTarget = MediaPickTarget.Draft
                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                },
                onPickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
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
                        draft = applied.toDraft()
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Player
                        player.play()
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
                onPlayPause = player::togglePlayPause,
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
                    onSurfaceChanged = { playerSurface = it },
                    onEditSources = { destination = AppDestination.Home },
                    onSubtitleOffsetChanged = { offsetMs ->
                        val updated = session.copy(subtitleOffsetMs = offsetMs)
                        activeSession = updated
                        persistSession(updated)
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
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(displayName)
                .build(),
        )
        .build()

private fun MediaItem.toActiveSession(): ActiveSession? {
    val uri = localConfiguration?.uri ?: return null
    val displayName =
        mediaMetadata.title
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment
            ?: "未命名媒体"
    return ActiveSession(
        media =
            MediaSourceRef(
                uri = uri,
                displayName = displayName,
                mimeType = localConfiguration?.mimeType,
            ),
        subtitle = null,
    )
}

private fun ActiveSession.toDraft(): SessionDraft =
    SessionDraft(
        media = DraftMedia.Ready(media),
        subtitle = subtitle?.let(DraftSubtitle::Ready) ?: DraftSubtitle.None,
    )
