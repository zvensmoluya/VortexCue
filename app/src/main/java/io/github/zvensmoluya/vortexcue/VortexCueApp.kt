package io.github.zvensmoluya.vortexcue

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val initialSession = remember(player) { player.currentMediaItem?.toActiveSession() }

    var destination by
        rememberSaveable {
            mutableStateOf(
                if (initialSession == null) AppDestination.Home else AppDestination.Player,
            )
        }
    var playerSurface by rememberSaveable { mutableStateOf(PlayerSurface.Normal) }
    var activeSession by remember { mutableStateOf(initialSession) }
    var draft by remember { mutableStateOf(initialSession?.toDraft() ?: SessionDraft()) }
    var displayProfile by remember { mutableStateOf(DisplayProfile.Balanced) }
    var subtitleLoadJob by remember { mutableStateOf<Job?>(null) }
    var subtitleRequestId by remember { mutableLongStateOf(0L) }

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
                    val loaded =
                        withContext(Dispatchers.IO) {
                            contentResolver.persistReadPermission(uri)
                            val cues =
                                SubtitleParser.parse(
                                    source.displayName,
                                    contentResolver.readSubtitleBytes(uri),
                                )
                            LoadedSubtitle(source, SubtitleTimeline(cues))
                        }
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
                        activeSession =
                            ActiveSession(
                                media = media,
                                subtitle = loadedSubtitle,
                                subtitleOffsetMs = preservedOffsetMs,
                            )
                        draft = activeSession?.toDraft() ?: draft
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Player
                        player.play()
                    }
                },
                onContinue = {
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
                    },
                    modifier = modifier,
                )
            }
        }
    }
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
