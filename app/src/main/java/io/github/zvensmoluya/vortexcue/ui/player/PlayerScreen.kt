package io.github.zvensmoluya.vortexcue.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.playback.*
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleTimeline
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.GeneratedResultCompleteness
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.GenerationNetworkWaitReason
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitState
import io.github.zvensmoluya.vortexcue.ui.queue.QueueItemRow
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextLaunchUiState
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextProgressPresentation
import io.github.zvensmoluya.vortexcue.ui.synctext.generationProductStatus
import io.github.zvensmoluya.vortexcue.ui.synctext.syncTextProgressPresentation
import io.github.zvensmoluya.vortexcue.ui.theme.*

private val GlowTrackColor = Color(0xFF242424)
private val PlaybackSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val MinGlowSubtitleLevel = 4
private const val MaxGlowSubtitleLevel = 96
private const val GlowBrightnessGestureStartFraction = 2f / 3f

@Composable
internal fun PlayerScreen(
    player: Player,
    session: ActiveSession,
    playback: PlaybackSnapshot,
    surface: PlayerSurface,
    displayProfile: DisplayProfile,
    isGlowLandscape: Boolean,
    sleepTimer: SleepTimerState,
    showGestureHint: Boolean,
    isCollected: Boolean,
    contentUnit: PlayableContentUnit?,
    embeddedCueTrackId: String?,
    embeddedCueText: String?,
    onPlayPause: () -> Unit,
    onSurfaceChanged: (PlayerSurface) -> Unit,
    onBackToLibrary: () -> Unit,
    onRelinkMedia: () -> Unit,
    onToggleCollected: () -> Unit,
    onQueueSelect: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onSelectTextTrack: (TextTrackSlot, String?) -> Unit,
    onRestoreAutomaticTextTrack: (TextTrackSlot) -> Unit,
    onRestoreAutomaticTextTracks: () -> Unit,
    onAddTextTrack: (TextTrackSlot) -> Unit,
    syncTextTask: GenerationTaskSummary?,
    syncTextLaunch: SyncTextLaunchUiState?,
    onGenerateSyncText: () -> Unit,
    onTranslateSyncText: () -> Unit,
    onRegenerateTranslation: () -> Unit,
    onRegenerateFromAudio: () -> Unit,
    onCancelSyncText: () -> Unit,
    onAbortSyncText: () -> Unit,
    onRetrySyncText: () -> Unit,
    onAllowMeteredSyncText: () -> Unit,
    onGeneratedPresentationModeChanged: (String, TextPresentationMode) -> Unit,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    onGlowLandscapeChanged: (Boolean) -> Unit,
    onCalibrationCompleted: () -> Unit,
    onGestureHintSeen: () -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onCancelSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var textTracksVisible by rememberSaveable { mutableStateOf(false) }
    var regenerationVisible by rememberSaveable { mutableStateOf(false) }
    var timerVisible by rememberSaveable { mutableStateOf(false) }
    val canDisplayTimedText =
        shouldDisplayTimedText(
            playbackState = playback.playbackState,
            errorMessage = playback.errorMessage,
        )
    val cuePositionMs =
        (playback.positionMs + session.subtitleOffsetMs).coerceAtLeast(0L)
    val generatedSourceCue =
        session.generatedText?.sourceTimeline?.resolve(cuePositionMs).takeIf { canDisplayTimedText }
    val generatedTargetCue =
        session.generatedText?.targetTimeline?.resolve(cuePositionMs).takeIf { canDisplayTimedText }
    val primaryTimeline =
        session.generatedText?.targetTimeline
            ?: session.generatedText?.sourceTimeline
            ?: session.subtitle?.timeline
    val currentCue =
        if (canDisplayTimedText) {
            generatedTargetCue ?: generatedSourceCue ?: session.subtitle?.timeline?.resolve(cuePositionMs)
        } else {
            null
        }
    val secondaryCue =
        if (canDisplayTimedText) {
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
        if (!canDisplayTimedText) {
            null
        } else if (session.generatedText != null) {
            generatedTargetCue?.text ?: generatedSourceCue?.text
        } else if (primaryEmbeddedTrack != null) {
            embeddedCueText.takeIf { embeddedCueTrackId == primaryEmbeddedTrack.stableId }
        } else {
            currentCue?.text
        }
    val secondaryText =
        if (!canDisplayTimedText) {
            null
        } else if (hasPairedSource) {
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
                generatedText = session.generatedText,
                syncTextTask = syncTextTask,
                syncTextLaunch = syncTextLaunch,
                sleepTimer = sleepTimer,
                isCollected = isCollected,
                onPlayPause = onPlayPause,
                onBackToLibrary = onBackToLibrary,
                onRelinkMedia = onRelinkMedia,
                onToggleCollected = onToggleCollected,
                onQueueSelect = onQueueSelect,
                onQueueMove = onQueueMove,
                onQueueRemove = onQueueRemove,
                onManageTextTracks = { textTracksVisible = true },
                onGenerateSyncText = onGenerateSyncText,
                onTranslateSyncText = onTranslateSyncText,
                onCancelSyncText = onCancelSyncText,
                onAbortSyncText = onAbortSyncText,
                onRetrySyncText = onRetrySyncText,
                onAllowMeteredSyncText = onAllowMeteredSyncText,
                onEnterGlow = { onSurfaceChanged(PlayerSurface.Glow) },
                onShowTimer = { timerVisible = true },
                modifier = modifier,
            )
        PlayerSurface.Calibration ->
            GlowCalibrationScreen(
                profile = displayProfile,
                isOnboarding = true,
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
                isLandscape = isGlowLandscape,
                sleepTimer = sleepTimer,
                showGestureHint = showGestureHint,
                onPlayPause = onPlayPause,
                onDisplayProfileChanged = onDisplayProfileChanged,
                onLandscapeChanged = onGlowLandscapeChanged,
                onExitGlow = { onSurfaceChanged(PlayerSurface.Normal) },
                onRelinkMedia = onRelinkMedia,
                onManageTextTracks = { textTracksVisible = true },
                onGestureHintSeen = onGestureHintSeen,
                onShowTimer = { timerVisible = true },
                modifier = modifier,
            )
    }

    if (textTracksVisible) {
        SyncTextSheet(
            contentUnit = contentUnit,
            onDismiss = { textTracksVisible = false },
            onSelectTrack = onSelectTextTrack,
            onRestoreAutomatic = onRestoreAutomaticTextTrack,
            onRestoreAllAutomatic = onRestoreAutomaticTextTracks,
            onAddTrack = { slot ->
                textTracksVisible = false
                onAddTextTrack(slot)
            },
            generatedText = session.generatedText,
            subtitleOffsetMs = session.subtitleOffsetMs,
            onSubtitleOffsetChanged = onSubtitleOffsetChanged,
            onPresentationModeChanged = onGeneratedPresentationModeChanged,
            canRegenerate = syncTextTask == null && syncTextLaunch == null,
            onRegenerate = {
                textTracksVisible = false
                if (session.generatedText?.targetTimeline != null) {
                    regenerationVisible = true
                } else {
                    onRegenerateFromAudio()
                }
            },
        )
    }
    if (regenerationVisible) {
        RegenerateSyncTextSheet(
            onRetranslate = {
                regenerationVisible = false
                onRegenerateTranslation()
            },
            onRecognizeAgain = {
                regenerationVisible = false
                onRegenerateFromAudio()
            },
            onDismiss = { regenerationVisible = false },
        )
    }
    if (timerVisible) {
        SleepTimerSheet(
            state = sleepTimer,
            onSetDuration = {
                onSetSleepTimer(it)
                timerVisible = false
            },
            onCancelTimer = {
                onCancelSleepTimer()
                timerVisible = false
            },
            isGlow = surface == PlayerSurface.Glow,
            onDismiss = { timerVisible = false },
        )
    }
}

