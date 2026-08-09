package io.github.zvensmoluya.vortexcue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
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
    sleepTimer: SleepTimerState,
    showGestureHint: Boolean,
    onPlayPause: () -> Unit,
    onSurfaceChanged: (PlayerSurface) -> Unit,
    onEditSources: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    onCalibrationCompleted: () -> Unit,
    onGestureHintSeen: () -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onStopAfterCurrent: () -> Unit,
    onCancelSleepTimer: () -> Unit,
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
                sleepTimer = sleepTimer,
                onPlayPause = onPlayPause,
                onEditSources = onEditSources,
                onEnterGlow = { onSurfaceChanged(PlayerSurface.Glow) },
                onEnterCalibration = { onSurfaceChanged(PlayerSurface.Calibration) },
                onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                onSetSleepTimer = onSetSleepTimer,
                onStopAfterCurrent = onStopAfterCurrent,
                onCancelSleepTimer = onCancelSleepTimer,
                modifier = modifier,
            )
        PlayerSurface.Calibration ->
            GlowCalibration(
                profile = displayProfile,
                onProfileChanged = onDisplayProfileChanged,
                onComplete = onCalibrationCompleted,
                onCancel = { onSurfaceChanged(PlayerSurface.Normal) },
                modifier = modifier,
            )
        PlayerSurface.Glow ->
            GlowPlayer(
                player = player,
                session = session,
                playback = playback,
                currentCue = currentCue,
                displayProfile = displayProfile,
                sleepTimer = sleepTimer,
                showGestureHint = showGestureHint,
                onPlayPause = onPlayPause,
                onExitGlow = { onSurfaceChanged(PlayerSurface.Normal) },
                onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                onGestureHintSeen = onGestureHintSeen,
                modifier = modifier,
            )
    }
}

