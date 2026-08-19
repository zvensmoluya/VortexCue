package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary

internal fun generationProductStatus(
    task: GenerationTaskSummary?,
    hasGeneratedText: Boolean = false,
    hasPartialText: Boolean = false,
): String? =
    when {
        task?.needsAttention == true || task?.state == GenerationTaskState.NeedsAttention ->
            if (hasPartialText) "部分可用 · 失败" else "失败"
        task?.state == GenerationTaskState.Paused -> if (hasPartialText) "部分可用 · 已暂停" else "已暂停"
        task?.state == GenerationTaskState.Completed || hasGeneratedText -> "完成"
        task != null && task.state != GenerationTaskState.Deleted -> "准备中"
        else -> null
    }
