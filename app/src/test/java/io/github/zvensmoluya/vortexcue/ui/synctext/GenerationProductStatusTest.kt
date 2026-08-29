package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.synctext.GenerationStage
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskKind
import io.github.zvensmoluya.vortexcue.synctext.GenerationNetworkWaitReason
import io.github.zvensmoluya.vortexcue.synctext.GenerationRuntimePhase
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitKind
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitState
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitSummary
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
            SyncTextProgressPresentation("正在启动语音识别"),
            syncTextProgressPresentation(
                task = null,
                launch = SyncTextLaunchUiState("content", SyncTextLaunchKind.Generation),
            ),
        )
        assertEquals(
            SyncTextProgressPresentation(
                label = "正在启动翻译",
                showWorkUnitRing = false,
            ),
            syncTextProgressPresentation(
                task = task(GenerationTaskState.Completed),
                launch = SyncTextLaunchUiState("content", SyncTextLaunchKind.Translation),
            ),
        )
    }

    @Test
    fun presentsCommittedUnitsAndWaitReasonWithoutPercentages() {
        val runningUnit =
            GenerationWorkUnitSummary(
                workUnitId = "window-1",
                kind = GenerationWorkUnitKind.AsrWindow,
                ordinal = 0,
                state = GenerationWorkUnitState.Running,
                runtimePhase = GenerationRuntimePhase.AwaitingAsr,
            )
        assertEquals(
            SyncTextProgressPresentation(
                label = "等待识别结果",
                detail = "1 段进行中",
                completedUnitCount = 0,
                totalUnitCount = 1,
                activeUnitCount = 1,
                unitStates = listOf(GenerationWorkUnitState.Running),
            ),
            syncTextProgressPresentation(
                task(
                    state = GenerationTaskState.Running,
                    stage = GenerationStage.RecognizingSpeech,
                    workUnits = listOf(runningUnit),
                ),
                launch = null,
            ),
        )
        assertEquals(
            SyncTextProgressPresentation("等待网络"),
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
    fun presentsObservedTranslationRowsAndDurableStreamActivity() {
        val receiving =
            GenerationWorkUnitSummary(
                workUnitId = "batch-1",
                kind = GenerationWorkUnitKind.TranslationBatch,
                ordinal = 0,
                state = GenerationWorkUnitState.Running,
                runtimePhase = GenerationRuntimePhase.ReceivingTranslation,
                observedItemCount = 17,
                expectedItemCount = 60,
            )
        assertEquals(
            SyncTextProgressPresentation(
                label = "正在接收译文",
                detail = "17/60 · ≈3.1k tokens",
                completedUnitCount = 0,
                totalUnitCount = 1,
                activeUnitCount = 1,
                unitStates = listOf(GenerationWorkUnitState.Running),
                streamedTokens = "≈3.1k tokens",
                streamActivityRevision = 12_345L,
                showWorkUnitRing = false,
            ),
            syncTextProgressPresentation(
                task(
                    state = GenerationTaskState.Running,
                    stage = GenerationStage.Translating,
                    kind = GenerationTaskKind.TranslationDerivation,
                    workUnits = listOf(receiving),
                    streamedTokenEstimate = 3_120L,
                    streamActivityRevision = 12_345L,
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
        kind: GenerationTaskKind = GenerationTaskKind.SourceGeneration,
        workUnits: List<GenerationWorkUnitSummary> = emptyList(),
        streamedTokenEstimate: Long? = null,
        streamActivityRevision: Long? = null,
    ) =
        GenerationTaskSummary(
            taskId = "task",
            contentId = "content",
            schemeId = "config",
            kind = kind,
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
            streamActivityRevision = streamActivityRevision,
            workUnits = workUnits,
        )
}