@Composable
private fun GlowCalibration(
    profile: DisplayProfile,
    onProfileChanged: (DisplayProfile) -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitleLevel = profile.glowSubtitleLevel.coerceIn(0, 255)
    val controlLevel = profile.glowControlLevel.coerceIn(0, 255)
    val subtitleColor = Color(subtitleLevel, subtitleLevel, subtitleLevel)
    val controlColor = Color(controlLevel, controlLevel, controlLevel)
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = controlColor,
            activeTrackColor = controlColor,
            inactiveTrackColor = GlowTrackColor,
        )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("第一次微光校准", color = controlColor, fontSize = 15.sp)
        Text(
            "把文字调到刚好能看清。这里的变化会直接作用于真实微光窗口。",
            color = controlColor.copy(alpha = 0.72f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Text(
                text = "声音继续，画面退场",
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                color = subtitleColor,
                fontSize = profile.glowSubtitleSizeSp.sp,
                lineHeight = (profile.glowSubtitleSizeSp * 1.4f).sp,
                textAlign = TextAlign.Center,
            )
        }

        CalibrationSlider(
            label = "屏幕",
            value = profile.glowWindowBrightness,
            valueRange = 0.01f..0.30f,
            colors = sliderColors,
            controlColor = controlColor,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowWindowBrightness = it,
                    ),
                )
            },
        )
        CalibrationSlider(
            label = "文字",
            value = profile.glowSubtitleLevel.toFloat(),
            valueRange = 4f..96f,
            colors = sliderColors,
            controlColor = controlColor,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowSubtitleLevel = it.toInt(),
                    ),
                )
            },
        )
        CalibrationSlider(
            label = "临时控制",
            value = profile.glowControlLevel.toFloat(),
            valueRange = 24f..160f,
            colors = sliderColors,
            controlColor = controlColor,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowControlLevel = it.toInt(),
                    ),
                )
            },
        )

        OutlinedButton(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, controlColor),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = controlColor,
                ),
        ) {
            Text("完成并进入微光")
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("返回播放器", color = controlColor.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: androidx.compose.material3.SliderColors,
    controlColor: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = controlColor, fontSize = 11.sp)
            Text("更暗  ·  更清晰", color = controlColor.copy(alpha = 0.60f), fontSize = 10.sp)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = colors,
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
    sleepTimer: SleepTimerState,
    onPlayPause: () -> Unit,
    onEditSources: () -> Unit,
    onEnterGlow: () -> Unit,
    onEnterCalibration: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onStopAfterCurrent: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = subtitleContext(session.subtitle?.timeline, cuePositionMs, currentCue)
    var queueVisible by rememberSaveable { mutableStateOf(false) }
    var timerVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppBackground)
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
                Text("首页", color = AppSecondaryText)
            }
            Text(
                text = session.media.displayTitle,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = AppPrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onEditSources) {
                Text("来源", color = AppSecondaryText)
            }
        }

        if (playback.playbackState == Player.STATE_BUFFERING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AppAccent,
                trackColor = AppSurfaceRaised,
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
                    color = AppSecondaryText,
                    fontSize = 15.sp,
                )
                Text(
                    text = "仍可进入纯黑微光",
                    color = AppMutedText,
                    fontSize = 12.sp,
                )
                TextButton(onClick = onEditSources) {
                    Text("添加字幕", color = AppSecondaryText)
                }
            } else {
                CueText(
                    text = context.previous?.text.orEmpty(),
                    color = AppMutedText,
                    fontSize = 15,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(24.dp))
                CueText(
                    text = currentCue?.text.orEmpty(),
                    color = AppPrimaryText,
                    fontSize = 26,
                    maxLines = 6,
                )
                Spacer(modifier = Modifier.height(24.dp))
                CueText(
                    text = context.next?.text.orEmpty(),
                    color = AppMutedText,
                    fontSize = 15,
                    maxLines = 3,
                )
            }
        }

        playback.errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = AppError,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        PlaybackControls(
            player = player,
            playback = playback,
            subtitleOffsetMs = session.subtitleOffsetMs,
            subtitleControlsEnabled = session.subtitle != null,
            contentColor = AppPrimaryText,
            secondaryColor = AppSecondaryText,
            accentColor = AppAccent,
            trackColor = AppSurfaceRaised,
            onPlayPause = onPlayPause,
            onSubtitleOffsetChanged = onSubtitleOffsetChanged,
        )

        PlayerOptions(
            player = player,
            playback = playback,
            sleepTimer = sleepTimer,
            onShowQueue = { queueVisible = true },
            onShowTimer = { timerVisible = true },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onEnterGlow,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, AppAccent),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = AppAccent,
                    ),
            ) {
                Text(
                    text = if (session.subtitle == null) "无字幕 · 进入微光" else "进入微光",
                    color = AppAccent,
                )
            }
            TextButton(onClick = onEnterCalibration) {
                Text("校准", color = AppMutedText)
            }
        }
    }

    if (queueVisible) {
        QueueDialog(
            player = player,
            playback = playback,
            onDismiss = { queueVisible = false },
        )
    }
    if (timerVisible) {
        SleepTimerDialog(
            state = sleepTimer,
            onSetDuration = {
                onSetSleepTimer(it)
                timerVisible = false
            },
            onEndOfItem = {
                onStopAfterCurrent()
                timerVisible = false
            },
            onCancelTimer = {
                onCancelSleepTimer()
                timerVisible = false
            },
            onDismiss = { timerVisible = false },
        )
    }
}

