package io.github.zvensmoluya.vortexcue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(
    draft: SessionDraft,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    playback: PlaybackSnapshot,
    displayProfile: DisplayProfile,
    onPickMedia: () -> Unit,
    onPickSubtitle: () -> Unit,
    onRemoveSubtitle: () -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    onDisplayProfileChanged: (DisplayProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var settingsVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppBackground)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Header(
            settingsVisible = settingsVisible,
            onToggleSettings = { settingsVisible = !settingsVisible },
        )

        if (settingsVisible) {
            DisplaySettings(
                profile = displayProfile,
                onProfileChanged = onDisplayProfileChanged,
            )
        } else {
            HomeContent(
                draft = draft,
                activeSession = activeSession,
                lastSession = lastSession,
                playback = playback,
                onPickMedia = onPickMedia,
                onPickSubtitle = onPickSubtitle,
                onRemoveSubtitle = onRemoveSubtitle,
                onClearDraft = onClearDraft,
                onApplyDraft = onApplyDraft,
                onContinue = onContinue,
                onPlayPause = onPlayPause,
            )
        }
    }
}

@Composable
private fun Header(
    settingsVisible: Boolean,
    onToggleSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (settingsVisible) "显示" else "荧荧",
                color = AppPrimaryText,
                fontSize = 25.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (settingsVisible) "微光校准与字幕外观" else "本地播放 · OLED 微光",
                color = AppMutedText,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onToggleSettings) {
            Text(
                text = if (settingsVisible) "完成" else "设置",
                color = AppSecondaryText,
            )
        }
    }
}

