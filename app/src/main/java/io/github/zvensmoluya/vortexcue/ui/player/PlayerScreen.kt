package io.github.zvensmoluya.vortexcue.ui.player

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.playback.*
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleTimeline
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.ui.queue.QueueItemRow
import io.github.zvensmoluya.vortexcue.ui.synctext.generationProductStatus
import io.github.zvensmoluya.vortexcue.ui.theme.*

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
    isCollected: Boolean,
    contentUnit: PlayableContentUnit?,
    embeddedCueTrackId: String?,
    embeddedCueText: String?,
    onPlayPause: () -> Unit,
    onSurfaceChanged: (PlayerSurface) -> Unit,
    onBackToLibrary: () -> Unit,
    onOpenSources: () -> Unit,
    onRelinkMedia: () -> Unit,
    onToggleCollected: () -> Unit,
    onOpenQueue: () -> Unit,
    onQueueSelect: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onSelectTextTrack: (TextTrackSlot, String?) -> Unit,
    onRestoreAutomaticTextTrack: (TextTrackSlot) -> Unit,
    onAddTextTrack: (TextTrackSlot) -> Unit,
    syncTextTask: GenerationTaskSummary?,
    onGenerateSyncText: () -> Unit,
    onCancelSyncText: () -> Unit,
    onRetrySyncText: () -> Unit,
    onGeneratedPresentationModeChanged: (String, TextPresentationMode) -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    onCalibrationCompleted: () -> Unit,
    onGestureHintSeen: () -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onStopAfterCurrent: () -> Unit,
    onStopAfterQueue: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var textTracksVisible by rememberSaveable { mutableStateOf(false) }
    var timerVisible by rememberSaveable { mutableStateOf(false) }
    val cuePositionMs =
        (playback.positionMs + session.subtitleOffsetMs).coerceAtLeast(0L)
    val generatedSourceCue = session.generatedText?.sourceTimeline?.resolve(cuePositionMs)
    val generatedTargetCue = session.generatedText?.targetTimeline?.resolve(cuePositionMs)
    val primaryTimeline =
        session.generatedText?.targetTimeline
            ?: session.generatedText?.sourceTimeline
            ?: session.subtitle?.timeline
    val currentCue =
        if (
            playback.playbackState != Player.STATE_IDLE &&
                playback.playbackState != Player.STATE_ENDED &&
                playback.errorMessage == null
        ) {
            generatedTargetCue ?: generatedSourceCue ?: session.subtitle?.timeline?.resolve(cuePositionMs)
        } else {
            null
        }
    val secondaryCue =
        if (
            playback.playbackState != Player.STATE_IDLE &&
                playback.playbackState != Player.STATE_ENDED &&
                playback.errorMessage == null
        ) {
            session.secondarySubtitle?.timeline?.resolve(cuePositionMs).takeIf { session.generatedText == null }
        } else {
            null
        }
    val selectedPrimaryTrack = contentUnit?.selectedTrack(TextTrackSlot.Primary)
    val selectedSecondaryTrack = contentUnit?.selectedTrack(TextTrackSlot.Secondary)
    val primaryEmbeddedTrack =
        selectedPrimaryTrack?.takeIf { it.origin == TimedTextOrigin.Embedded }
    val secondaryEmbeddedTrack =
        selectedSecondaryTrack?.takeIf { it.origin == TimedTextOrigin.Embedded && session.generatedText == null }
    val hasPrimaryText = session.generatedText != null || session.subtitle != null || primaryEmbeddedTrack != null
    val hasPairedSource =
        session.generatedText?.targetTimeline != null &&
            session.generatedText.presentationMode == TextPresentationMode.SourceTarget
    val hasSecondaryText = hasPairedSource || session.generatedText == null && (session.secondarySubtitle != null || secondaryEmbeddedTrack != null)
    val primaryText =
        if (session.generatedText != null) {
            generatedTargetCue?.text ?: generatedSourceCue?.text
        } else if (primaryEmbeddedTrack != null) {
            embeddedCueText.takeIf { embeddedCueTrackId == primaryEmbeddedTrack.stableId }
        } else {
            currentCue?.text
        }
    val secondaryText =
        if (hasPairedSource) {
            generatedSourceCue?.text
        } else if (secondaryEmbeddedTrack != null) {
            embeddedCueText.takeIf { embeddedCueTrackId == secondaryEmbeddedTrack.stableId }
        } else {
            secondaryCue?.text
        }

    when (surface) {
        PlayerSurface.Normal ->
            NormalPlayer(
                player = player,
                session = session,
                playback = playback,
                cuePositionMs = cuePositionMs,
                currentCue = currentCue,
                primaryTimeline = primaryTimeline,
                primaryText = primaryText,
                secondaryText = secondaryText,
                hasPrimaryText = hasPrimaryText,
                hasSecondaryText = hasSecondaryText,
                sleepTimer = sleepTimer,
                isCollected = isCollected,
                onPlayPause = onPlayPause,
                onBackToLibrary = onBackToLibrary,
                onOpenSources = onOpenSources,
                onRelinkMedia = onRelinkMedia,
                onToggleCollected = onToggleCollected,
                onOpenQueue = onOpenQueue,
                onQueueSelect = onQueueSelect,
                onQueueMove = onQueueMove,
                onQueueRemove = onQueueRemove,
                onManageTextTracks = { textTracksVisible = true },
                syncTextStatus = generationProductStatus(syncTextTask, session.generatedText != null),
                onEnterGlow = { onSurfaceChanged(PlayerSurface.Glow) },
                onEnterCalibration = { onSurfaceChanged(PlayerSurface.Calibration) },
                onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                onShowTimer = { timerVisible = true },
                modifier = modifier,
            )
        PlayerSurface.Calibration ->
            GlowCalibrationScreen(
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
                primaryText = primaryText,
                secondaryText = secondaryText,
                hasPrimaryText = hasPrimaryText,
                hasSecondaryText = hasSecondaryText,
                displayProfile = displayProfile,
                sleepTimer = sleepTimer,
                showGestureHint = showGestureHint,
                onPlayPause = onPlayPause,
                onExitGlow = { onSurfaceChanged(PlayerSurface.Normal) },
                onRelinkMedia = onRelinkMedia,
                onManageTextTracks = { textTracksVisible = true },
                onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                onGestureHintSeen = onGestureHintSeen,
                onShowTimer = { timerVisible = true },
                modifier = modifier,
            )
    }

    if (textTracksVisible) {
        TextTrackDialog(
            contentUnit = contentUnit,
            onDismiss = { textTracksVisible = false },
            onSelectTrack = onSelectTextTrack,
            onRestoreAutomatic = onRestoreAutomaticTextTrack,
            onAddTrack = { slot ->
                textTracksVisible = false
                onAddTextTrack(slot)
            },
            generatedText = session.generatedText,
            syncTextTask = syncTextTask,
            onGenerateSyncText = onGenerateSyncText,
            onCancelSyncText = onCancelSyncText,
            onRetrySyncText = onRetrySyncText,
            onPresentationModeChanged = onGeneratedPresentationModeChanged,
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
            showEndOfQueue = playback.queue.isNotEmpty(),
            onEndOfQueue = {
                onStopAfterQueue()
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
internal fun GlowCalibrationScreen(
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
    primaryTimeline: SubtitleTimeline?,
    primaryText: String?,
    secondaryText: String?,
    hasPrimaryText: Boolean,
    hasSecondaryText: Boolean,
    sleepTimer: SleepTimerState,
    isCollected: Boolean,
    onPlayPause: () -> Unit,
    onBackToLibrary: () -> Unit,
    onOpenSources: () -> Unit,
    onRelinkMedia: () -> Unit,
    onToggleCollected: () -> Unit,
    onOpenQueue: () -> Unit,
    onQueueSelect: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onManageTextTracks: () -> Unit,
    syncTextStatus: String?,
    onEnterGlow: () -> Unit,
    onEnterCalibration: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onShowTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = subtitleContext(primaryTimeline, cuePositionMs, currentCue)
    var queueVisible by rememberSaveable { mutableStateOf(false) }

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
            IconButton(onClick = onBackToLibrary) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = AppSecondaryText)
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
            IconButton(onClick = onOpenSources) {
                Icon(Icons.Outlined.Add, contentDescription = "添加内容", tint = AppSecondaryText)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggleCollected) {
                Text(
                    text = if (isCollected) "移出内容" else "加入内容",
                    color = if (isCollected) AppMutedText else AppSecondaryText,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onManageTextTracks) {
                Text(
                    text =
                        when {
                            hasPrimaryText && hasSecondaryText ->
                                "字幕 · 主 / 次"
                            hasPrimaryText || hasSecondaryText ->
                                "字幕 · 1 轨"
                            else -> "字幕"
                        },
                    color = AppSecondaryText,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onOpenQueue) {
                Text(
                    text = "播放列表",
                    color = AppMutedText,
                    fontSize = 12.sp,
                )
            }
        }

        if (syncTextStatus != null) {
            Text(
                "同步文字 · $syncTextStatus",
                color = if (syncTextStatus == "失败") AppError else AppAccent,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
            )
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
            if (!hasPrimaryText && !hasSecondaryText) {
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
                TextButton(onClick = onManageTextTracks) {
                    Text("添加或选择字幕", color = AppSecondaryText)
                }
            } else if (hasPrimaryText) {
                CueText(
                    text = context.previous?.text.orEmpty(),
                    color = AppMutedText,
                    fontSize = 15,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(24.dp))
                CueText(
                    text = primaryText.orEmpty(),
                    color = AppPrimaryText,
                    fontSize = 26,
                    maxLines = 6,
                )
                if (hasSecondaryText) {
                    Spacer(modifier = Modifier.height(10.dp))
                    CueText(
                        text = secondaryText.orEmpty(),
                        color = AppSecondaryText,
                        fontSize = 17,
                        maxLines = 4,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                CueText(
                    text = context.next?.text.orEmpty(),
                    color = AppMutedText,
                    fontSize = 15,
                    maxLines = 3,
                )
            } else {
                CueText(
                    text = secondaryText.orEmpty(),
                    color = AppPrimaryText,
                    fontSize = 24,
                    maxLines = 6,
                )
            }
        }

        playback.errorMessage?.let { message ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = message,
                    color = AppError,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRelinkMedia) {
                    Text("重新选择媒体", color = AppSecondaryText, fontSize = 11.sp)
                }
            }
        }

        PlaybackControls(
            player = player,
            playback = playback,
            subtitleOffsetMs = session.subtitleOffsetMs,
            subtitleControlsEnabled =
                session.generatedText != null || session.subtitle != null || session.secondarySubtitle != null,
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
            onShowTimer = onShowTimer,
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
                    text =
                        if (!hasPrimaryText && !hasSecondaryText) {
                            "无字幕 · 进入微光"
                        } else {
                            "进入微光"
                        },
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
            playback = playback,
            onSelect = onQueueSelect,
            onMove = onQueueMove,
            onRemove = onQueueRemove,
            onDismiss = { queueVisible = false },
        )
    }
}

@Composable
private fun TextTrackDialog(
    contentUnit: PlayableContentUnit?,
    onDismiss: () -> Unit,
    onSelectTrack: (TextTrackSlot, String?) -> Unit,
    onRestoreAutomatic: (TextTrackSlot) -> Unit,
    onAddTrack: (TextTrackSlot) -> Unit,
    generatedText: LoadedGeneratedText?,
    syncTextTask: GenerationTaskSummary?,
    onGenerateSyncText: () -> Unit,
    onCancelSyncText: () -> Unit,
    onRetrySyncText: () -> Unit,
    onPresentationModeChanged: (String, TextPresentationMode) -> Unit,
) {
    val tracks = contentUnit?.timedTextTracks.orEmpty()
    val selection = contentUnit?.activeText ?: ActiveTextSelection()
    val primary = tracks.firstOrNull { it.stableId == selection.primaryTrackId }
    val secondary = tracks.firstOrNull { it.stableId == selection.secondaryTrackId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同步文字", color = AppPrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val productStatus = generationProductStatus(syncTextTask, generatedText != null)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = onGenerateSyncText,
                        enabled = syncTextTask == null || syncTextTask.state == GenerationTaskState.Completed,
                    ) {
                        Text("生成", color = AppAccent, fontSize = 12.sp)
                    }
                    if (productStatus != null) {
                        val failed = productStatus == "失败"
                        Text(
                            productStatus,
                            color = if (failed) AppError else AppAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        if (productStatus != "完成") {
                            TextButton(onClick = if (failed) onRetrySyncText else onCancelSyncText) {
                                Text(if (failed) "重试" else "取消", color = AppSecondaryText, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (generatedText?.targetTimeline != null) {
                    Text("生成结果展示", color = AppMutedText, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                onPresentationModeChanged(generatedText.slotId, TextPresentationMode.TargetOnly)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "纯译文",
                                color = if (generatedText.presentationMode == TextPresentationMode.TargetOnly) AppAccent else AppSecondaryText,
                                fontSize = 11.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onPresentationModeChanged(generatedText.slotId, TextPresentationMode.SourceTarget)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "译文 + 原文",
                                color = if (generatedText.presentationMode == TextPresentationMode.SourceTarget) AppAccent else AppSecondaryText,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                TextSelectionSummary(
                    title = "主轨",
                    track = primary,
                    mode = selection.primaryMode,
                    slot = TextTrackSlot.Primary,
                    onClear = { onSelectTrack(TextTrackSlot.Primary, null) },
                    onRestoreAutomatic = onRestoreAutomatic,
                    onAddTrack = onAddTrack,
                )
                TextSelectionSummary(
                    title = "次轨",
                    track = secondary,
                    mode = selection.secondaryMode,
                    slot = TextTrackSlot.Secondary,
                    onClear = { onSelectTrack(TextTrackSlot.Secondary, null) },
                    onRestoreAutomatic = onRestoreAutomatic,
                    onAddTrack = onAddTrack,
                )
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppHairline))
                if (tracks.isEmpty()) {
                    Text(
                        "还没有已发现或手动添加的字幕轨。",
                        color = AppMutedText,
                        fontSize = 12.sp,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        itemsIndexed(
                            items = tracks,
                            key = { _, track -> track.stableId },
                        ) { index, track ->
                            TextTrackChoiceRow(
                                track = track,
                                isPrimary = track.stableId == selection.primaryTrackId,
                                isSecondary = track.stableId == selection.secondaryTrackId,
                                onSelectPrimary = {
                                    onSelectTrack(TextTrackSlot.Primary, track.stableId)
                                },
                                onSelectSecondary = {
                                    onSelectTrack(TextTrackSlot.Secondary, track.stableId)
                                },
                            )
                            if (index != tracks.lastIndex) {
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
private fun TextSelectionSummary(
    title: String,
    track: TimedTextTrack?,
    mode: TextSelectionMode,
    slot: TextTrackSlot,
    onClear: () -> Unit,
    onRestoreAutomatic: (TextTrackSlot) -> Unit,
    onAddTrack: (TextTrackSlot) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = AppMutedText, fontSize = 11.sp)
                Text(
                    track?.let(::textTrackLabel) ?: "关闭",
                    color = AppPrimaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (mode == TextSelectionMode.Automatic) "自动" else "手动",
                color = AppMutedText,
                fontSize = 10.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (mode == TextSelectionMode.Manual) {
                TextButton(onClick = { onRestoreAutomatic(slot) }) {
                    Text("恢复自动", color = AppSecondaryText, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onClear) {
                Text("关闭", color = AppMutedText, fontSize = 11.sp)
            }
            TextButton(onClick = { onAddTrack(slot) }) {
                Text("选择文件", color = AppSecondaryText, fontSize = 11.sp)
            }
        }
        track?.failureMessage?.let { message ->
            Text(message, color = AppError, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun TextTrackChoiceRow(
    track: TimedTextTrack,
    isPrimary: Boolean,
    isSecondary: Boolean,
    onSelectPrimary: () -> Unit,
    onSelectSecondary: () -> Unit,
) {
    val failed =
        track.readiness in
            setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            textTrackLabel(track),
            color = AppPrimaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            textTrackDetail(track),
            color = if (failed) AppError else AppMutedText,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onSelectPrimary, enabled = !isPrimary || failed) {
                Text(
                    if (isPrimary && failed) "重试主轨" else if (isPrimary) "当前主轨" else "设为主轨",
                    color = if (!isPrimary || failed) AppSecondaryText else AppMutedText,
                    fontSize = 11.sp,
                )
            }
            TextButton(
                onClick = onSelectSecondary,
                enabled = (!isSecondary || failed) && !isPrimary,
            ) {
                Text(
                    if (isSecondary && failed) "重试次轨" else if (isSecondary) "当前次轨" else "设为次轨",
                    color =
                        if ((!isSecondary || failed) && !isPrimary) {
                            AppSecondaryText
                        } else {
                            AppMutedText
                        },
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun textTrackLabel(track: TimedTextTrack): String =
    track.label?.takeIf(String::isNotBlank)
        ?: track.language?.takeIf(String::isNotBlank)
        ?: track.locator.displayName

private fun PlayableContentUnit.selectedTrack(slot: TextTrackSlot): TimedTextTrack? {
    val trackId =
        when (slot) {
            TextTrackSlot.Primary -> activeText.primaryTrackId
            TextTrackSlot.Secondary -> activeText.secondaryTrackId
        }
    return timedTextTracks.firstOrNull { it.stableId == trackId }
}

private fun textTrackDetail(track: TimedTextTrack): String =
    listOfNotNull(
        when (track.origin) {
            TimedTextOrigin.Embedded -> "内嵌"
            TimedTextOrigin.Sidecar -> "外挂"
            TimedTextOrigin.Lyrics -> "歌词"
            TimedTextOrigin.Generated -> "生成"
        },
        track.format?.uppercase(Locale.ROOT),
        "默认".takeIf { TimedTextRole.Default in track.roles },
        "强制".takeIf { TimedTextRole.Forced in track.roles },
        "SDH".takeIf { TimedTextRole.Sdh in track.roles },
        "CC".takeIf { TimedTextRole.ClosedCaptions in track.roles },
        when (track.readiness) {
            TimedTextReadiness.Unknown -> "待验证"
            TimedTextReadiness.Ready -> "可用"
            TimedTextReadiness.PrivateCopy -> "应用副本可用"
            TimedTextReadiness.Unreadable -> "文件不可读"
            TimedTextReadiness.ParseFailed -> "无法解析"
        },
    ).joinToString(" · ")

@Composable
private fun GlowPlayer(
    player: Player,
    session: ActiveSession,
    playback: PlaybackSnapshot,
    primaryText: String?,
    secondaryText: String?,
    hasPrimaryText: Boolean,
    hasSecondaryText: Boolean,
    displayProfile: DisplayProfile,
    sleepTimer: SleepTimerState,
    showGestureHint: Boolean,
    onPlayPause: () -> Unit,
    onExitGlow: () -> Unit,
    onRelinkMedia: () -> Unit,
    onManageTextTracks: () -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onGestureHintSeen: () -> Unit,
    onShowTimer: () -> Unit,
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
                }
                .safeDrawingPadding(),
    ) {
        Column(
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = primaryText.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
                color = subtitleColor,
                fontSize = displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f).sp,
                fontWeight = FontWeight.Medium,
                lineHeight =
                    (displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 1.4f).sp,
                textAlign = TextAlign.Center,
            )
            if (hasSecondaryText) {
                Text(
                    text = secondaryText.orEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    color = subtitleColor.copy(alpha = 0.68f),
                    fontSize =
                        (displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 0.68f).sp,
                    lineHeight =
                        (displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 0.95f).sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

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
                    IconButton(onClick = onShowTimer) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = "睡眠定时：${sleepTimerLabel(sleepTimer)}",
                            tint = controlColor,
                        )
                    }
                    IconButton(onClick = { controlsVisible = false }) {
                        Icon(Icons.Outlined.Lock, contentDescription = "隐藏控制", tint = controlColor)
                    }
                    IconButton(onClick = onManageTextTracks) {
                        Icon(Icons.Outlined.Subtitles, contentDescription = "同步文字", tint = controlColor)
                    }
                    IconButton(onClick = onExitGlow) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "退出微光", tint = controlColor)
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
                    TextButton(onClick = onRelinkMedia) {
                        Text("重新选择媒体", color = controlColor, fontSize = 11.sp)
                    }
                }
                PlaybackControls(
                    player = player,
                    playback = playback,
                    subtitleOffsetMs = session.subtitleOffsetMs,
                    subtitleControlsEnabled =
                        session.generatedText != null || session.subtitle != null || session.secondarySubtitle != null,
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
        IconButton(
            onClick = {
                onInteraction()
                player.seekToPreviousMediaItem()
            },
            enabled = playback.hasPrevious,
        ) {
            Icon(
                Icons.Outlined.SkipPrevious,
                contentDescription = "上一项",
                tint = if (playback.hasPrevious) contentColor else trackColor,
            )
        }
        IconButton(
            onClick = {
                onInteraction()
                onPlayPause()
            },
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                when {
                    playback.errorMessage != null -> Icons.Outlined.Refresh
                    playback.isPlaying -> Icons.Outlined.Pause
                    else -> Icons.Outlined.PlayArrow
                },
                contentDescription = playPauseLabel(playback),
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(
            onClick = {
                onInteraction()
                player.seekToNextMediaItem()
            },
            enabled = playback.hasNext,
        ) {
            Icon(
                Icons.Outlined.SkipNext,
                contentDescription = "下一项",
                tint = if (playback.hasNext) contentColor else trackColor,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                onInteraction()
                player.seekWithinMedia(-10_000L)
            },
        ) {
            Icon(Icons.Outlined.Replay10, contentDescription = "后退 10 秒", tint = contentColor)
        }
        TextButton(
            onClick = {
                onInteraction()
                player.setPlaybackSpeed(nextPlaybackSpeed(playback.speed))
            },
        ) {
            Text(formatSpeed(playback.speed), color = contentColor)
        }
        IconButton(
            onClick = {
                onInteraction()
                player.seekWithinMedia(10_000L)
            },
        ) {
            Icon(Icons.Outlined.Forward10, contentDescription = "前进 10 秒", tint = contentColor)
        }
    }

    if (subtitleControlsEnabled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    onInteraction()
                    onSubtitleOffsetChanged((subtitleOffsetMs - 500L).coerceAtLeast(-60_000L))
                },
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = "字幕偏移减少 0.5 秒", tint = contentColor)
            }
            Text("字幕 ${formatOffset(subtitleOffsetMs)}", color = secondaryColor, fontSize = 11.sp)
            IconButton(
                onClick = {
                    onInteraction()
                    onSubtitleOffsetChanged((subtitleOffsetMs + 500L).coerceAtMost(60_000L))
                },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "字幕偏移增加 0.5 秒", tint = contentColor)
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
        IconButton(onClick = { player.shuffleModeEnabled = !playback.shuffleEnabled }) {
            Icon(
                Icons.Outlined.Shuffle,
                contentDescription = if (playback.shuffleEnabled) "关闭随机播放" else "开启随机播放",
                tint = if (playback.shuffleEnabled) AppAccent else AppMutedText,
            )
        }
        IconButton(onClick = { player.repeatMode = nextRepeatMode(playback.repeatMode) }) {
            Icon(
                if (playback.repeatMode == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                contentDescription = repeatModeLabel(playback.repeatMode),
                tint = if (playback.repeatMode == Player.REPEAT_MODE_OFF) AppMutedText else AppAccent,
            )
        }
        IconButton(onClick = onShowQueue) {
            Icon(
                Icons.AutoMirrored.Outlined.QueueMusic,
                contentDescription = "播放列表，共 ${playback.queue.size} 项",
                tint = AppMutedText,
            )
        }
        IconButton(onClick = onShowTimer) {
            Icon(
                Icons.Outlined.Timer,
                contentDescription = "睡眠定时：${sleepTimerLabel(sleepTimer)}",
                tint = if (sleepTimer.isActive) AppAccent else AppMutedText,
            )
        }
    }
}

@Composable
private fun QueueDialog(
    playback: PlaybackSnapshot,
    onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放列表", color = AppPrimaryText) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                itemsIndexed(
                    items = playback.queue,
                    key = { index, item -> "queue-$index-${item.mediaId}" },
                ) { index, item ->
                    QueueItemRow(
                        itemKey = item.mediaId,
                        index = index,
                        itemCount = playback.queue.size,
                        title = item.title,
                        artist = item.artist,
                        isCurrent = index == playback.currentIndex,
                        onSelect = { onSelect(index) },
                        onMove = onMove,
                        onRemove = { onRemove(index) },
                    )
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
    showEndOfQueue: Boolean,
    onEndOfQueue: () -> Unit,
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
                if (showEndOfQueue) {
                    TextButton(onClick = onEndOfQueue) {
                    Text("播放列表结束后停止", color = AppPrimaryText)
                    }
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
                    mediaId = item.mediaId,
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
        SleepTimerMode.EndOfTonight -> "播放列表结束"
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