@Composable
internal fun GlowCalibrationScreen(
    profile: DisplayProfile,
    isOnboarding: Boolean,
    onProfileChanged: (DisplayProfile) -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlowDisplayEditor(
        profile = profile,
        isOnboarding = isOnboarding,
        onProfileChanged = onProfileChanged,
        onComplete = onComplete,
        onCancel = onCancel,
        modifier = modifier,
    )
}

@Composable
internal fun GlowDisplayEditor(
    profile: DisplayProfile,
    isOnboarding: Boolean,
    onProfileChanged: (DisplayProfile) -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitleLevel = profile.glowSubtitleLevel.coerceIn(0, 255)
    val controlLevel = profile.glowControlLevel.coerceIn(0, 255)
    val subtitleColor = Color(subtitleLevel, subtitleLevel, subtitleLevel)
    val controlColor = Color(controlLevel, controlLevel, controlLevel)
    var adjustmentPanelVisible by rememberSaveable { mutableStateOf(true) }
    var previewControlsVisible by rememberSaveable { mutableStateOf(false) }
    var previewControlsRevision by rememberSaveable { mutableIntStateOf(0) }
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = controlColor,
            activeTrackColor = controlColor,
            inactiveTrackColor = GlowTrackColor,
        )

    LaunchedEffect(previewControlsRevision) {
        if (previewControlsRevision > 0) {
            delay(2_400L)
            previewControlsVisible = false
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding(),
    ) {
        val panelMaxHeight = maxHeight * 0.62f
        val revealPanelModifier =
            if (adjustmentPanelVisible) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "显示微光调整面板" }
                    .clickable { adjustmentPanelVisible = true }
            }

        Box(modifier = revealPanelModifier) {
            Column(
                modifier =
                    Modifier
                        .align(
                            BiasAlignment(
                                horizontalBias = 0f,
                                verticalBias = profile.glowSubtitleVerticalBias.coerceIn(-0.60f, 0.60f),
                            ),
                        )
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "声音继续，画面退场。\n需要时，只让当前文字发光。",
                    modifier = Modifier.fillMaxWidth(),
                    color = subtitleColor,
                    fontSize = profile.glowSubtitleSizeSp.coerceIn(18f, 36f).sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = (profile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 1.4f).sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "The sound continues as the screen fades away.",
                    modifier = Modifier.fillMaxWidth(),
                    color = subtitleColor.copy(alpha = 0.68f),
                    fontSize = (profile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 0.68f).sp,
                    lineHeight = (profile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 0.95f).sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        AnimatedVisibility(
            visible = previewControlsVisible,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Replay10, contentDescription = "控制亮度预览：后退 10 秒", tint = controlColor)
                Icon(Icons.Outlined.Pause, contentDescription = "控制亮度预览：暂停", tint = controlColor, modifier = Modifier.size(30.dp))
                Icon(Icons.Outlined.Forward10, contentDescription = "控制亮度预览：前进 10 秒", tint = controlColor)
            }
        }

        AnimatedVisibility(
            visible = adjustmentPanelVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = if (isOnboarding) "返回播放器" else "返回设置",
                        tint = controlColor,
                    )
                }
                Text(
                    if (isOnboarding) "第一次微光校准" else "微光显示",
                    color = controlColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                if (isOnboarding) {
                    Spacer(Modifier.size(48.dp))
                } else {
                    TextButton(onClick = onComplete, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("完成", color = controlColor)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = adjustmentPanelVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = panelMaxHeight)
                        .background(AppSurfaceRaised.copy(alpha = 0.96f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isOnboarding) {
                    Text(
                        "把文字调到刚好能看清。当前页面使用与微光相同的真实窗口亮度。",
                        color = controlColor.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                Text("预设", color = controlColor.copy(alpha = 0.72f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplayProfile.Presets.forEach { preset ->
                        val selected = profile.name == preset.name
                        OutlinedButton(
                            onClick = { onProfileChanged(preset) },
                            border = BorderStroke(1.dp, controlColor.copy(alpha = if (selected) 1f else 0.36f)),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) controlColor.copy(alpha = 0.12f) else Color.Transparent,
                                    contentColor = controlColor,
                                ),
                        ) {
                            Text(preset.name)
                        }
                    }
                }

                CalibrationSlider(
                    label = "窗口亮度",
                    valueLabel = "${(profile.glowWindowBrightness * 100f).roundToInt()}%",
                    startLabel = "更暗",
                    endLabel = "更亮",
                    value = profile.glowWindowBrightness,
                    valueRange = 0.01f..0.30f,
                    colors = sliderColors,
                    controlColor = controlColor,
                    onValueChange = {
                        onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowWindowBrightness = it))
                    },
                )
                CalibrationSlider(
                    label = "文字亮度",
                    valueLabel = "${glowSubtitleBrightnessPercent(profile.glowSubtitleLevel)}%",
                    startLabel = "更暗",
                    endLabel = "更亮",
                    value = profile.glowSubtitleLevel.toFloat(),
                    valueRange = 4f..96f,
                    colors = sliderColors,
                    controlColor = controlColor,
                    onValueChange = {
                        onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleLevel = it.toInt()))
                    },
                )
                CalibrationSlider(
                    label = "控制亮度",
                    valueLabel = "${(profile.glowControlLevel / 255f * 100f).roundToInt()}%",
                    startLabel = "更暗",
                    endLabel = "更亮",
                    value = profile.glowControlLevel.toFloat(),
                    valueRange = 24f..160f,
                    colors = sliderColors,
                    controlColor = controlColor,
                    onValueChange = {
                        previewControlsVisible = true
                        previewControlsRevision += 1
                        onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowControlLevel = it.toInt()))
                    },
                )
                CalibrationSlider(
                    label = "文字大小",
                    valueLabel = "${profile.glowSubtitleSizeSp.roundToInt()}sp",
                    startLabel = "更小",
                    endLabel = "更大",
                    value = profile.glowSubtitleSizeSp,
                    valueRange = 18f..36f,
                    colors = sliderColors,
                    controlColor = controlColor,
                    onValueChange = {
                        onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleSizeSp = it))
                    },
                )
                CalibrationSlider(
                    label = "垂直位置",
                    valueLabel = glowVerticalPositionLabel(profile.glowSubtitleVerticalBias),
                    startLabel = "偏上",
                    endLabel = "偏下",
                    value = profile.glowSubtitleVerticalBias,
                    valueRange = -0.6f..0.6f,
                    colors = sliderColors,
                    controlColor = controlColor,
                    onValueChange = {
                        onProfileChanged(profile.copy(name = DisplayProfile.CustomName, glowSubtitleVerticalBias = it))
                    },
                )

                OutlinedButton(
                    onClick = { adjustmentPanelVisible = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, controlColor.copy(alpha = 0.48f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = controlColor),
                ) {
                    Text("隐藏面板，查看纯净预览")
                }
                if (isOnboarding) {
                    OutlinedButton(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, controlColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = controlColor),
                    ) {
                        Text("完成并进入微光")
                    }
                }
            }
        }
    }
}

internal fun glowVerticalPositionLabel(bias: Float): String =
    when {
        bias < -0.15f -> "偏上"
        bias > 0.15f -> "偏下"
        else -> "居中"
    }

