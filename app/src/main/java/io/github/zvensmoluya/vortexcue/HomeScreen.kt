package io.github.zvensmoluya.vortexcue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val WorkBackground = Color(0xFF080704)
internal val WorkSurface = Color(0xFF141109)
internal val WorkSurfaceRaised = Color(0xFF1B170D)
internal val WorkAccent = Color(0xFFFFCB4A)
internal val WorkPrimaryText = Color(0xFFF2E9D4)
internal val WorkSecondaryText = Color(0xFFA69B82)
internal val WorkMutedText = Color(0xFF746C5B)
internal val WorkError = Color(0xFFFF9A8F)

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
    val resumableSession = activeSession ?: lastSession?.session
    val resumablePositionMs =
        if (activeSession != null) playback.positionMs else lastSession?.positionMs ?: 0L
    val resumableDurationMs =
        if (activeSession != null) playback.durationMs else lastSession?.durationMs ?: 0L
    val resumableAvailability =
        if (activeSession != null) MediaAvailability.Ready else lastSession?.mediaAvailability

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(WorkBackground),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "荧荧",
                        color = WorkAccent,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "把声音留下，让画面退场",
                        color = WorkSecondaryText,
                        fontSize = 13.sp,
                    )
                }
                TextButton(onClick = { settingsVisible = !settingsVisible }) {
                    Text(
                        text = if (settingsVisible) "收起设置" else "显示设置",
                        color = WorkPrimaryText,
                    )
                }
            }

            resumableSession?.let { session ->
                SectionCard {
                    Text(
                        text =
                            when {
                                activeSession == null -> "继续上次"
                                playback.isPlaying -> "正在播放"
                                else -> "当前会话"
                            },
                        color = WorkAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = session.media.displayName,
                        color = WorkPrimaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            "${formatTime(resumablePositionMs)} / ${formatTime(resumableDurationMs)} · " +
                                (session.subtitle?.source?.displayName ?: "无字幕"),
                        color = WorkSecondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when (val availability = resumableAvailability) {
                        MediaAvailability.Checking ->
                            Text(
                                text = "正在验证播放文件…",
                                color = WorkSecondaryText,
                                fontSize = 12.sp,
                            )
                        is MediaAvailability.Unavailable ->
                            Text(
                                text = availability.message,
                                color = WorkError,
                                fontSize = 12.sp,
                            )
                        else -> Unit
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onContinue,
                            enabled = resumableAvailability != MediaAvailability.Checking,
                            modifier = Modifier.weight(1f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = WorkAccent,
                                    disabledContainerColor = WorkSurfaceRaised,
                                ),
                        ) {
                            Text(
                                text =
                                    when {
                                        activeSession != null -> "返回播放"
                                        resumableAvailability is MediaAvailability.Unavailable ->
                                            "重新定位并继续"
                                        else -> "继续播放"
                                    },
                                color =
                                    if (resumableAvailability == MediaAvailability.Checking) {
                                        WorkMutedText
                                    } else {
                                        Color.Black
                                    },
                            )
                        }
                        if (activeSession != null) {
                            OutlinedButton(
                                onClick = onPlayPause,
                                border = BorderStroke(1.dp, WorkMutedText),
                            ) {
                                Text(
                                    text = if (playback.isPlaying) "暂停" else "播放",
                                    color = WorkPrimaryText,
                                )
                            }
                        }
                    }
                }
            }

            if (settingsVisible) {
                SectionCard {
                    Text(
                        text = "微光显示",
                        color = WorkPrimaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "普通界面保持正常可读；以下预设只在微光模式生效。",
                        color = WorkSecondaryText,
                        fontSize = 12.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DisplayProfile.Presets.forEach { preset ->
                            val selected = preset == displayProfile
                            OutlinedButton(
                                onClick = { onDisplayProfileChanged(preset) },
                                modifier = Modifier.weight(1f),
                                border =
                                    BorderStroke(
                                        1.dp,
                                        if (selected) WorkAccent else WorkMutedText,
                                    ),
                                colors =
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor =
                                            if (selected) WorkAccent.copy(alpha = 0.12f) else Color.Transparent,
                                    ),
                            ) {
                                Text(
                                    text = preset.name,
                                    color = if (selected) WorkAccent else WorkSecondaryText,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "配置播放",
                    color = WorkPrimaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "播放源与字幕源彼此独立，按任意顺序选择。",
                    color = WorkSecondaryText,
                    fontSize = 13.sp,
                )
            }

            SectionCard {
                val mediaValue =
                    when (val media = draft.media) {
                        DraftMedia.None -> "尚未选择音频或视频"
                        is DraftMedia.Checking -> "正在验证 ${media.source.displayName}"
                        is DraftMedia.Ready -> media.source.displayName
                        is DraftMedia.Unavailable -> media.source.displayName
                    }
                SourceRow(
                    label = "播放源",
                    value = mediaValue,
                    action = if (draft.media == DraftMedia.None) "选择" else "更换",
                    onClick = onPickMedia,
                )

                if (draft.media is DraftMedia.Unavailable) {
                    Text(
                        text = draft.media.message,
                        color = WorkError,
                        fontSize = 12.sp,
                    )
                }

                Spacer(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF2B2517)),
                )

                val subtitleValue =
                    when (val subtitle = draft.subtitle) {
                        DraftSubtitle.None -> "可选：SRT、LRC、VTT 或 ASS"
                        is DraftSubtitle.Loading -> "正在读取 ${subtitle.source.displayName}"
                        is DraftSubtitle.Ready -> subtitle.subtitle.source.displayName
                        is DraftSubtitle.Failed -> subtitle.source.displayName
                    }
                SourceRow(
                    label = "字幕源",
                    value = subtitleValue,
                    action = if (draft.subtitle == DraftSubtitle.None) "选择" else "更换",
                    onClick = onPickSubtitle,
                )

                when (val subtitle = draft.subtitle) {
                    is DraftSubtitle.Failed ->
                        Text(
                            text = subtitle.message,
                            color = WorkError,
                            fontSize = 12.sp,
                        )
                    is DraftSubtitle.Ready ->
                        Text(
                            text = "已读取 ${subtitle.subtitle.timeline.cues.size} 条字幕",
                            color = WorkSecondaryText,
                            fontSize = 12.sp,
                        )
                    else -> Unit
                }

                if (draft.subtitle != DraftSubtitle.None) {
                    TextButton(onClick = onRemoveSubtitle) {
                        Text("移除字幕", color = WorkSecondaryText)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClearDraft) {
                        Text("清空", color = WorkMutedText)
                    }
                    Button(
                        onClick = onApplyDraft,
                        enabled =
                            draft.media is DraftMedia.Ready &&
                                draft.subtitle !is DraftSubtitle.Loading,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = WorkAccent,
                                disabledContainerColor = WorkSurfaceRaised,
                            ),
                    ) {
                        Text(
                            text =
                                when {
                                    draft.media is DraftMedia.Checking -> "正在验证播放源"
                                    draft.media is DraftMedia.Unavailable -> "播放源需要重新定位"
                                    draft.subtitle is DraftSubtitle.Failed -> "忽略字幕并播放"
                                    activeSession == null -> "开始播放"
                                    else -> "应用并播放"
                                },
                            color =
                                if (
                                    draft.media is DraftMedia.Ready &&
                                        draft.subtitle !is DraftSubtitle.Loading
                                ) {
                                    Color.Black
                                } else {
                                    WorkMutedText
                                },
                        )
                    }
                }
            }

            Text(
                text = "本地文件只在设备上读取，不建立媒体库。",
                color = WorkMutedText,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WorkSurface,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFF282216)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text = label, color = WorkSecondaryText, fontSize = 12.sp)
            Text(
                text = value,
                color = WorkPrimaryText,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onClick) {
            Text(action, color = WorkAccent)
        }
    }
}
