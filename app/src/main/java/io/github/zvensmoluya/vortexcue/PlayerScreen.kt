package io.github.zvensmoluya.vortexcue

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ControlText = Color(0xFF666666)
private val SecondaryText = Color(0xFF454545)
private val TrackColor = Color(0xFF242424)

@Composable
fun VortexCuePlayer(
    player: ExoPlayer,
    onWindowBrightnessChanged: (Float) -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val playback = rememberPlaybackSnapshot(player)

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var subtitleGray by rememberSaveable { mutableFloatStateOf(12f) }
    var windowBrightness by rememberSaveable { mutableFloatStateOf(0.08f) }
    var subtitleOffsetMs by rememberSaveable { mutableLongStateOf(0L) }
    var mediaName by remember { mutableStateOf<String?>(null) }
    var subtitleName by remember { mutableStateOf<String?>(null) }
    var subtitleTimeline by remember { mutableStateOf(SubtitleTimeline.Empty) }
    var subtitleLoading by remember { mutableStateOf(false) }
    var subtitleError by remember { mutableStateOf<String?>(null) }
    var subtitleLoadJob by remember { mutableStateOf<Job?>(null) }
    var subtitleRequestId by remember { mutableLongStateOf(0L) }

    fun loadMedia(uri: Uri) {
        contentResolver.persistReadPermission(uri)
        mediaName = contentResolver.displayName(uri)
        subtitleRequestId += 1L
        subtitleLoadJob?.cancel()
        subtitleLoading = false
        subtitleName = null
        subtitleTimeline = SubtitleTimeline.Empty
        subtitleOffsetMs = 0L
        subtitleError = null
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun loadSubtitle(uri: Uri) {
        subtitleRequestId += 1L
        val requestId = subtitleRequestId
        subtitleLoadJob?.cancel()
        subtitleLoadJob =
            coroutineScope.launch {
                subtitleLoading = true
                subtitleError = null
                try {
                    val (name, timeline) =
                        withContext(Dispatchers.IO) {
                            contentResolver.persistReadPermission(uri)
                            val displayName = contentResolver.displayName(uri)
                            val cues =
                                SubtitleParser.parse(
                                    displayName,
                                    contentResolver.readSubtitleBytes(uri),
                                )
                            displayName to SubtitleTimeline(cues)
                        }
                    if (requestId == subtitleRequestId) {
                        subtitleName = name
                        subtitleTimeline = timeline
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (requestId == subtitleRequestId) {
                        subtitleName = null
                        subtitleTimeline = SubtitleTimeline.Empty
                        subtitleError = error.message ?: "无法读取所选字幕"
                    }
                } finally {
                    if (requestId == subtitleRequestId) subtitleLoading = false
                }
            }
    }

    val mediaPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::loadMedia)
        }
    val subtitlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::loadSubtitle)
        }

    LaunchedEffect(windowBrightness) {
        onWindowBrightnessChanged(windowBrightness)
    }
    LaunchedEffect(playback.isPlaying) {
        onPlaybackActiveChanged(playback.isPlaying)
    }
    DisposableEffect(Unit) {
        onDispose {
            subtitleLoadJob?.cancel()
            onPlaybackActiveChanged(false)
        }
    }

    BackHandler(enabled = !controlsVisible) {
        controlsVisible = true
    }

    val cuePositionMs = (playback.positionMs + subtitleOffsetMs).coerceAtLeast(0L)
    val currentCue =
        if (
            mediaName != null &&
                playback.playbackState != Player.STATE_IDLE &&
                playback.playbackState != Player.STATE_ENDED &&
                playback.errorMessage == null
        ) {
            subtitleTimeline.resolve(cuePositionMs)
        } else {
            null
        }
    val gray = subtitleGray.toInt().coerceIn(0, 255)
    val subtitleColor = Color(gray, gray, gray)
    val centerHint =
        when {
            !controlsVisible -> null
            subtitleLoading -> "正在读取字幕…"
            mediaName == null -> "选择本地音频与 LRC / SRT"
            subtitleName == null -> "选择 LRC 或 SRT 后开始测试"
            else -> null
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                Text(
                    text = currentCue?.text.orEmpty(),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .offset(y = if (controlsVisible) 16.dp else 48.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp),
                    color = subtitleColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center,
                )

                centerHint?.let { hint ->
                    Text(
                        text = hint,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp),
                        color = SecondaryText,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (controlsVisible) {
                PlayerControls(
                    mediaName = mediaName,
                    subtitleName = subtitleName,
                    subtitleLoading = subtitleLoading,
                    errorMessage = subtitleError ?: playback.errorMessage,
                    playback = playback,
                    subtitleOffsetMs = subtitleOffsetMs,
                    subtitleGray = subtitleGray,
                    windowBrightness = windowBrightness,
                    onPickMedia = { mediaPicker.launch(arrayOf("audio/*")) },
                    onPickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
                    onPlayPause = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                            player.play()
                        }
                    },
                    onSeekBy = { deltaMs -> player.seekWithinMedia(deltaMs) },
                    onSeekTo = player::seekTo,
                    onSubtitleOffsetChanged = { subtitleOffsetMs = it.coerceIn(-60_000L, 60_000L) },
                    onSubtitleGrayChanged = { subtitleGray = it },
                    onWindowBrightnessChanged = { windowBrightness = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    mediaName: String?,
    subtitleName: String?,
    subtitleLoading: Boolean,
    errorMessage: String?,
    playback: PlaybackSnapshot,
    subtitleOffsetMs: Long,
    subtitleGray: Float,
    windowBrightness: Float,
    onPickMedia: () -> Unit,
    onPickSubtitle: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onSubtitleGrayChanged: (Float) -> Unit,
    onWindowBrightnessChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = ControlText)
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = ControlText,
            activeTrackColor = Color(0xFF383838),
            inactiveTrackColor = TrackColor,
        )
    val hasMedia = mediaName != null
    val durationMs = playback.durationMs
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val displayedPositionMs = scrubPositionMs ?: playback.positionMs
    val progress =
        if (durationMs > 0L) {
            (displayedPositionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPickMedia, colors = buttonColors) {
                Text("选择音频")
            }
            TextButton(onClick = onPickSubtitle, colors = buttonColors, enabled = !subtitleLoading) {
                Text(if (subtitleLoading) "读取中…" else "选择字幕")
            }
        }
        Text(
            text = mediaName ?: "未选择音频",
            modifier = Modifier.fillMaxWidth(),
            color = SecondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitleName ?: "未选择 LRC / SRT",
            modifier = Modifier.fillMaxWidth(),
            color = SecondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF7A5555),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Slider(
            value = progress,
            onValueChange = { value -> scrubPositionMs = (durationMs * value).toLong() },
            onValueChangeFinished = {
                scrubPositionMs?.let(onSeekTo)
                scrubPositionMs = null
            },
            enabled = durationMs > 0L,
            colors = sliderColors,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(displayedPositionMs), color = SecondaryText, fontSize = 11.sp)
            Text(formatTime(durationMs), color = SecondaryText, fontSize = 11.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onSeekBy(-10_000L) }, enabled = hasMedia, colors = buttonColors) {
                Text("−10 秒")
            }
            TextButton(onClick = onPlayPause, enabled = hasMedia, colors = buttonColors) {
                Text(if (playback.isPlaying) "暂停" else "播放")
            }
            TextButton(onClick = { onSeekBy(10_000L) }, enabled = hasMedia, colors = buttonColors) {
                Text("+10 秒")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onSubtitleOffsetChanged(subtitleOffsetMs - 500L) },
                colors = buttonColors,
            ) {
                Text("字幕 −0.5 秒")
            }
            Text(
                text = formatOffset(subtitleOffsetMs),
                color = SecondaryText,
                fontSize = 11.sp,
            )
            TextButton(
                onClick = { onSubtitleOffsetChanged(subtitleOffsetMs + 500L) },
                colors = buttonColors,
            ) {
                Text("+0.5 秒")
            }
        }

        Text(
            text = "窗口亮度  ${(windowBrightness * 100).toInt()}%",
            color = SecondaryText,
            fontSize = 11.sp,
        )
        Slider(
            value = windowBrightness,
            onValueChange = onWindowBrightnessChanged,
            valueRange = 0.01f..0.25f,
            colors = sliderColors,
        )
        Text(
            text = "字幕灰阶  RGB ${subtitleGray.toInt()}",
            color = SecondaryText,
            fontSize = 11.sp,
        )
        Slider(
            value = subtitleGray,
            onValueChange = onSubtitleGrayChanged,
            valueRange = 2f..32f,
            colors = sliderColors,
        )
    }
}