@Composable
private fun GlowPlayer(
    player: Player,
    session: ActiveSession,
    playback: PlaybackSnapshot,
    currentCue: SubtitleCue?,
    displayProfile: DisplayProfile,
    sleepTimer: SleepTimerState,
    showGestureHint: Boolean,
    onPlayPause: () -> Unit,
    onExitGlow: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onGestureHintSeen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var controlInteractionRevision by rememberSaveable { mutableIntStateOf(0) }
    var gestureFeedback by remember { mutableStateOf<String?>(null) }
    var gestureFeedbackRevision by remember { mutableIntStateOf(0) }
    val subtitleLevel = displayProfile.glowSubtitleLevel.coerceIn(0, 255)
    val controlLevel = displayProfile.glowControlLevel.coerceIn(0, 255)
    val subtitleColor = Color(subtitleLevel, subtitleLevel, subtitleLevel)
    val controlColor = Color(controlLevel, controlLevel, controlLevel)

    fun showControls() {
        controlsVisible = true
        controlInteractionRevision += 1
        onGestureHintSeen()
    }

    fun showGestureFeedback(message: String) {
        gestureFeedback = message
        gestureFeedbackRevision += 1
    }

    LaunchedEffect(controlsVisible, controlInteractionRevision) {
        if (controlsVisible) {
            delay(5_000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(gestureFeedbackRevision) {
        if (gestureFeedback != null) {
            val revision = gestureFeedbackRevision
            delay(850L)
            if (revision == gestureFeedbackRevision) gestureFeedback = null
        }
    }
    LaunchedEffect(showGestureHint) {
        if (showGestureHint) {
            delay(6_500L)
            onGestureHintSeen()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics {
                    contentDescription =
                        if (controlsVisible) "微光临时控制已显示" else "微光已锁定，长按显示控制"
                    onClick(
                        label = if (controlsVisible) "锁定微光" else "显示微光控制",
                    ) {
                        if (controlsVisible) controlsVisible = false else showControls()
                        true
                    }
                }
                .pointerInput(controlsVisible) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) controlsVisible = false
                        },
                        onDoubleTap = { offset ->
                            when {
                                offset.x < size.width / 3f -> {
                                    player.seekWithinMedia(-10_000L)
                                    showGestureFeedback("−10 秒")
                                }
                                offset.x > size.width * 2f / 3f -> {
                                    player.seekWithinMedia(10_000L)
                                    showGestureFeedback("+10 秒")
                                }
                                else -> {
                                    onPlayPause()
                                    showGestureFeedback(if (playback.isPlaying) "暂停" else "播放")
                                }
                            }
                            onGestureHintSeen()
                        },
                        onLongPress = { showControls() },
                    )
                },
    ) {
        Text(
            text = currentCue?.text.orEmpty(),
            modifier =
                Modifier
                    .align(
                        BiasAlignment(
                            horizontalBias = 0f,
                            verticalBias =
                                displayProfile.glowSubtitleVerticalBias
                                    .coerceIn(-0.60f, 0.60f),
                        ),
                    )
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            color = subtitleColor,
            fontSize = displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f).sp,
            fontWeight = FontWeight.Medium,
            lineHeight =
                (displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 1.4f).sp,
            textAlign = TextAlign.Center,
        )

        AnimatedVisibility(
            visible = gestureFeedback != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = gestureFeedback.orEmpty(),
                color = controlColor,
                fontSize = 13.sp,
            )
        }

        AnimatedVisibility(
            visible = showGestureHint && !controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("左侧双击\n快退", color = controlColor, fontSize = 10.sp)
                    Text("中央双击\n播放 / 暂停", color = controlColor, fontSize = 10.sp, textAlign = TextAlign.Center)
                    Text("右侧双击\n快进", color = controlColor, fontSize = 10.sp, textAlign = TextAlign.End)
                }
                Text("长按任意位置显示临时控制", color = controlColor, fontSize = 10.sp)
            }
        }

        playback.errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                color = controlColor,
                fontSize = 11.sp,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier =
                    Modifier
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
                        text = session.media.displayTitle,
                        modifier = Modifier.weight(1f),
                        color = controlColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sleepTimer.isActive) {
                        Text(
                            text = sleepTimerLabel(sleepTimer),
                            color = controlColor,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(onClick = { controlsVisible = false }) {
                        Text("锁定", color = controlColor)
                    }
                    TextButton(onClick = onExitGlow) {
                        Text("退出微光", color = controlColor)
                    }
                }
                playback.errorMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth(),
                        color = controlColor,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                PlaybackControls(
                    player = player,
                    playback = playback,
                    subtitleOffsetMs = session.subtitleOffsetMs,
                    subtitleControlsEnabled = session.subtitle != null,
                    contentColor = controlColor,
                    secondaryColor = controlColor.copy(alpha = 0.72f),
                    accentColor = controlColor,
                    trackColor = GlowTrackColor,
                    onPlayPause = onPlayPause,
                    onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                    onInteraction = ::showControls,
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
    onPlayPause: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onInteraction: () -> Unit = {},
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
            onInteraction()
            scrubPositionMs = (playback.durationMs * value).toLong()
        },
        onValueChangeFinished = {
            onInteraction()
            scrubPositionMs?.let(player::seekTo)
            scrubPositionMs = null
        },
        enabled = playback.durationMs > 0L,
        modifier = Modifier.semantics { contentDescription = "播放进度" },
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
        TextButton(
            onClick = {
                onInteraction()
                player.seekToPreviousMediaItem()
            },
            enabled = playback.hasPrevious,
        ) {
            Text("上一项", color = if (playback.hasPrevious) contentColor else trackColor)
        }
        TextButton(
            onClick = {
                onInteraction()
                onPlayPause()
            },
        ) {
            Text(playPauseLabel(playback), color = contentColor, fontWeight = FontWeight.Medium)
        }
        TextButton(
            onClick = {
                onInteraction()
                player.seekToNextMediaItem()
            },
            enabled = playback.hasNext,
        ) {
            Text("下一项", color = if (playback.hasNext) contentColor else trackColor)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                onInteraction()
                player.seekWithinMedia(-10_000L)
            },
        ) {
            Text("−10 秒", color = contentColor)
        }
        TextButton(
            onClick = {
                onInteraction()
                player.setPlaybackSpeed(nextPlaybackSpeed(playback.speed))
            },
        ) {
            Text(formatSpeed(playback.speed), color = contentColor)
        }
        TextButton(
            onClick = {
                onInteraction()
                player.seekWithinMedia(10_000L)
            },
        ) {
            Text("+10 秒", color = contentColor)
        }
    }

    if (subtitleControlsEnabled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    onInteraction()
                    onSubtitleOffsetChanged((subtitleOffsetMs - 500L).coerceAtLeast(-60_000L))
                },
            ) {
                Text("字幕 −0.5 秒", color = contentColor)
            }
            Text(formatOffset(subtitleOffsetMs), color = secondaryColor, fontSize = 11.sp)
            TextButton(
                onClick = {
                    onInteraction()
                    onSubtitleOffsetChanged((subtitleOffsetMs + 500L).coerceAtMost(60_000L))
                },
            ) {
                Text("+0.5 秒", color = contentColor)
            }
        }
    }
}

