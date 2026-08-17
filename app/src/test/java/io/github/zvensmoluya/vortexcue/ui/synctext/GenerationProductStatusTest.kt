package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.synctext.GenerationStage
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationProductStatusTest {
    @Test
    fun mapsTechnicalTaskStatesToThreeProductStates() {
        assertEquals("准备中", generationProductStatus(task(GenerationTaskState.Waiting)))
        assertEquals("准备中", generationProductStatus(task(GenerationTaskState.Running)))
        assertEquals("完成", generationProductStatus(task(GenerationTaskState.Completed)))
        assertEquals("失败", generationProductStatus(task(GenerationTaskState.NeedsAttention)))
    }

    @Test
    fun generatedResultWinsWhenNoActiveTaskRemains() {
        assertEquals("完成", generationProductStatus(null, hasGeneratedText = true))
        assertNull(generationProductStatus(null, hasGeneratedText = false))
        assertNull(generationProductStatus(task(GenerationTaskState.Deleted)))
    }

    private fun task(state: GenerationTaskState) =
        GenerationTaskSummary(
            taskId = "task",
            contentId = "content",
            schemeId = "config",
            queuePosition = 0,
            state = state,
            stage = GenerationStage.Queued,
            progress = 0f,
            targetLanguage = "zh-CN",
            waitingForNetwork = false,
            paused = state == GenerationTaskState.Paused,
            needsAttention = state == GenerationTaskState.NeedsAttention,
            blockedByScheme = false,
        )
}