@Composable
private fun CalibrationSlider(
    label: String,
    valueLabel: String,
    startLabel: String,
    endLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: androidx.compose.material3.SliderColors,
    controlColor: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = controlColor, fontSize = 12.sp)
            Text(valueLabel, color = controlColor.copy(alpha = 0.72f), fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.semantics { contentDescription = label },
            colors = colors,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(startLabel, color = controlColor.copy(alpha = 0.52f), fontSize = 10.sp)
            Text(endLabel, color = controlColor.copy(alpha = 0.52f), fontSize = 10.sp)
        }
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
    generatedText: LoadedGeneratedText?,
    syncTextTask: GenerationTaskSummary?,
    syncTextLaunch: SyncTextLaunchUiState?,
    sleepTimer: SleepTimerState,
    isCollected: Boolean,
    onPlayPause: () -> Unit,
    onBackToLibrary: () -> Unit,
    onRelinkMedia: () -> Unit,
    onToggleCollected: () -> Unit,
    onQueueSelect: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onManageTextTracks: () -> Unit,
    onGenerateSyncText: () -> Unit,
    onTranslateSyncText: () -> Unit,
    onCancelSyncText: () -> Unit,
    onAbortSyncText: () -> Unit,
    onRetrySyncText: () -> Unit,
    onAllowMeteredSyncText: () -> Unit,
    onEnterGlow: () -> Unit,
    onShowTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = subtitleContext(primaryTimeline, cuePositionMs, currentCue)
    var queueVisible by rememberSaveable { mutableStateOf(false) }
    val canTranslate =
        generatedText?.targetTimeline == null &&
            generatedText?.completeness == GeneratedResultCompleteness.Final

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        PlayerHeader(
            title = session.media.displayTitle,
            artist = session.media.displayArtist,
            isCollected = isCollected,
            onBack = onBackToLibrary,
            onOpenSyncText = onManageTextTracks,
            onToggleCollected = onToggleCollected,
        )

        if (playback.playbackState == Player.STATE_BUFFERING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AppAccent,
                trackColor = AppSurfaceRaised,
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        SubtitleStage(
            previousText = context.previous?.text,
            primaryText = primaryText,
            secondaryText = secondaryText,
            nextText = context.next?.text,
            hasPrimaryText = hasPrimaryText,
            hasSecondaryText = hasSecondaryText,
            hasGeneratedText = generatedText != null,
            hasPartialGeneratedText = generatedText?.completeness == GeneratedResultCompleteness.Partial,
            canTranslate = canTranslate,
            task = syncTextTask,
            launch = syncTextLaunch,
            onGenerate = onGenerateSyncText,
            onTranslate = onTranslateSyncText,
            onCancel = onCancelSyncText,
            onAbort = onAbortSyncText,
            onRetry = onRetrySyncText,
            onAllowMetered = onAllowMeteredSyncText,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        )

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
            contentColor = AppPrimaryText,
            secondaryColor = AppSecondaryText,
            accentColor = AppAccent,
            trackColor = AppSurfaceRaised,
            onPlayPause = onPlayPause,
            prominent = true,
        )

        PlayerOptions(
            player = player,
            playback = playback,
            sleepTimer = sleepTimer,
            onShowQueue = { queueVisible = true },
            onShowTimer = onShowTimer,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedButton(
                onClick = onEnterGlow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                border = BorderStroke(1.dp, AppHairline),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = AppSurfaceRaised,
                        contentColor = AppPrimaryText,
                    ),
            ) {
                Icon(Icons.Outlined.DarkMode, contentDescription = null, tint = AppAccent)
                Spacer(Modifier.size(8.dp))
                Text("进入微光", color = AppPrimaryText)
            }
        }
    }

    if (queueVisible) {
        QueueDialog(
            player = player,
            playback = playback,
            onSelect = onQueueSelect,
            onMove = onQueueMove,
            onRemove = onQueueRemove,
            onDismiss = { queueVisible = false },
        )
    }
}

private enum class SyncTextSheetPage {
    Overview,
    Primary,
    Secondary,
    Timing,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncTextSheet(
    contentUnit: PlayableContentUnit?,
    onDismiss: () -> Unit,
    onSelectTrack: (TextTrackSlot, String?) -> Unit,
    onRestoreAutomatic: (TextTrackSlot) -> Unit,
    onRestoreAllAutomatic: () -> Unit,
    onAddTrack: (TextTrackSlot) -> Unit,
    generatedText: LoadedGeneratedText?,
    subtitleOffsetMs: Long,
    onSubtitleOffsetChanged: (Long) -> Unit,
    onPresentationModeChanged: (String, TextPresentationMode) -> Unit,
    canRegenerate: Boolean = true,
    onRegenerate: () -> Unit = {},
) {
    val tracks = contentUnit?.timedTextTracks.orEmpty()
    val selection = contentUnit?.activeText ?: ActiveTextSelection()
    val primary = tracks.firstOrNull { it.stableId == selection.primaryTrackId }
    val secondary = tracks.firstOrNull { it.stableId == selection.secondaryTrackId }
    var page by remember { mutableStateOf(SyncTextSheetPage.Overview) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title =
        when (page) {
            SyncTextSheetPage.Overview -> "同步文字"
            SyncTextSheetPage.Primary -> "选择主文字"
            SyncTextSheetPage.Secondary -> "选择第二文字"
            SyncTextSheetPage.Timing -> "时间校准"
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppSurfaceRaised,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = {
            Box(
                modifier =
                    Modifier
                        .padding(top = 10.dp, bottom = 8.dp)
                        .size(width = 32.dp, height = 3.dp)
                        .background(AppHairline, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            SyncTextSheetHeader(
                title = title,
                showBack = page != SyncTextSheetPage.Overview,
                onBack = { page = SyncTextSheetPage.Overview },
                onDismiss = onDismiss,
            )
            when (page) {
                SyncTextSheetPage.Overview ->
                    SyncTextOverview(
                        primary = primary,
                        secondary = secondary,
                        primaryMode = selection.primaryMode,
                        secondaryMode = selection.secondaryMode,
                        generatedText = generatedText,
                        subtitleOffsetMs = subtitleOffsetMs,
                        onOpenPrimary = { page = SyncTextSheetPage.Primary },
                        onOpenSecondary = { page = SyncTextSheetPage.Secondary },
                        onOpenTiming = { page = SyncTextSheetPage.Timing },
                        onRestoreAutomatic = onRestoreAllAutomatic,
                        onPresentationModeChanged = onPresentationModeChanged,
                        canRegenerate = canRegenerate,
                        onRegenerate = onRegenerate,
                    )
                SyncTextSheetPage.Primary,
                SyncTextSheetPage.Secondary,
                -> {
                    val slot =
                        if (page == SyncTextSheetPage.Primary) {
                            TextTrackSlot.Primary
                        } else {
                            TextTrackSlot.Secondary
                        }
                    TextTrackPicker(
                        slot = slot,
                        tracks = tracks,
                        selection = selection,
                        onSelectTrack = { trackId ->
                            onSelectTrack(slot, trackId)
                            page = SyncTextSheetPage.Overview
                        },
                        onRestoreAutomatic = {
                            onRestoreAutomatic(slot)
                            page = SyncTextSheetPage.Overview
                        },
                        onAddTrack = { onAddTrack(slot) },
                    )
                }
                SyncTextSheetPage.Timing ->
                    SubtitleTimingEditor(
                        subtitleOffsetMs = subtitleOffsetMs,
                        onSubtitleOffsetChanged = onSubtitleOffsetChanged,
                    )
            }
        }
    }
}

@Composable
private fun SyncTextSheetHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回同步文字", tint = AppSecondaryText)
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = AppPrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭同步文字", tint = AppSecondaryText)
        }
    }
}

@Composable
private fun SyncTextOverview(
    primary: TimedTextTrack?,
    secondary: TimedTextTrack?,
    primaryMode: TextSelectionMode,
    secondaryMode: TextSelectionMode,
    generatedText: LoadedGeneratedText?,
    subtitleOffsetMs: Long,
    onOpenPrimary: () -> Unit,
    onOpenSecondary: () -> Unit,
    onOpenTiming: () -> Unit,
    onRestoreAutomatic: () -> Unit,
    onPresentationModeChanged: (String, TextPresentationMode) -> Unit,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (generatedText?.targetTimeline != null) {
            Text(
                "显示方式",
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                color = AppSecondaryText,
                fontSize = 13.sp,
            )
            TextPresentationSelector(
                selectedMode = generatedText.presentationMode,
                onSelected = { mode -> onPresentationModeChanged(generatedText.slotId, mode) },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            "文字",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            color = AppSecondaryText,
            fontSize = 13.sp,
        )
        SyncTextSlotRow(
            title = "主文字",
            value = textSelectionLabel(primary, primaryMode),
            failureMessage = primary?.failureMessage,
            onClick = onOpenPrimary,
        )
        SyncTextSlotRow(
            title = "第二文字",
            value = textSelectionLabel(secondary, secondaryMode),
            failureMessage = secondary?.failureMessage,
            onClick = onOpenSecondary,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = AppHairline)
        Text(
            "校正",
            modifier = Modifier.padding(bottom = 4.dp),
            color = AppSecondaryText,
            fontSize = 13.sp,
        )
        SyncTextActionRow(
            title = "时间校准",
            value = subtitleOffsetValueLabel(subtitleOffsetMs),
            onClick = onOpenTiming,
        )
        SyncTextActionRow(
            title = "重新自动匹配",
            onClick = onRestoreAutomatic,
        )
        if (canRegenerate) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = AppHairline)
            SyncTextActionRow(
                title = "重新生成",
                onClick = onRegenerate,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegenerateSyncTextSheet(
    onRetranslate: () -> Unit,
    onRecognizeAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppSurfaceRaised,
        scrimColor = Color.Black.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            SyncTextSheetHeader(
                title = "重新生成",
                showBack = false,
                onBack = {},
                onDismiss = onDismiss,
            )
            SyncTextActionRow(title = "重新翻译", onClick = onRetranslate)
            SyncTextActionRow(title = "从声音重新生成", onClick = onRecognizeAgain)
        }
    }
}

@Composable
private fun TextPresentationSelector(
    selectedMode: TextPresentationMode,
    onSelected: (TextPresentationMode) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AppHairline)
                .padding(1.dp),
    ) {
        listOf(
            TextPresentationMode.TargetOnly to "译文",
            TextPresentationMode.SourceTarget to "双语",
        ).forEach { (mode, label) ->
            val selected = mode == selectedMode
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (selected) AppSurfaceSelected else AppSurfaceRaised)
                        .clickable { onSelected(mode) }
                        .semantics { contentDescription = "显示方式：$label" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) AppPrimaryText else AppMutedText,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SyncTextSlotRow(
    title: String,
    value: String,
    failureMessage: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = AppMutedText, fontSize = 12.sp)
            Text(
                value,
                color = AppPrimaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            failureMessage?.let {
                Text(it, color = AppError, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", color = AppMutedText, fontSize = 24.sp)
    }
}

@Composable
private fun SyncTextActionRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = AppPrimaryText, fontSize = 15.sp)
        value?.let { Text(it, color = AppMutedText, fontSize = 13.sp) }
        Spacer(modifier = Modifier.width(8.dp))
        Text("›", color = AppMutedText, fontSize = 24.sp)
    }
}