private data class PlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val errorMessage: String? = null,
)

@Composable
private fun rememberPlaybackSnapshot(player: Player): PlaybackSnapshot {
    var snapshot by remember(player) { mutableStateOf(player.snapshot()) }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    snapshot = player.snapshot()
                }

                override fun onPlayerError(error: PlaybackException) {
                    snapshot = player.snapshot()
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (isActive) {
            snapshot = player.snapshot()
            delay(if (player.isPlaying) 50L else 250L)
        }
    }

    return snapshot
}

private fun Player.snapshot(): PlaybackSnapshot =
    PlaybackSnapshot(
        positionMs = currentPosition.coerceAtLeast(0L),
        durationMs = duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
        isPlaying = isPlaying,
        playbackState = playbackState,
        errorMessage = playerError?.let { "播放失败：${it.errorCodeName}" },
    )

private fun Player.seekWithinMedia(deltaMs: Long) {
    val maximum = duration.takeUnless { it == C.TIME_UNSET || it < 0L }
    val target = (currentPosition + deltaMs).coerceAtLeast(0L)
    seekTo(maximum?.let { target.coerceAtMost(it) } ?: target)
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun formatOffset(milliseconds: Long): String =
    if (milliseconds == 0L) {
        "同步"
    } else {
        String.format(Locale.ROOT, "%+.1f 秒", milliseconds / 1_000f)
    }

@Composable
fun VortexCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = Color.Black,
                surface = Color.Black,
                primary = ControlText,
                onBackground = ControlText,
                onSurface = ControlText,
            ),
        content = content,
    )
}
