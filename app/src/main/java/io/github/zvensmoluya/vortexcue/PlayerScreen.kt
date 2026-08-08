package io.github.zvensmoluya.vortexcue

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val GlowTrackColor = Color(0xFF242424)
private val PlaybackSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
internal fun PlayerScreen(
    player: Player,
    session: ActiveSession,
    playback: PlaybackSnapshot,
    surface: PlayerSurface,
    displayProfile: DisplayProfile,
    onSurfaceChanged: (PlayerSurface) -> Unit,
    onEditSources: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cuePositionMs =
        (playback.positionMs + session.subtitleOffsetMs).coerceAtLeast(0L)
    val currentCue =
        if (
            playback.playbackState != Player.STATE_IDLE &&
                playback.playbackState != Player.STATE_ENDED &&
                playback.errorMessage == null
        ) {
            session.subtitle?.timeline?.resolve(cuePositionMs)
        } else {
            null
        }

    when (surface) {
        PlayerSurface.Normal ->
            NormalPlayer(
                player = player,
                session = session,
                playback = playback,
                cuePositionMs = cuePositionMs,
                currentCue = currentCue,
                onEditSources = onEditSources,
                onEnterGlow = { onSurfaceChanged(PlayerSurface.Glow) },
                onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                modifier = modifier,
            )
        PlayerSurface.Glow ->
            GlowPlayer(
                player = player,
                session = session,
                playback = playback,
                currentCue = currentCue,
                displayProfile = displayProfile,
                onExitGlow = { onSurfaceChanged(PlayerSurface.Normal) },
                onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                modifier = modifier,
            )
    }
}

@Composable
private fun NormalPlayer(
    player: Player,
    session: ActiveSession,
    playback: PlaybackSnapshot,
    cuePositionMs: Long,
    currentCue: SubtitleCue?,
    onEditSources: () -> Unit,
    onEnterGlow: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = subtitleContext(session.subtitle?.timeline, cuePositionMs, currentCue)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(WorkBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onEditSources) {
                Text("首页", color = WorkSecondaryText)
            }
            Text(
                text = session.media.displayName,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = WorkPrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onEditSources) {
                Text("来源", color = WorkAccent)
            }
        }

        if (playback.playbackState == Player.STATE_BUFFERING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = WorkAccent,
                trackColor = WorkSurfaceRaised,
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (session.subtitle == null) {
                Text(
                    text = "当前会话没有字幕",
                    color = WorkSecondaryText,
                    fontSize = 15.sp,
                )
                TextButton(onClick = onEditSources) {
                    Text("添加字幕源", color = WorkAccent)
                }
            } else {
                CueText(
                    text = context.previous?.text.orEmpty(),
                    color = WorkMutedText,
                    fontSize = 15,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(24.dp))
                CueText(
                    text = currentCue?.text.orEmpty(),
                    color = WorkPrimaryText,
                    fontSize = 26,
                    maxLines = 6,
                )
                Spacer(modifier = Modifier.height(24.dp))
                CueText(
                    text = context.next?.text.orEmpty(),
                    color = WorkMutedText,
                    fontSize = 15,
                    maxLines = 3,
                )
            }
        }

        playback.errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = WorkError,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        PlaybackControls(
            player = player,
            playback = playback,
            subtitleOffsetMs = session.subtitleOffsetMs,
            subtitleControlsEnabled = session.subtitle != null,
            contentColor = WorkPrimaryText,
            secondaryColor = WorkSecondaryText,
            accentColor = WorkAccent,
            trackColor = WorkSurfaceRaised,
            onSubtitleOffsetChanged = onSubtitleOffsetChanged,
        )

        Button(
            onClick = onEnterGlow,
            enabled = session.subtitle != null,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = WorkAccent,
                    disabledContainerColor = WorkSurfaceRaised,
                ),
        ) {
            Text(
                text = if (session.subtitle == null) "添加字幕后进入微光" else "进入微光",
                color = if (session.subtitle == null) WorkMutedText else Color.Black,
            )
        }
    }
}

@Composable
private fun GlowPlayer(
    player: Player,
    session: ActiveSession,
    playback: PlaybackSnapshot,
    currentCue: SubtitleCue?,
    displayProfile: DisplayProfile,
    onExitGlow: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    val subtitleLevel = displayProfile.glowSubtitleLevel.coerceIn(0, 255)
    val controlLevel = displayProfile.glowControlLevel.coerceIn(0, 255)
    val subtitleColor = Color(subtitleLevel, subtitleLevel, subtitleLevel)
    val controlColor = Color(controlLevel, controlLevel, controlLevel)

    LaunchedEffect(controlsVisible, playback.isPlaying) {
        if (controlsVisible && playback.isPlaying) {
            delay(4_000L)
            controlsVisible = false
        }
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
        Text(
            text = currentCue?.text.orEmpty(),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .offset(y = 48.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            color = subtitleColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 34.sp,
            textAlign = TextAlign.Center,
        )

        if (controlsVisible) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = session.media.displayName,
                        modifier = Modifier.weight(1f),
                        color = controlColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onExitGlow) {
                        Text("退出微光", color = controlColor)
                    }
                }
                PlaybackControls(
                    player = player,
                    playback = playback,
                    subtitleOffsetMs = session.subtitleOffsetMs,
                    subtitleControlsEnabled = true,
                    contentColor = controlColor,
                    secondaryColor = controlColor.copy(alpha = 0.72f),
                    accentColor = controlColor,
                    trackColor = GlowTrackColor,
                    onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                )
            }
        }
    }
}