@Composable
private fun TextTrackPicker(
    slot: TextTrackSlot,
    tracks: List<TimedTextTrack>,
    selection: ActiveTextSelection,
    onSelectTrack: (String?) -> Unit,
    onRestoreAutomatic: () -> Unit,
    onAddTrack: () -> Unit,
) {
    val selectedTrackId =
        if (slot == TextTrackSlot.Primary) selection.primaryTrackId else selection.secondaryTrackId
    val selectionMode =
        if (slot == TextTrackSlot.Primary) selection.primaryMode else selection.secondaryMode
    val availableTracks =
        tracks.filterNot { slot == TextTrackSlot.Secondary && it.stableId == selection.primaryTrackId }

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        item(key = "automatic") {
            TextTrackPickerRow(
                title = "自动选择",
                supportingText =
                    tracks
                        .firstOrNull { it.stableId == selectedTrackId }
                        ?.takeIf { selectionMode == TextSelectionMode.Automatic }
                        ?.let(::textTrackLabel),
                selected = selectionMode == TextSelectionMode.Automatic,
                onClick = onRestoreAutomatic,
            )
        }
        if (availableTracks.isEmpty()) {
            item(key = "empty") {
                Text(
                    "没有找到可用文字",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    color = AppMutedText,
                    fontSize = 13.sp,
                )
            }
        } else {
            itemsIndexed(
                items = availableTracks,
                key = { _, track -> track.stableId },
            ) { _, track ->
                TextTrackPickerRow(
                    title = textTrackLabel(track),
                    supportingText = textTrackProblemLabel(track),
                    selected = selectionMode == TextSelectionMode.Manual && track.stableId == selectedTrackId,
                    onClick = { onSelectTrack(track.stableId) },
                )
            }
        }
        item(key = "off") {
            TextTrackPickerRow(
                title = "关闭",
                selected = selectionMode == TextSelectionMode.Manual && selectedTrackId == null,
                onClick = { onSelectTrack(null) },
            )
        }
        item(key = "divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = AppHairline)
        }
        item(key = "file") {
            SyncTextActionRow(title = "从文件添加文字", onClick = onAddTrack)
        }
    }
}