@Composable
private fun HomeContent(
    draft: SessionDraft,
    activeSession: ActiveSession?,
    lastSession: RestorableSession?,
    playback: PlaybackSnapshot,
    onPickMedia: () -> Unit,
    onPickSubtitle: () -> Unit,
    onRemoveSubtitle: () -> Unit,
    onClearDraft: () -> Unit,
    onApplyDraft: () -> Unit,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val resumableSession = activeSession ?: lastSession?.session
    val positionMs = if (activeSession != null) playback.positionMs else lastSession?.positionMs ?: 0L
    val durationMs = if (activeSession != null) playback.durationMs else lastSession?.durationMs ?: 0L
    val availability =
        if (activeSession != null) MediaAvailability.Ready else lastSession?.mediaAvailability

    resumableSession?.let { session ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(
                title = if (activeSession == null) "继续上次" else "正在播放",
                detail = if (playback.isPlaying && activeSession != null) "声音仍在继续" else null,
            )
            Text(
                text = session.media.displayName,
                color = AppPrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    "${formatTime(positionMs)} / ${formatTime(durationMs)}  ·  " +
                        (session.subtitle?.source?.displayName ?: "无字幕"),
                color = AppSecondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (availability) {
                MediaAvailability.Checking -> StatusText("正在验证播放文件…")
                is MediaAvailability.Unavailable -> StatusText(availability.message, AppError)
                else -> Unit
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietButton(
                    label =
                        when {
                            activeSession != null -> "返回播放器"
                            availability is MediaAvailability.Unavailable -> "重新定位"
                            else -> "继续播放"
                        },
                    onClick = onContinue,
                    enabled = availability != MediaAvailability.Checking,
                    modifier = Modifier.weight(1f),
                    emphasized = true,
                )
                if (activeSession != null) {
                    QuietButton(
                        label = if (playback.isPlaying) "暂停" else "播放",
                        onClick = onPlayPause,
                    )
                }
            }
        }
        Hairline()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(title = "打开内容", detail = "媒体库建立前保留单文件入口")
        Text(
            text = "选择本地音频或视频音轨，并按需关联同步字幕。",
            color = AppSecondaryText,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val mediaValue =
            when (val media = draft.media) {
                DraftMedia.None -> "尚未选择"
                is DraftMedia.Checking -> "正在验证 ${media.source.displayName}"
                is DraftMedia.Ready -> media.source.displayName
                is DraftMedia.Unavailable -> media.source.displayName
            }
        SourceRow(
            label = "播放内容",
            value = mediaValue,
            action = if (draft.media == DraftMedia.None) "选择" else "更换",
            onClick = onPickMedia,
        )
        if (draft.media is DraftMedia.Unavailable) {
            StatusText(draft.media.message, AppError)
        }

        Hairline()

        val subtitleValue =
            when (val subtitle = draft.subtitle) {
                DraftSubtitle.None -> "可选 · SRT / LRC / VTT / ASS"
                is DraftSubtitle.Loading -> "正在读取 ${subtitle.source.displayName}"
                is DraftSubtitle.Ready -> subtitle.subtitle.source.displayName
                is DraftSubtitle.Failed -> subtitle.source.displayName
            }
        SourceRow(
            label = "同步字幕",
            value = subtitleValue,
            action = if (draft.subtitle == DraftSubtitle.None) "选择" else "更换",
            onClick = onPickSubtitle,
        )
        when (val subtitle = draft.subtitle) {
            is DraftSubtitle.Failed -> StatusText(subtitle.message, AppError)
            is DraftSubtitle.Ready ->
                StatusText("已读取 ${subtitle.subtitle.timeline.cues.size} 条字幕")
            else -> Unit
        }
        if (draft.subtitle != DraftSubtitle.None) {
            TextButton(onClick = onRemoveSubtitle) {
                Text("移除字幕", color = AppMutedText)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClearDraft) {
            Text("清空", color = AppMutedText)
        }
        QuietButton(
            label =
                when {
                    draft.media is DraftMedia.Checking -> "正在验证"
                    draft.media is DraftMedia.Unavailable -> "需要重新定位"
                    draft.subtitle is DraftSubtitle.Failed -> "忽略字幕并播放"
                    activeSession == null -> "开始播放"
                    else -> "应用并播放"
                },
            onClick = onApplyDraft,
            enabled = draft.media is DraftMedia.Ready && draft.subtitle !is DraftSubtitle.Loading,
            modifier = Modifier.weight(1f),
            emphasized = true,
        )
    }

    Text(
        text = "内容只在本机读取",
        modifier = Modifier.fillMaxWidth(),
        color = AppMutedText,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DisplaySettings(
    profile: DisplayProfile,
    onProfileChanged: (DisplayProfile) -> Unit,
) {
    GlowPreview(profile)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(title = "预设", detail = profile.name)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DisplayProfile.Presets.forEach { preset ->
                QuietButton(
                    label = preset.name,
                    onClick = { onProfileChanged(preset) },
                    modifier = Modifier.weight(1f),
                    emphasized = preset == profile,
                )
            }
        }
    }

    Hairline()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(title = "微光校准", detail = "窗口与发光强度")
        DisplaySettingSlider(
            label = "环境亮度",
            valueLabel = windowBrightnessLabel(profile.glowWindowBrightness),
            value = profile.glowWindowBrightness,
            valueRange = 0.01f..0.30f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowWindowBrightness = it,
                    ),
                )
            },
        )
        DisplaySettingSlider(
            label = "文字亮度",
            valueLabel = luminanceLabel(profile.glowSubtitleLevel, 4, 96),
            value = profile.glowSubtitleLevel.toFloat(),
            valueRange = 4f..96f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowSubtitleLevel = it.roundToInt(),
                    ),
                )
            },
        )
        DisplaySettingSlider(
            label = "控制亮度",
            valueLabel = luminanceLabel(profile.glowControlLevel, 24, 160),
            value = profile.glowControlLevel.toFloat(),
            valueRange = 24f..160f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowControlLevel = it.roundToInt(),
                    ),
                )
            },
        )
    }

    Hairline()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(title = "字幕外观", detail = "独立于屏幕亮度")
        DisplaySettingSlider(
            label = "字号",
            valueLabel = subtitleSizeLabel(profile.glowSubtitleSizeSp),
            value = profile.glowSubtitleSizeSp,
            valueRange = 18f..36f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowSubtitleSizeSp = it,
                    ),
                )
            },
        )
        DisplaySettingSlider(
            label = "垂直位置",
            valueLabel = verticalPositionLabel(profile.glowSubtitleVerticalBias),
            value = profile.glowSubtitleVerticalBias,
            valueRange = -0.60f..0.60f,
            onValueChange = {
                onProfileChanged(
                    profile.copy(
                        name = DisplayProfile.CustomName,
                        glowSubtitleVerticalBias = it,
                    ),
                )
            },
        )
    }
}