@Composable
private fun CueText(
    text: String,
    color: Color,
    fontSize: Int,
    maxLines: Int,
) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = color,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.4f).sp,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PlaybackControls(
    player: Player,
    playback: PlaybackSnapshot,
    subtitleOffsetMs: Long,
    subtitleControlsEnabled: Boolean,
    contentColor: Color,
    secondaryColor: Color,
    accentColor: Color,
    trackColor: Color,
    onSubtitleOffsetChanged: (Long) -> Unit,
) {
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = accentColor,
            activeTrackColor = accentColor.copy(alpha = 0.75f),
            inactiveTrackColor = trackColor,
        )
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val displayedPositionMs = scrubPositionMs ?: playback.positionMs
    val progress =
        if (playback.durationMs > 0L) {
            (displayedPositionMs.toDouble() / playback.durationMs.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        } else {
            0f
        }

    Slider(
        value = progress,
        onValueChange = { value ->
            scrubPositionMs = (playback.durationMs * value).toLong()
        },
        onValueChangeFinished = {
            scrubPositionMs?.let(player::seekTo)
            scrubPositionMs = null
        },
        enabled = playback.durationMs > 0L,
        colors = sliderColors,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(displayedPositionMs), color = secondaryColor, fontSize = 11.sp)
        Text(formatTime(playback.durationMs), color = secondaryColor, fontSize = 11.sp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { player.seekWithinMedia(-10_000L) }) {
            Text("−10 秒", color = contentColor)
        }
        TextButton(onClick = player::togglePlayPause) {
            Text(playPauseLabel(playback), color = contentColor, fontWeight = FontWeight.Medium)
        }
        TextButton(onClick = { player.seekWithinMedia(10_000L) }) {
            Text("+10 秒", color = contentColor)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                onSubtitleOffsetChanged((subtitleOffsetMs - 500L).coerceAtLeast(-60_000L))
            },
            enabled = subtitleControlsEnabled,
        ) {
            Text("字幕 −0.5 秒", color = if (subtitleControlsEnabled) contentColor else trackColor)
        }
        TextButton(onClick = { player.setPlaybackSpeed(nextPlaybackSpeed(playback.speed)) }) {
            Text(formatSpeed(playback.speed), color = contentColor)
        }
        TextButton(
            onClick = {
                onSubtitleOffsetChanged((subtitleOffsetMs + 500L).coerceAtMost(60_000L))
            },
            enabled = subtitleControlsEnabled,
        ) {
            Text("+0.5 秒", color = if (subtitleControlsEnabled) contentColor else trackColor)
        }
    }
    if (subtitleControlsEnabled) {
        Text(
            text = formatOffset(subtitleOffsetMs),
            modifier = Modifier.fillMaxWidth(),
            color = secondaryColor,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

internal data class PlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val errorMessage: String? = null,
    val speed: Float = 1f,
)

@Composable
internal fun rememberPlaybackSnapshot(player: Player): PlaybackSnapshot {
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
            delay(if (player.isPlaying) 100L else 300L)
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
        speed = playbackParameters.speed,
    )

internal fun Player.togglePlayPause() {
    if (isPlaying) {
        pause()
    } else {
        if (playbackState == Player.STATE_ENDED) seekTo(0L)
        if (playbackState == Player.STATE_IDLE && mediaItemCount > 0) prepare()
        play()
    }
}

private fun Player.seekWithinMedia(deltaMs: Long) {
    val maximum = duration.takeUnless { it == C.TIME_UNSET || it < 0L }
    val target = (currentPosition + deltaMs).coerceAtLeast(0L)
    seekTo(maximum?.let { target.coerceAtMost(it) } ?: target)
}

private data class SubtitleContext(
    val previous: SubtitleCue?,
    val next: SubtitleCue?,
)

private fun subtitleContext(
    timeline: SubtitleTimeline?,
    positionMs: Long,
    current: SubtitleCue?,
): SubtitleContext {
    val cues = timeline?.cues.orEmpty()
    if (cues.isEmpty()) return SubtitleContext(null, null)

    if (current != null) {
        val index = cues.indexOf(current)
        return SubtitleContext(
            previous = cues.getOrNull(index - 1),
            next = cues.getOrNull(index + 1),
        )
    }

    val nextIndex = cues.indexOfFirst { it.startMs > positionMs }.let { if (it < 0) cues.size else it }
    return SubtitleContext(
        previous = cues.getOrNull(nextIndex - 1),
        next = cues.getOrNull(nextIndex),
    )
}

internal fun formatTime(milliseconds: Long): String {
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
        "字幕同步"
    } else {
        String.format(Locale.ROOT, "字幕 %+.1f 秒", milliseconds / 1_000f)
    }

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) {
        "${speed.toInt()}.0×"
    } else {
        "${speed}×"
    }

private fun nextPlaybackSpeed(current: Float): Float {
    val index = PlaybackSpeeds.indexOfFirst { it > current + 0.01f }
    return if (index >= 0) PlaybackSpeeds[index] else PlaybackSpeeds.first()
}

private fun playPauseLabel(playback: PlaybackSnapshot): String =
    when {
        playback.playbackState == Player.STATE_BUFFERING -> "缓冲中"
        playback.playbackState == Player.STATE_ENDED -> "重新播放"
        playback.isPlaying -> "暂停"
        else -> "播放"
    }

@Composable
fun VortexCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = WorkBackground,
                surface = WorkSurface,
                primary = WorkAccent,
                onBackground = WorkPrimaryText,
                onSurface = WorkPrimaryText,
            ),
        content = content,
    )
}