@Composable
private fun TextTrackPickerRow(
    title: String,
    supportingText: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors =
                RadioButtonDefaults.colors(
                    selectedColor = AppAccent,
                    unselectedColor = AppMutedText,
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AppPrimaryText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            supportingText?.let {
                Text(
                    it,
                    color = AppError,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SubtitleTimingEditor(
    subtitleOffsetMs: Long,
    onSubtitleOffsetChanged: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "让文字与声音对齐",
            color = AppSecondaryText,
            fontSize = 13.sp,
        )
        Text(
            subtitleOffsetValueLabel(subtitleOffsetMs),
            color = AppPrimaryText,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    onSubtitleOffsetChanged((subtitleOffsetMs - 500L).coerceAtLeast(-60_000L))
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text("提前 0.5 秒", color = AppSecondaryText)
            }
            OutlinedButton(
                onClick = {
                    onSubtitleOffsetChanged((subtitleOffsetMs + 500L).coerceAtMost(60_000L))
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text("延后 0.5 秒", color = AppSecondaryText)
            }
        }
        TextButton(
            onClick = { onSubtitleOffsetChanged(0L) },
            enabled = subtitleOffsetMs != 0L,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("恢复默认", color = if (subtitleOffsetMs == 0L) AppMutedText else AppSecondaryText)
        }
    }
}

@Composable
internal fun SyncTextProgressStatus(
    task: GenerationTaskSummary?,
    launch: SyncTextLaunchUiState?,
    expanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val presentation = syncTextProgressPresentation(task, launch) ?: return
    if (expanded) {
        Column(
            modifier = modifier.heightIn(min = 180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (presentation.showWorkUnitRing) {
                WorkUnitRing(presentation = presentation, size = 112.dp)
            } else {
                StreamActivityGlyph(presentation = presentation, size = 72.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                presentation.label,
                color = AppPrimaryText,
                fontSize = 16.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            presentation.detail?.let { detail ->
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    color = AppSecondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        Row(
            modifier = modifier.heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (presentation.showWorkUnitRing) {
                WorkUnitRing(presentation = presentation, size = 44.dp)
            } else {
                StreamActivityGlyph(presentation = presentation, size = 52.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    presentation.label,
                    color = AppPrimaryText,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
                presentation.detail?.let { detail ->
                    Text(
                        detail,
                        color = AppSecondaryText,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamActivityGlyph(
    presentation: SyncTextProgressPresentation,
    size: androidx.compose.ui.unit.Dp,
) {
    val glow = remember { Animatable(0.16f) }
    val initialRevision = remember { presentation.streamActivityRevision }
    LaunchedEffect(presentation.streamActivityRevision) {
        if (
            presentation.streamActivityRevision != null &&
                presentation.streamActivityRevision != initialRevision
        ) {
            glow.snapTo(1f)
            glow.animateTo(0.16f, animationSpec = tween(850))
        }
    }
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(if (size > 60.dp) 3.dp else 2.dp)) {
            val stroke = Stroke(width = if (size > 60.dp) 5.dp.toPx() else 3.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = AppHairline,
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                style = stroke,
            )
            drawArc(
                color = AppAccent.copy(alpha = 0.25f + glow.value * 0.75f),
                startAngle = 300f,
                sweepAngle = 80f,
                useCenter = false,
                style = stroke,
            )
        }
        presentation.ringCenterText?.let { centerText ->
            Text(
                centerText,
                color = AppPrimaryText,
                fontSize =
                    when {
                        size > 60.dp -> 12.sp
                        centerText.length <= 5 -> 10.sp
                        centerText.length <= 7 -> 8.sp
                        else -> 7.sp
                    },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WorkUnitRing(
    presentation: SyncTextProgressPresentation,
    size: androidx.compose.ui.unit.Dp,
) {
    val total = presentation.totalUnitCount
    val completed = presentation.completedUnitCount.coerceIn(0, total.coerceAtLeast(0))
    val transition = rememberInfiniteTransition(label = "同步文字运行脉冲")
    val pulse by
        transition.animateFloat(
            initialValue = 0.38f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "活动分段亮度",
        )
    Box(
        modifier =
            Modifier.size(size).semantics {
                contentDescription =
                    if (total > 0) {
                        "${presentation.label}，已完成 $completed/$total"
                    } else {
                        presentation.label
                    }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (total <= 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.82f),
                color = AppAccent,
                strokeWidth = if (size > 60.dp) 5.dp else 3.dp,
                trackColor = AppHairline,
            )
        } else {
            val segmentCount = total.coerceAtMost(16)
            val completedSegments = (completed.toFloat() / total * segmentCount).toInt().coerceIn(0, segmentCount)
            val activeSegments =
                if (presentation.activeUnitCount > 0 && completedSegments < segmentCount) {
                    ((presentation.activeUnitCount.toFloat() / total * segmentCount).coerceAtLeast(1f)).toInt()
                } else {
                    0
                }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gap = if (segmentCount <= 8) 10f else 5f
                val step = 360f / segmentCount
                val stroke = Stroke(width = if (size > 60.dp) 5.dp.toPx() else 3.dp.toPx(), cap = StrokeCap.Round)
                repeat(segmentCount) { index ->
                    val color =
                        if (total <= 16 && presentation.unitStates.size == total) {
                            when (presentation.unitStates[index]) {
                                GenerationWorkUnitState.Completed -> AppAccent
                                GenerationWorkUnitState.Running -> AppAccent.copy(alpha = pulse)
                                GenerationWorkUnitState.Failed -> AppError
                                GenerationWorkUnitState.Waiting -> AppHairline
                            }
                        } else {
                            when {
                                index < completedSegments -> AppAccent
                                index < completedSegments + activeSegments -> AppAccent.copy(alpha = pulse)
                                else -> AppHairline
                            }
                        }
                    drawArc(
                        color = color,
                        startAngle = -90f + index * step + gap / 2f,
                        sweepAngle = step - gap,
                        useCenter = false,
                        style = stroke,
                    )
                }
            }
            if (total > 1) {
                Text(
                    "$completed/$total",
                    color = AppPrimaryText,
                    fontSize = if (size > 60.dp) 20.sp else 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun PlayerHeader(
    title: String,
    artist: String?,
    isCollected: Boolean,
    onBack: () -> Unit,
    onOpenSyncText: () -> Unit,
    onToggleCollected: () -> Unit,
) {
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = AppSecondaryText)
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = AppPrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            artist?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    color = AppMutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuVisible = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作", tint = AppSecondaryText)
            }
            DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                DropdownMenuItem(
                    text = { Text("同步文字") },
                    leadingIcon = { Icon(Icons.Outlined.Subtitles, contentDescription = null) },
                    onClick = {
                        menuVisible = false
                        onOpenSyncText()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(if (isCollected) "从“我的内容”移除" else "加入“我的内容”")
                    },
                    leadingIcon = {
                        Icon(
                            if (isCollected) Icons.Outlined.Remove else Icons.Outlined.Add,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuVisible = false
                        onToggleCollected()
                    },
                )
            }
        }
    }
}

@Composable
internal fun SubtitleStage(
    previousText: String?,
    primaryText: String?,
    secondaryText: String?,
    nextText: String?,
    hasPrimaryText: Boolean,
    hasSecondaryText: Boolean,
    hasGeneratedText: Boolean,
    hasPartialGeneratedText: Boolean,
    canTranslate: Boolean,
    task: GenerationTaskSummary?,
    launch: SyncTextLaunchUiState?,
    onGenerate: () -> Unit,
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
    onAbort: () -> Unit,
    onRetry: () -> Unit,
    onAllowMetered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasText = hasPrimaryText || hasSecondaryText
    val progress = syncTextProgressPresentation(task, launch)
    val productStatus =
        generationProductStatus(
            task,
            hasGeneratedText = hasGeneratedText,
            hasPartialText = hasPartialGeneratedText,
        )
    val retryable = productStatus?.contains("失败") == true || productStatus?.contains("已暂停") == true
    val taskFooterVisible = progress != null || retryable || canTranslate

    BoxWithConstraints(modifier = modifier) {
        val compactText = maxHeight < 380.dp || LocalDensity.current.fontScale > 1.3f
        if (hasText) {
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(bottom = if (taskFooterVisible) 56.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (hasPrimaryText) {
                    if (!compactText) {
                        CueText(previousText.orEmpty(), AppMutedText, fontSize = 15, maxLines = 3)
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                    CueText(primaryText.orEmpty(), AppPrimaryText, fontSize = 26, maxLines = if (compactText) 3 else 6)
                    if (hasSecondaryText) {
                        Spacer(modifier = Modifier.height(10.dp))
                        CueText(
                            secondaryText.orEmpty(),
                            AppSecondaryText,
                            fontSize = 17,
                            maxLines = if (compactText) 2 else 4,
                        )
                    }
                    if (!compactText) {
                        Spacer(modifier = Modifier.height(24.dp))
                        CueText(nextText.orEmpty(), AppMutedText, fontSize = 15, maxLines = 3)
                    }
                } else {
                    CueText(
                        secondaryText.orEmpty(),
                        AppPrimaryText,
                        fontSize = 24,
                        maxLines = if (compactText) 3 else 6,
                    )
                }
            }
        }

        val taskModifier =
            if (hasText) {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            } else {
                Modifier.align(Alignment.Center).fillMaxWidth()
            }
        when {
            progress != null ->
                SyncTextTaskIndicator(
                    task = task,
                    launch = launch,
                    onCancel = onCancel,
                    onAbort = onAbort,
                    onAllowMetered = onAllowMetered,
                    expanded = !hasText,
                    modifier = taskModifier,
                )
            retryable ->
                SyncTextTerminalStatus(
                    status = requireNotNull(productStatus),
                    onRetry = onRetry,
                    onAbort = onAbort,
                    modifier = taskModifier,
                )
            !hasText ->
                TextButton(onClick = onGenerate, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Outlined.Subtitles, contentDescription = null, tint = AppAccent)
                    Spacer(Modifier.size(8.dp))
                    Text("生成同步文字", color = AppAccent)
                }
            canTranslate && launch == null && (task == null || task.state == GenerationTaskState.Completed) ->
                IconButton(
                    onClick = onTranslate,
                    modifier = Modifier.align(Alignment.BottomEnd).size(48.dp),
                ) {
                    Icon(Icons.Outlined.Translate, contentDescription = "翻译同步文字", tint = AppAccent)
                }
        }
    }
}

@Composable
private fun SyncTextTaskIndicator(
    task: GenerationTaskSummary?,
    launch: SyncTextLaunchUiState?,
    onCancel: () -> Unit,
    onAbort: () -> Unit,
    onAllowMetered: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (expanded) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            SyncTextProgressStatus(task = task, launch = launch, expanded = true, modifier = Modifier.fillMaxWidth())
            if (launch == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (task?.networkWaitReason == GenerationNetworkWaitReason.MeteredPermissionRequired) {
                        TextButton(onClick = onAllowMetered) {
                            Text("允许本次", color = AppSecondaryText, fontSize = 12.sp)
                        }
                    } else {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Pause, contentDescription = "暂停生成", tint = AppSecondaryText)
                        }
                        IconButton(onClick = onAbort) {
                            Icon(Icons.Outlined.Stop, contentDescription = "停止生成", tint = AppError)
                        }
                    }
                }
            }
        }
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            SyncTextProgressStatus(task = task, launch = launch, modifier = Modifier.weight(1f))
            if (launch == null) {
                if (task?.networkWaitReason == GenerationNetworkWaitReason.MeteredPermissionRequired) {
                    TextButton(onClick = onAllowMetered) {
                        Text("允许本次", color = AppSecondaryText, fontSize = 12.sp)
                    }
                } else {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Pause, contentDescription = "暂停生成", tint = AppSecondaryText)
                    }
                    IconButton(onClick = onAbort) {
                        Icon(Icons.Outlined.Stop, contentDescription = "停止生成", tint = AppError)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncTextTerminalStatus(
    status: String,
    onRetry: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, color = if (status.contains("失败")) AppError else AppSecondaryText, fontSize = 12.sp)
        IconButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = "继续同步文字", tint = AppSecondaryText)
        }
        IconButton(onClick = onAbort) {
            Icon(Icons.Outlined.Stop, contentDescription = "停止生成", tint = AppError)
        }
    }
}

private fun textTrackLabel(track: TimedTextTrack): String =
    when {
        track.origin == TimedTextOrigin.Generated && track.label?.contains("译文") == true ->
            "${textTrackLanguageLabel(track)}（${if (track.label.contains("上一版")) "翻译，上一版" else "翻译"}）"
        track.origin == TimedTextOrigin.Generated && track.label?.contains("原文") == true ->
            "${textTrackLanguageLabel(track)}（${if (track.label.contains("上一版")) "原文，上一版" else "原文"}）"
        else ->
            track.label?.takeIf(String::isNotBlank)?.removePrefix("生成")
                ?: track.language?.takeIf(String::isNotBlank)?.let(::languageLabel)
                ?: track.locator.displayName.substringBeforeLast('.', track.locator.displayName)
    }

private fun textTrackLanguageLabel(track: TimedTextTrack): String =
    track.language?.takeIf(String::isNotBlank)?.let(::languageLabel)
        ?: track.label
            ?.substringAfterLast('·', "")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::languageLabel)
        ?: "文字"

private fun languageLabel(language: String): String =
    when (language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
        "zh" -> "中文"
        "ja" -> "日文"
        "en" -> "英文"
        "ko" -> "韩文"
        else -> language
    }

private fun textSelectionLabel(
    track: TimedTextTrack?,
    mode: TextSelectionMode,
): String =
    track?.let(::textTrackLabel)
        ?: if (mode == TextSelectionMode.Automatic) "自动选择" else "关闭"

private fun textTrackProblemLabel(track: TimedTextTrack): String? =
    track.failureMessage
        ?: when (track.readiness) {
            TimedTextReadiness.Unreadable -> "文字文件无法读取"
            TimedTextReadiness.ParseFailed -> "文字文件无法解析"
            TimedTextReadiness.MissingArtifact -> "文字内容需要重新添加"
            else -> null
        }

private fun PlayableContentUnit.selectedTrack(slot: TextTrackSlot): TimedTextTrack? {
    val trackId =
        when (slot) {
            TextTrackSlot.Primary -> activeText.primaryTrackId
            TextTrackSlot.Secondary -> activeText.secondaryTrackId
        }
    return timedTextTracks.firstOrNull { it.stableId == trackId }
}

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
    isLandscape: Boolean,
    sleepTimer: SleepTimerState,
    showGestureHint: Boolean,
    onPlayPause: () -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    onLandscapeChanged: (Boolean) -> Unit,
    onExitGlow: () -> Unit,
    onRelinkMedia: () -> Unit,
    onManageTextTracks: () -> Unit,
    onGestureHintSeen: () -> Unit,
    onShowTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var controlInteractionRevision by rememberSaveable { mutableIntStateOf(0) }
    var gestureFeedback by remember { mutableStateOf<String?>(null) }
    var gestureFeedbackRevision by remember { mutableIntStateOf(0) }
    var brightnessPreviewLevel by remember { mutableStateOf<Int?>(null) }
    var brightnessFeedbackVisible by remember { mutableStateOf(false) }
    var brightnessDragging by remember { mutableStateOf(false) }
    var brightnessFeedbackRevision by remember { mutableIntStateOf(0) }
    var primaryGestureHintVisible by rememberSaveable { mutableStateOf(showGestureHint) }
    var gestureGuideVisible by rememberSaveable { mutableStateOf(false) }
    var gestureGuideShown by rememberSaveable { mutableStateOf(!showGestureHint) }
    val latestDisplayProfile by rememberUpdatedState(displayProfile)
    val latestOnDisplayProfileChanged by rememberUpdatedState(onDisplayProfileChanged)
    val subtitleLevel =
        (brightnessPreviewLevel ?: displayProfile.glowSubtitleLevel)
            .coerceIn(MinGlowSubtitleLevel, MaxGlowSubtitleLevel)
    val controlLevel = displayProfile.glowControlLevel.coerceIn(0, 255)
    val subtitleColor = Color(subtitleLevel, subtitleLevel, subtitleLevel)
    val controlColor = Color(controlLevel, controlLevel, controlLevel)
    val showTimedText =
        shouldShowGlowTimedText(
            playbackState = playback.playbackState,
            errorMessage = playback.errorMessage,
            isPlaying = playback.isPlaying,
            sleepTimerDidFinish = sleepTimer.didFinish,
        )

    fun showControls() {
        controlsVisible = true
        controlInteractionRevision += 1
        primaryGestureHintVisible = false
        if (!gestureGuideShown) {
            gestureGuideVisible = true
            gestureGuideShown = true
        }
        onGestureHintSeen()
    }

    fun hideControls() {
        controlsVisible = false
        gestureGuideVisible = false
    }

    fun showGestureFeedback(message: String) {
        gestureFeedback = message
        gestureFeedbackRevision += 1
    }

    LaunchedEffect(controlsVisible, controlInteractionRevision) {
        if (controlsVisible) {
            delay(5_000L)
            hideControls()
        }
    }
    LaunchedEffect(gestureFeedbackRevision) {
        if (gestureFeedback != null) {
            val revision = gestureFeedbackRevision
            delay(850L)
            if (revision == gestureFeedbackRevision) gestureFeedback = null
        }
    }
    LaunchedEffect(brightnessDragging, brightnessFeedbackRevision) {
        if (brightnessFeedbackVisible && !brightnessDragging) {
            val revision = brightnessFeedbackRevision
            delay(800L)
            if (revision == brightnessFeedbackRevision) brightnessFeedbackVisible = false
        }
    }
    LaunchedEffect(showGestureHint) {
        if (showGestureHint) {
            delay(6_500L)
            primaryGestureHintVisible = false
        } else {
            primaryGestureHintVisible = false
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics {
                    contentDescription =
                        if (controlsVisible) "微光临时控制已显示" else "微光播放，长按显示控制"
                    onClick(
                        label = if (controlsVisible) "收起微光控制" else "显示微光控制",
                    ) {
                        if (controlsVisible) hideControls() else showControls()
                        true
                    }
                }
                .pointerInput(controlsVisible) {
                    var adjustsBrightness = false
                    var startLevel = MinGlowSubtitleLevel
                    var accumulatedDragPx = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            adjustsBrightness =
                                !controlsVisible && offset.x >= size.width * GlowBrightnessGestureStartFraction
                            if (adjustsBrightness) {
                                primaryGestureHintVisible = false
                                onGestureHintSeen()
                                startLevel =
                                    latestDisplayProfile.glowSubtitleLevel
                                        .coerceIn(MinGlowSubtitleLevel, MaxGlowSubtitleLevel)
                                accumulatedDragPx = 0f
                                brightnessPreviewLevel = startLevel
                                brightnessDragging = true
                                brightnessFeedbackVisible = true
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            if (adjustsBrightness) {
                                change.consume()
                                accumulatedDragPx += dragAmount
                                brightnessPreviewLevel =
                                    adjustedGlowSubtitleLevel(
                                        startLevel = startLevel,
                                        accumulatedDragPx = accumulatedDragPx,
                                        gestureHeightPx = size.height.toFloat(),
                                    )
                            }
                        },
                        onDragEnd = {
                            if (adjustsBrightness) {
                                val level = brightnessPreviewLevel
                                val profile = latestDisplayProfile
                                if (level != null && level != profile.glowSubtitleLevel) {
                                    latestOnDisplayProfileChanged(
                                        profile.copy(
                                            name = DisplayProfile.CustomName,
                                            glowSubtitleLevel = level,
                                        ),
                                    )
                                }
                                brightnessPreviewLevel = null
                                brightnessDragging = false
                                brightnessFeedbackRevision += 1
                            }
                            adjustsBrightness = false
                        },
                        onDragCancel = {
                            if (adjustsBrightness) {
                                brightnessPreviewLevel = null
                                brightnessDragging = false
                                brightnessFeedbackVisible = false
                            }
                            adjustsBrightness = false
                        },
                    )
                }
                .pointerInput(controlsVisible, playback.isPlaying) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) hideControls()
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
                text = primaryText.takeIf { showTimedText }.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
                color = subtitleColor,
                fontSize = displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f).sp,
                fontWeight = FontWeight.Medium,
                lineHeight =
                    (displayProfile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 1.4f).sp,
                textAlign = TextAlign.Center,
            )
            if (hasSecondaryText && showTimedText) {
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
            visible = brightnessFeedbackVisible,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.LightMode,
                    contentDescription = null,
                    tint = controlColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "文字 ${glowSubtitleBrightnessPercent(subtitleLevel)}%",
                    color = controlColor,
                    fontSize = 11.sp,
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
            visible = primaryGestureHintVisible && !controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            GlowGestureHint(controlColor = controlColor)
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
                    IconButton(
                        onClick = {
                            onLandscapeChanged(!isLandscape)
                            showControls()
                        },
                    ) {
                        Icon(
                            Icons.Outlined.ScreenRotation,
                            contentDescription = if (isLandscape) "切换为竖屏" else "切换为横屏",
                            tint = controlColor,
                        )
                    }
                    IconButton(onClick = onShowTimer) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = "睡眠定时：${sleepTimerLabel(sleepTimer)}",
                            tint = controlColor,
                        )
                    }
                    IconButton(onClick = onManageTextTracks) {
                        Icon(Icons.Outlined.Subtitles, contentDescription = "同步文字", tint = controlColor)
                    }
                    IconButton(onClick = onExitGlow) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "退出微光", tint = controlColor)
                    }
                    IconButton(onClick = ::hideControls) {
                        Icon(Icons.Outlined.Close, contentDescription = "收起控制", tint = controlColor)
                    }
                }
                if (gestureGuideVisible) {
                    GlowGestureGuide(controlColor = controlColor)
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
                    contentColor = controlColor,
                    secondaryColor = controlColor.copy(alpha = 0.72f),
                    accentColor = controlColor,
                    trackColor = GlowTrackColor,
                    onPlayPause = onPlayPause,
                    onInteraction = ::showControls,
                )
            }
        }
    }
}

