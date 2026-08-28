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

    @Test
    fun presentsLaunchBeforeTaskProgressIsAvailable() {
        assertEquals(
            SyncTextProgressPresentation("正在启动语音识别", progress = null),
            syncTextProgressPresentation(
                task = null,
                launch = SyncTextLaunchUiState("content", SyncTextLaunchKind.Generation),
            ),
        )
        assertEquals(
            SyncTextProgressPresentation("正在启动翻译", progress = null),
            syncTextProgressPresentation(
                task = task(GenerationTaskState.Completed),
                launch = SyncTextLaunchUiState("content", SyncTextLaunchKind.Translation),
            ),
        )
    }

    @Test
    fun presentsDeterminateTaskProgressAndWaitReason() {
        assertEquals(
            SyncTextProgressPresentation("正在语音识别", progress = 0.42f),
            syncTextProgressPresentation(
                task(
                    state = GenerationTaskState.Running,
                    stage = GenerationStage.RecognizingSpeech,
                    progress = 0.42f,
                ),
                launch = null,
            ),
        )
        assertEquals(
            SyncTextProgressPresentation("等待网络", progress = 0.6f),
            syncTextProgressPresentation(
                task(
                    state = GenerationTaskState.Waiting,
                    networkWaitReason = GenerationNetworkWaitReason.Unavailable,
                    progress = 0.6f,
                ),
                launch = null,
            ),
        )
        assertNull(syncTextProgressPresentation(task(GenerationTaskState.Completed), launch = null))
    }

    @Test
    fun formatsTranslationStreamActivityWithoutChangingDurableProgress() {
        assertEquals(
            SyncTextProgressPresentation("正在翻译", progress = 0.55f, streamedTokens = "3.1k tokens"),
            syncTextProgressPresentation(
                task(
                    state = GenerationTaskState.Running,
                    stage = GenerationStage.Translating,
                    progress = 0.55f,
                    streamedTokenEstimate = 3_120L,
                ),
                launch = null,
            ),
        )
    }

    private fun task(
        state: GenerationTaskState,
        networkWaitReason: GenerationNetworkWaitReason? = null,
        stage: GenerationStage = GenerationStage.Queued,
        progress: Float = 0f,
        streamedTokenEstimate: Long? = null,
    ) =
        GenerationTaskSummary(
            taskId = "task",
            contentId = "content",
            schemeId = "config",
            kind = GenerationTaskKind.SourceGeneration,
            queuePosition = 0,
            state = state,
            stage = stage,
            progress = progress,
            targetLanguage = "zh-CN",
            waitingForNetwork = networkWaitReason != null,
            networkWaitReason = networkWaitReason,
            paused = state == GenerationTaskState.Paused,
            needsAttention = state == GenerationTaskState.NeedsAttention,
            blockedByScheme = false,
            streamedTokenEstimate = streamedTokenEstimate,
        )
}
