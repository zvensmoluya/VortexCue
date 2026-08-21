package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.synctext.GenerationNetworkWaitReason
import io.github.zvensmoluya.vortexcue.synctext.GenerationStage
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskKind
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary

internal data class SyncTextProgressPresentation(
    val label: String,
    val progress: Float?,
)

internal fun syncTextProgressPresentation(
    task: GenerationTaskSummary?,
    launch: SyncTextLaunchUiState?,
): SyncTextProgressPresentation? {
    if (launch != null) {
        val operation =
            when (launch.kind) {
                SyncTextLaunchKind.Generation -> "语音识别"
                SyncTextLaunchKind.Translation -> "翻译"
            }
        return SyncTextProgressPresentation("正在启动$operation", progress = null)
    }
    val activeTask = task ?: return null
    if (activeTask.state !in setOf(GenerationTaskState.Waiting, GenerationTaskState.Running)) return null

    val label =
        when {
            activeTask.networkWaitReason == GenerationNetworkWaitReason.MeteredPermissionRequired -> "等待计费网络许可"
            activeTask.networkWaitReason == GenerationNetworkWaitReason.Unavailable -> "等待网络"
            activeTask.blockedByScheme -> "等待可用配置"
            activeTask.state == GenerationTaskState.Waiting ->
                when (activeTask.kind) {
                    GenerationTaskKind.SourceGeneration -> "等待语音识别"
                    GenerationTaskKind.TranslationDerivation -> "等待翻译"
                }
            else -> activeTask.stage.progressLabel(activeTask.kind)
        }
    return SyncTextProgressPresentation(label, activeTask.progress.coerceIn(0f, 1f))
}

private fun GenerationStage.progressLabel(kind: GenerationTaskKind): String =
    when (this) {
        GenerationStage.Queued ->
            if (kind == GenerationTaskKind.SourceGeneration) "正在准备语音识别" else "正在准备翻译"
        GenerationStage.MaterializingText -> "正在准备源文字"
        GenerationStage.DecodingAudio -> "正在准备音频"
        GenerationStage.RecognizingSpeech -> "正在语音识别"
        GenerationStage.SourcePublished -> "正在整理识别结果"
        GenerationStage.Translating -> "正在翻译"
        GenerationStage.Publishing -> "正在发布同步文字"
        GenerationStage.Completed -> "正在完成同步文字"
    }

internal fun generationProductStatus(
    task: GenerationTaskSummary?,
    hasGeneratedText: Boolean = false,
    hasPartialText: Boolean = false,
): String? =
    when {
        task?.needsAttention == true || task?.state == GenerationTaskState.NeedsAttention ->
            if (hasPartialText) "部分可用 · 失败" else "失败"
        task?.state == GenerationTaskState.Paused -> if (hasPartialText) "部分可用 · 已暂停" else "已暂停"
        task?.networkWaitReason == GenerationNetworkWaitReason.MeteredPermissionRequired -> "等待计费网络许可"
        task?.networkWaitReason == GenerationNetworkWaitReason.Unavailable -> "等待网络"
        task?.state == GenerationTaskState.Completed || hasGeneratedText -> "完成"
        task != null && task.state != GenerationTaskState.Deleted -> "准备中"
        else -> null
    }