@Composable
internal fun GlowGestureHint(controlColor: Color, modifier: Modifier = Modifier) {
    Text(
        "长按屏幕，显示播放控制",
        modifier = modifier.padding(horizontal = 24.dp, vertical = 28.dp),
        color = controlColor,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun GlowGestureGuide(controlColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "双击左 / 中 / 右侧：快退 / 暂停 / 快进",
            color = controlColor.copy(alpha = 0.72f),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "右侧上下滑动：调节文字亮度",
            color = controlColor.copy(alpha = 0.72f),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
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
    contentColor: Color,
    secondaryColor: Color,
    accentColor: Color,
    trackColor: Color,
    onPlayPause: () -> Unit,
    onInteraction: () -> Unit = {},
    prominent: Boolean = false,
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
        Text(formatTime(displayedPositionMs), color = secondaryColor, fontSize = if (prominent) 13.sp else 11.sp)
        Text(formatTime(playback.durationMs), color = secondaryColor, fontSize = if (prominent) 13.sp else 11.sp)
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
                player.seekWithinMedia(-10_000L)
            },
        ) {
            Icon(Icons.Outlined.Replay10, contentDescription = "后退 10 秒", tint = contentColor)
        }
        IconButton(
            onClick = {
                onInteraction()
                onPlayPause()
            },
            modifier = if (prominent) Modifier.size(64.dp).background(AppSurfaceRaised, RoundedCornerShape(32.dp)) else Modifier.size(56.dp),
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
                player.seekWithinMedia(10_000L)
            },
        ) {
            Icon(Icons.Outlined.Forward10, contentDescription = "前进 10 秒", tint = contentColor)
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
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { player.setPlaybackSpeed(nextPlaybackSpeed(playback.speed)) }) {
            Text(formatSpeed(playback.speed), color = AppSecondaryText)
        }
        SleepTimerButton(state = sleepTimer, onClick = onShowTimer)
        IconButton(onClick = onShowQueue) {
            Icon(
                Icons.AutoMirrored.Outlined.QueueMusic,
                contentDescription = "播放列表，共 ${playback.queue.size} 项",
                tint = AppMutedText,
            )
        }
    }
}

