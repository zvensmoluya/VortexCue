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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
            val storedSubtitle = storedDraft.subtitle
            draft =
                SessionDraft(
                    media = storedDraft.media,
                    subtitle =
                        storedSubtitle?.let(DraftSubtitle::Loading)
                            ?: DraftSubtitle.None,
                )
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
        if (restorationCompleted) sessionStore.saveDisplayProfile(displayProfile)
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

    val mediaPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                contentResolver.persistReadPermission(it)
                draft =
                    draft.copy(
                        media =
                            MediaSourceRef(
                                uri = it,
                                displayName = contentResolver.displayName(it),
                                mimeType = contentResolver.safeMimeType(it),
                            ),
                    )
            }
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
                onPickMedia = { mediaPicker.launch(arrayOf("audio/*", "video/*")) },
                onPickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
                onRemoveSubtitle = {
                    cancelSubtitleLoad()
                    draft = draft.copy(subtitle = DraftSubtitle.None)
                },
                onClearDraft = {
                    cancelSubtitleLoad()
                    draft = SessionDraft()
                },
                onApplyDraft = {
                    draft.media?.let { media ->
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
                    if (activeSession == null) {
                        lastSession?.let { saved ->
                            player.setMediaItem(saved.session.media.toMediaItem())
                            player.prepare()
                            player.seekTo(saved.positionMs)
                            player.setPlaybackSpeed(saved.playbackSpeed)
                            activeSession = saved.session
                            draft = saved.session.toDraft()
                            player.play()
                        }
                    }
                    playerSurface = PlayerSurface.Normal
                    destination = AppDestination.Player
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
        val cachedFileName =
            if (providerBytes.isSuccess) {
                runCatching { subtitleCache.store(source.uri, bytes) }
                    .getOrNull()
                    ?: source.cachedFileName
            } else {
                source.cachedFileName
            }
        val cachedSource = source.copy(cachedFileName = cachedFileName)
        val cues = SubtitleParser.parse(cachedSource.displayName, bytes)
        LoadedSubtitle(cachedSource, SubtitleTimeline(cues))
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
        media = media,
        subtitle = subtitle?.let(DraftSubtitle::Ready) ?: DraftSubtitle.None,
    )