@Composable
private fun GlowPreview(profile: DisplayProfile) {
    val subtitleLevel = profile.glowSubtitleLevel.coerceIn(0, 255)
    val controlLevel = profile.glowControlLevel.coerceIn(0, 255)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .background(Color.Black),
    ) {
        Text(
            text = "声音继续，画面退场",
            modifier =
                Modifier
                    .align(
                        BiasAlignment(
                            0f,
                            profile.glowSubtitleVerticalBias.coerceIn(-0.60f, 0.60f),
                        ),
                    )
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            color = Color(subtitleLevel, subtitleLevel, subtitleLevel),
            fontSize = profile.glowSubtitleSizeSp.coerceIn(18f, 36f).sp,
            lineHeight = (profile.glowSubtitleSizeSp.coerceIn(18f, 36f) * 1.4f).sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "长按解锁",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            color = Color(controlLevel, controlLevel, controlLevel),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SectionHeading(
    title: String,
    detail: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = AppPrimaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        detail?.let {
            Text(text = it, color = AppMutedText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SourceRow(
    label: String,
    value: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = label, color = AppMutedText, fontSize = 11.sp)
            Text(
                text = value,
                color = AppPrimaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onClick) {
            Text(action, color = AppSecondaryText)
        }
    }
}

@Composable
private fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, if (emphasized) AppAccent else AppHairline),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = if (emphasized) AppAccent else AppPrimaryText,
                disabledContentColor = AppMutedText,
            ),
    ) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun DisplaySettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = AppSecondaryText, fontSize = 12.sp)
            Text(text = valueLabel, color = AppPrimaryText, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.semantics { contentDescription = "$label，$valueLabel" },
            colors =
                SliderDefaults.colors(
                    thumbColor = AppAccent,
                    activeTrackColor = AppSecondaryText,
                    inactiveTrackColor = AppHairline,
                ),
        )
    }
}

@Composable
private fun Hairline() {
    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppHairline))
}

@Composable
private fun StatusText(
    text: String,
    color: Color = AppMutedText,
) {
    Text(text = text, color = color, fontSize = 11.sp, lineHeight = 16.sp)
}

private fun windowBrightnessLabel(value: Float): String =
    when {
        value < 0.055f -> "极暗"
        value < 0.11f -> "柔和"
        value < 0.19f -> "清晰"
        else -> "明亮"
    }

private fun luminanceLabel(
    value: Int,
    minimum: Int,
    maximum: Int,
): String {
    val ratio = (value - minimum).toFloat() / (maximum - minimum).toFloat()
    return when {
        ratio < 0.25f -> "极暗"
        ratio < 0.55f -> "柔和"
        ratio < 0.82f -> "清晰"
        else -> "明亮"
    }
}

private fun subtitleSizeLabel(value: Float): String =
    when {
        value < 22f -> "紧凑"
        value < 27f -> "标准"
        value < 32f -> "较大"
        else -> "最大"
    }

private fun verticalPositionLabel(bias: Float): String {
    val normalized = bias.coerceIn(-0.60f, 0.60f)
    if (normalized.absoluteValue < 0.04f) return "居中"
    val percent = (normalized.absoluteValue * 100).roundToInt()
    return if (normalized < 0f) "偏上 $percent%" else "偏下 $percent%"
}