@Composable
private fun SleepTimerButton(
    state: SleepTimerState,
    onClick: () -> Unit,
) {
    val remainingFraction = sleepTimerRemainingFraction(state)
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (state.isActive) {
            Canvas(modifier = Modifier.size(42.dp)) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = AppAccent.copy(alpha = 0.16f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                )
                drawArc(
                    color = AppAccent,
                    startAngle = -90f,
                    sweepAngle = 360f * remainingFraction,
                    useCenter = false,
                    style = stroke,
                )
            }
        }
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Outlined.Timer,
                contentDescription = "睡眠定时：${sleepTimerLabel(state)}",
                tint = if (state.isActive) AppAccent else AppMutedText,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun QueueDialog(
    player: Player,
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
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
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
                            if (playback.repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Outlined.RepeatOne
                            } else {
                                Icons.Outlined.Repeat
                            },
                            contentDescription = repeatModeLabel(playback.repeatMode),
                            tint = if (playback.repeatMode == Player.REPEAT_MODE_OFF) AppMutedText else AppAccent,
                        )
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成", color = AppAccent) }
        },
        containerColor = AppSurfaceRaised,
    )
}

private const val MaxSleepTimerHours = 23

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerSheet(
    state: SleepTimerState,
    onSetDuration: (Long) -> Unit,
    onCancelTimer: () -> Unit,
    isGlow: Boolean,
    onDismiss: () -> Unit,
) {
    val configuredMinutes =
        (state.configuredDurationMs / 60_000L)
            .coerceIn(1L, MaxSleepTimerHours * 60L + 59L)
            .toInt()
    val configuredHours = configuredMinutes / 60
    val configuredMinutePart = configuredMinutes % 60
    val hourState = rememberLazyListState(initialFirstVisibleItemIndex = configuredHours)
    val minuteState = rememberLazyListState(initialFirstVisibleItemIndex = configuredMinutePart)
    val selectedHours by remember { derivedStateOf { hourState.firstVisibleItemIndex } }
    val selectedMinutes by remember { derivedStateOf { minuteState.firstVisibleItemIndex } }
    val durationMs = sleepTimerDurationMs(selectedHours, selectedMinutes)

    LaunchedEffect(state.configuredDurationMs) {
        if (!hourState.isScrollInProgress) hourState.scrollToItem(configuredHours)
        if (!minuteState.isScrollInProgress) minuteState.scrollToItem(configuredMinutePart)
    }
    val containerColor = if (isGlow) Color.Black else AppSurfaceRaised
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = {
            Box(
                modifier =
                    Modifier
                        .padding(top = 10.dp, bottom = 8.dp)
                        .size(width = 32.dp, height = 3.dp)
                        .background(AppHairline, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "睡眠定时",
                color = AppPrimaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (state.isActive) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("还剩", color = AppSecondaryText, fontSize = 13.sp)
                    Text(
                        formatSleepTimerRemaining(state.remainingMs),
                        color = AppPrimaryText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }

                OutlinedButton(
                    onClick = onCancelTimer,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, AppHairline),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = AppSecondaryText,
                        ),
                ) {
                    Text("关闭定时")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        DurationWheel(
                            values = 0..MaxSleepTimerHours,
                            state = hourState,
                            label = "小时",
                            modifier = Modifier.weight(1f),
                        )
                        Text("小时", color = AppSecondaryText, fontSize = 13.sp)
                        Spacer(Modifier.width(12.dp))
                        DurationWheel(
                            values = 0..59,
                            state = minuteState,
                            label = "分钟",
                            modifier = Modifier.weight(1f),
                        )
                        Text("分钟", color = AppSecondaryText, fontSize = 13.sp)
                    }

                    if (durationMs == 0L) {
                        Text(
                            "至少选择 1 分钟",
                            modifier = Modifier.fillMaxWidth(),
                            color = AppSecondaryText,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Button(
                    onClick = { onSetDuration(durationMs) },
                    enabled = durationMs > 0L,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "开始定时" },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = AppAccent,
                            contentColor = Color.Black,
                            disabledContainerColor = AppHairline,
                            disabledContentColor = AppMutedText,
                        ),
                ) {
                    Text("开始定时")
                }
            }
        }
    }
}

