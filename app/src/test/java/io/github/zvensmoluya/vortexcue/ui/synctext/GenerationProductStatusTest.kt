package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.synctext.GenerationStage
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskKind
import io.github.zvensmoluya.vortexcue.synctext.GenerationNetworkWaitReason
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

    @Test
    fun exposesNetworkWaitReason() {
        assertEquals(
            "等待计费网络许可",
            generationProductStatus(
                task(GenerationTaskState.Waiting, GenerationNetworkWaitReason.MeteredPermissionRequired),
                hasGeneratedText = true,
            ),
        )
        assertEquals(
            "等待网络",
            generationProductStatus(task(GenerationTaskState.Waiting, GenerationNetworkWaitReason.Unavailable)),
        )
    }

    private fun task(
        state: GenerationTaskState,
        networkWaitReason: GenerationNetworkWaitReason? = null,
    ) =
        GenerationTaskSummary(
            taskId = "task",
            contentId = "content",
            schemeId = "config",
            kind = GenerationTaskKind.SourceGeneration,
            queuePosition = 0,
            state = state,
            stage = GenerationStage.Queued,
            progress = 0f,
            targetLanguage = "zh-CN",
            waitingForNetwork = networkWaitReason != null,
            networkWaitReason = networkWaitReason,
            paused = state == GenerationTaskState.Paused,
            needsAttention = state == GenerationTaskState.NeedsAttention,
            blockedByScheme = false,
        )
}
