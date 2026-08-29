package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.synctext.GenerationNetworkWaitReason
import io.github.zvensmoluya.vortexcue.synctext.GenerationRuntimePhase
import io.github.zvensmoluya.vortexcue.synctext.GenerationStage
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskKind
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitState
import java.util.Locale

internal data class SyncTextProgressPresentation(
    val label: String,
    val detail: String? = null,
    val completedUnitCount: Int = 0,
    val totalUnitCount: Int = 0,
    val activeUnitCount: Int = 0,
    val unitStates: List<GenerationWorkUnitState> = emptyList(),
    val streamedTokens: String? = null,
    val streamActivityRevision: Long? = null,
    val showWorkUnitRing: Boolean = true,
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
        return SyncTextProgressPresentation(
            label = "正在启动$operation",
            showWorkUnitRing = launch.kind == SyncTextLaunchKind.Generation,
        )
    }
    val activeTask = task ?: return null
    if (activeTask.state !in setOf(GenerationTaskState.Waiting, GenerationTaskState.Running)) return null

    val workUnits = activeTask.workUnits
    val completed = workUnits.count { it.state == GenerationWorkUnitState.Completed }
    val active = workUnits.count { it.state == GenerationWorkUnitState.Running }
    val total = workUnits.size
    val phases = workUnits.mapNotNull { it.runtimePhase }
    val (label, detail) =
        when {
            activeTask.networkWaitReason == GenerationNetworkWaitReason.MeteredPermissionRequired ->
                "等待计费网络许可" to null
            activeTask.networkWaitReason == GenerationNetworkWaitReason.Unavailable -> "等待网络" to null
            activeTask.blockedByScheme -> "等待可用配置" to null
            activeTask.state == GenerationTaskState.Waiting -> {
                val operation = if (activeTask.kind == GenerationTaskKind.SourceGeneration) "语音识别" else "翻译"
                "已排队 · 等待$operation" to
                    (activeTask.queuePosition.takeIf { it > 0 }?.let { "前方 $it 项任务" } ?: "等待内核接手")
            }
            activeTask.kind == GenerationTaskKind.SourceGeneration ->
                sourceRuntimeFact(activeTask.runtimePhase, phases, active, completed, total)
            else -> translationRuntimeFact(activeTask, phases, completed, total)
        }
    return SyncTextProgressPresentation(
        label = label,
        detail = detail,
        completedUnitCount = completed,
        totalUnitCount = total,
        activeUnitCount = active,
        unitStates = workUnits.map { it.state },
        streamedTokens = activeTask.streamedTokenEstimate?.takeIf { it > 0L }?.let(::formatStreamedTokens),
        streamActivityRevision = activeTask.streamActivityRevision,
        showWorkUnitRing = activeTask.kind == GenerationTaskKind.SourceGeneration,
    )
}

internal fun formatStreamedTokens(tokens: Long): String =
    when {
        tokens < 1_000L -> "≈$tokens tokens"
        tokens < 10_000L -> String.format(Locale.US, "≈%.1fk tokens", tokens / 1_000.0)
        else -> "≈${tokens / 1_000L}k tokens"
    }

private fun sourceRuntimeFact(
    taskPhase: GenerationRuntimePhase?,
    phases: List<GenerationRuntimePhase>,
    active: Int,
    completed: Int,
    total: Int,
): Pair<String, String?> =
    when {
        GenerationRuntimePhase.RetryingAsr in phases -> "识别请求正在重试" to workUnitDetail(completed, total)
        GenerationRuntimePhase.AwaitingAsr in phases ->
            "等待识别结果" to listOfNotNull(active.takeIf { it > 0 }?.let { "$it 段进行中" }, workUnitDetail(completed, total)).joinToString(" · ")
        GenerationRuntimePhase.ExtractingAudio in phases -> "正在提取音频" to workUnitDetail(completed, total)
        GenerationRuntimePhase.Splitting in phases -> "正在缩小音频分段" to workUnitDetail(completed, total)
        taskPhase == GenerationRuntimePhase.MaterializingText -> "正在准备源文字" to null
        taskPhase == GenerationRuntimePhase.Publishing -> "正在发布识别结果" to workUnitDetail(completed, total)
        taskPhase == GenerationRuntimePhase.Planning || total == 0 -> "正在规划音频" to null
        else -> "正在整理识别结果" to workUnitDetail(completed, total)
    }

private fun translationRuntimeFact(
    task: GenerationTaskSummary,
    phases: List<GenerationRuntimePhase>,
    completed: Int,
    total: Int,
): Pair<String, String?> {
    val observed = task.workUnits.sumOf { it.observedItemCount ?: 0 }
    val expected = task.workUnits.sumOf { it.expectedItemCount ?: 0 }
    val tokens = task.streamedTokenEstimate?.takeIf { it > 0L }?.let(::formatStreamedTokens)
    val batches = total.takeIf { it > 1 }?.let { "$completed/$it 批" }
    return when {
        GenerationRuntimePhase.RetryingTranslation in phases -> "正在重试" to compactDetail(tokens, batches)
        GenerationRuntimePhase.ReceivingTranslation in phases ->
            "正在接收译文" to
                compactDetail(expected.takeIf { it > 0 }?.let { "$observed/$it" }, tokens, batches)
        GenerationRuntimePhase.ValidatingTranslation in phases -> "正在校验译文" to compactDetail(tokens, batches)
        GenerationRuntimePhase.ModelReasoning in phases ->
            "模型正在处理" to compactDetail(tokens, batches)
        GenerationRuntimePhase.AwaitingTranslation in phases ->
            "等待模型返回" to compactDetail(tokens, batches)
        GenerationRuntimePhase.Splitting in phases -> "正在缩小翻译批次" to compactDetail(tokens, batches)
        task.runtimePhase == GenerationRuntimePhase.Publishing -> "正在发布翻译结果" to compactDetail(tokens, batches)
        task.runtimePhase == GenerationRuntimePhase.PreparingTranslation || total == 0 ->
            "正在准备翻译" to compactDetail(tokens, batches)
        else -> task.stage.progressLabel(task.kind) to compactDetail(tokens, batches)
    }
}

private fun compactDetail(vararg values: String?): String? =
    values.filterNotNull().takeIf(List<String>::isNotEmpty)?.joinToString(" · ")

private fun workUnitDetail(completed: Int, total: Int): String? =
    total.takeIf { it > 1 }?.let { "已完成 $completed/$it" }

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