@Composable
private fun DurationWheel(
    values: IntRange,
    state: LazyListState,
    label: String,
    modifier: Modifier = Modifier,
) {
    val selectedIndex by remember { derivedStateOf { state.firstVisibleItemIndex } }
    Box(
        modifier =
            modifier
                .height(144.dp)
                .semantics { contentDescription = "$label，当前 ${values.first + selectedIndex}" },
    ) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(AppSurfaceSelected, RoundedCornerShape(8.dp)),
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 48.dp),
            flingBehavior = rememberSnapFlingBehavior(state),
        ) {
            items(values.count()) { index ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (values.first + index).toString().padStart(2, '0'),
                        color = if (selected) AppPrimaryText else AppMutedText,
                        fontSize = if (selected) 24.sp else 16.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

internal fun sleepTimerDurationMs(hours: Int, minutes: Int): Long =
    (hours.coerceIn(0, MaxSleepTimerHours) * 60L + minutes.coerceIn(0, 59)) * 60_000L

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

private fun subtitleOffsetValueLabel(milliseconds: Long): String =
    if (milliseconds == 0L) {
        "0.0 秒"
    } else {
        String.format(Locale.ROOT, "%+.1f 秒", milliseconds / 1_000f)
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

internal fun formatSleepTimerRemaining(remainingMs: Long): String {
    if (remainingMs <= 0L) return "即将停止"
    if (remainingMs < 60_000L) return "不到 1 分钟"
    val totalMinutes = (remainingMs + 59_999L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours == 0L -> "$minutes 分钟"
        minutes == 0L -> "$hours 小时"
        else -> "$hours 小时 $minutes 分钟"
    }
}

internal fun sleepTimerRemainingFraction(state: SleepTimerState): Float =
    if (!state.isActive || state.configuredDurationMs <= 0L) {
        0f
    } else {
        (state.remainingMs.toDouble() / state.configuredDurationMs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

private fun sleepTimerLabel(state: SleepTimerState): String =
    if (state.isActive) "还剩 ${formatSleepTimerRemaining(state.remainingMs)}" else "未开启"

internal fun adjustedGlowSubtitleLevel(
    startLevel: Int,
    accumulatedDragPx: Float,
    gestureHeightPx: Float,
): Int {
    val clampedStart = startLevel.coerceIn(MinGlowSubtitleLevel, MaxGlowSubtitleLevel)
    if (!accumulatedDragPx.isFinite() || !gestureHeightPx.isFinite() || gestureHeightPx <= 0f) {
        return clampedStart
    }
    val levelRange = MaxGlowSubtitleLevel - MinGlowSubtitleLevel
    val delta = (-accumulatedDragPx / gestureHeightPx * levelRange).roundToInt()
    return (clampedStart + delta).coerceIn(MinGlowSubtitleLevel, MaxGlowSubtitleLevel)
}

internal fun glowSubtitleBrightnessPercent(level: Int): Int =
    ((level.coerceIn(MinGlowSubtitleLevel, MaxGlowSubtitleLevel) - MinGlowSubtitleLevel) *
        100f /
        (MaxGlowSubtitleLevel - MinGlowSubtitleLevel))
        .roundToInt()

internal fun shouldDisplayTimedText(
    playbackState: Int,
    errorMessage: String?,
): Boolean =
    playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED &&
        errorMessage == null

internal fun shouldShowGlowTimedText(
    playbackState: Int,
    errorMessage: String?,
    isPlaying: Boolean,
    sleepTimerDidFinish: Boolean,
): Boolean =
    shouldDisplayTimedText(playbackState, errorMessage) &&
        (isPlaying || !sleepTimerDidFinish)

private fun playPauseLabel(playback: PlaybackSnapshot): String =
    when {
        playback.playbackState == Player.STATE_BUFFERING -> "缓冲中"
        playback.playbackState == Player.STATE_ENDED -> "重新播放"
        playback.isPlaying -> "暂停"
        else -> "播放"
    }