@Composable
private fun PlayerOptions(
    player: Player,
    playback: PlaybackSnapshot,
    sleepTimer: SleepTimerState,
    onShowQueue: () -> Unit,
    onShowTimer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { player.shuffleModeEnabled = !playback.shuffleEnabled }) {
            Text(
                "随机",
                color = if (playback.shuffleEnabled) AppAccent else AppMutedText,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = { player.repeatMode = nextRepeatMode(playback.repeatMode) }) {
            Text(repeatModeLabel(playback.repeatMode), color = AppMutedText, fontSize = 12.sp)
        }
        TextButton(onClick = onShowQueue) {
            Text("队列 ${playback.queue.size}", color = AppMutedText, fontSize = 12.sp)
        }
        TextButton(onClick = onShowTimer) {
            Text(
                sleepTimerLabel(sleepTimer),
                color = if (sleepTimer.isActive) AppAccent else AppMutedText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun QueueDialog(
    player: Player,
    playback: PlaybackSnapshot,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放队列", color = AppPrimaryText) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                itemsIndexed(
                    items = playback.queue,
                    key = { index, item -> "queue-$index-${item.mediaId}" },
                ) { index, item ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                TextButton(
                                    onClick = {
                                        player.seekToDefaultPosition(index)
                                        player.play()
                                    },
                                ) {
                                    Column {
                                        Text(
                                            item.title,
                                            color =
                                                if (index == playback.currentIndex) {
                                                    AppAccent
                                                } else {
                                                    AppPrimaryText
                                                },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        item.artist?.let {
                                            Text(it, color = AppMutedText, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = { player.moveMediaItem(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Text("↑", color = if (index > 0) AppSecondaryText else AppMutedText)
                            }
                            TextButton(
                                onClick = { player.moveMediaItem(index, index + 1) },
                                enabled = index < playback.queue.lastIndex,
                            ) {
                                Text(
                                    "↓",
                                    color =
                                        if (index < playback.queue.lastIndex) {
                                            AppSecondaryText
                                        } else {
                                            AppMutedText
                                        },
                                )
                            }
                            TextButton(
                                onClick = { player.removeMediaItem(index) },
                                enabled = playback.queue.size > 1,
                            ) {
                                Text(
                                    "移除",
                                    color =
                                        if (playback.queue.size > 1) AppMutedText else AppHairline,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(AppHairline),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成", color = AppAccent) }
        },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun SleepTimerDialog(
    state: SleepTimerState,
    onSetDuration: (Long) -> Unit,
    onEndOfItem: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时", color = AppPrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (state.isActive) {
                    Text(
                        text = "当前：${sleepTimerLabel(state)}",
                        color = AppAccent,
                        fontSize = 13.sp,
                    )
                }
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { onSetDuration(minutes * 60_000L) }) {
                        Text("$minutes 分钟后停止", color = AppPrimaryText)
                    }
                }
                TextButton(onClick = onEndOfItem) {
                    Text("当前内容结束后停止", color = AppPrimaryText)
                }
                if (state.isActive) {
                    TextButton(onClick = onCancelTimer) {
                        Text("取消定时", color = AppError)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = AppSecondaryText) }
        },
        containerColor = AppSurfaceRaised,
    )
}

internal data class QueueItemSnapshot(
    val mediaId: String,
    val title: String,
    val artist: String?,
)

internal data class PlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val errorMessage: String? = null,
    val speed: Float = 1f,
    val currentIndex: Int = 0,
    val queue: List<QueueItemSnapshot> = emptyList(),
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
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
        currentIndex = currentMediaItemIndex.coerceAtLeast(0),
        queue =
            (0 until mediaItemCount).map { index ->
                val item = getMediaItemAt(index)
                QueueItemSnapshot(
                    mediaId = item.mediaId.ifBlank { item.localConfiguration?.uri.toString() },
                    title =
                        item.mediaMetadata.title
                            ?.toString()
                            ?.takeIf(String::isNotBlank)
                            ?: item.localConfiguration?.uri?.lastPathSegment
                            ?: "未命名媒体",
                    artist = item.mediaMetadata.artist?.toString(),
                )
            },
        hasPrevious = hasPreviousMediaItem(),
        hasNext = hasNextMediaItem(),
        shuffleEnabled = shuffleModeEnabled,
        repeatMode = repeatMode,
    )

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

private fun nextRepeatMode(current: Int): Int =
    when (current) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

private fun repeatModeLabel(mode: Int): String =
    when (mode) {
        Player.REPEAT_MODE_ALL -> "循环全部"
        Player.REPEAT_MODE_ONE -> "单曲循环"
        else -> "不循环"
    }

private fun sleepTimerLabel(state: SleepTimerState): String =
    when (state.mode) {
        SleepTimerMode.None -> "定时"
        SleepTimerMode.EndOfItem -> "本曲结束"
        SleepTimerMode.Duration -> {
            val minutes = ((state.remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
            "剩余 $minutes 分"
        }
    }

private fun playPauseLabel(playback: PlaybackSnapshot): String =
    when {
        playback.playbackState == Player.STATE_BUFFERING -> "缓冲中"
        playback.playbackState == Player.STATE_ENDED -> "重新播放"
        playback.isPlaying -> "暂停"
        else -> "播放"
    }
