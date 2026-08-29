package io.github.zvensmoluya.vortexcue.ui.player

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.zvensmoluya.vortexcue.synctext.GenerationStage
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskKind
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.GenerationRuntimePhase
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitKind
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitState
import io.github.zvensmoluya.vortexcue.synctext.GenerationWorkUnitSummary
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextLaunchKind
import io.github.zvensmoluya.vortexcue.ui.synctext.SyncTextLaunchUiState
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncTextProgressStatusComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsIndeterminateTranslationLaunch() {
        compose.setContent {
            VortexCueTheme {
                SyncTextProgressStatus(
                    task = null,
                    launch = SyncTextLaunchUiState("content", SyncTextLaunchKind.Translation),
                )
            }
        }

        compose.onNodeWithText("正在启动翻译").assertIsDisplayed()
    }

    @Test
    fun showsSegmentedAsrActivityWithoutPercentage() {
        compose.setContent {
            VortexCueTheme {
                SyncTextProgressStatus(
                    task = runningTask(progress = 0.42f),
                    launch = null,
                )
            }
        }

        compose.onNodeWithText("等待识别结果").assertIsDisplayed()
        compose.onNodeWithText("1 段进行中").assertIsDisplayed()
        compose.onNodeWithText("0/1").assertDoesNotExist()
        compose.onNodeWithText("42%").assertDoesNotExist()
    }

    @Test
    fun showsObservedTranslationRowsAndDurableTokenActivity() {
        compose.setContent {
            VortexCueTheme {
                SyncTextProgressStatus(
                    task =
                        runningTask(
                            progress = 0.55f,
                            kind = GenerationTaskKind.TranslationDerivation,
                            stage = GenerationStage.Translating,
                            workUnits =
                                listOf(
                                    GenerationWorkUnitSummary(
                                        workUnitId = "batch-1",
                                        kind = GenerationWorkUnitKind.TranslationBatch,
                                        ordinal = 0,
                                        state = GenerationWorkUnitState.Running,
                                        runtimePhase = GenerationRuntimePhase.ReceivingTranslation,
                                        observedItemCount = 17,
                                        expectedItemCount = 60,
                                    ),
                                ),
                            streamedTokenEstimate = 3_120L,
                            streamActivityRevision = 12_345L,
                        ),
                    launch = null,
                )
            }
        }

        compose.onNodeWithText("正在接收译文").assertIsDisplayed()
        compose.onNodeWithText("17/60 · ≈3.1k tokens").assertIsDisplayed()
        compose.onNodeWithText("0/1").assertDoesNotExist()
    }

    @Test
    fun emptySubtitleStageOffersOneDirectGenerationAction() {
        var launches = 0
        compose.setContent {
            VortexCueTheme {
                SubtitleStage(
                    previousText = null,
                    primaryText = null,
                    secondaryText = null,
                    nextText = null,
                    hasPrimaryText = false,
                    hasSecondaryText = false,
                    hasGeneratedText = false,
                    hasPartialGeneratedText = false,
                    canTranslate = false,
                    task = null,
                    launch = null,
                    onGenerate = { launches += 1 },
                    onTranslate = {},
                    onCancel = {},
                    onAbort = {},
                    onRetry = {},
                    onAllowMetered = {},
                )
            }
        }

        compose.onNodeWithText("生成同步文字").assertIsDisplayed().performClick()
        compose.onNodeWithText("当前会话没有字幕").assertDoesNotExist()
        compose.onNodeWithText("管理").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, launches) }
    }

    @Test
    fun sourceSubtitleStageKeepsTranslationAsAContextAction() {
        var translations = 0
        compose.setContent {
            VortexCueTheme {
                SubtitleStage(
                    previousText = "上一句",
                    primaryText = "当前原文",
                    secondaryText = null,
                    nextText = "下一句",
                    hasPrimaryText = true,
                    hasSecondaryText = false,
                    hasGeneratedText = true,
                    hasPartialGeneratedText = false,
                    canTranslate = true,
                    task = runningTask(progress = 1f, state = GenerationTaskState.Completed),
                    launch = null,
                    onGenerate = {},
                    onTranslate = { translations += 1 },
                    onCancel = {},
                    onAbort = {},
                    onRetry = {},
                    onAllowMetered = {},
                )
            }
        }

        compose.onNodeWithText("当前原文").assertIsDisplayed()
        compose.onNodeWithContentDescription("翻译同步文字").assertIsDisplayed().performClick()
        compose.onNodeWithText("管理").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, translations) }
    }

    @Test
    fun launchProgressReplacesTheEmptyGenerationAction() {
        compose.setContent {
            VortexCueTheme {
                SubtitleStage(
                    previousText = null,
                    primaryText = null,
                    secondaryText = null,
                    nextText = null,
                    hasPrimaryText = false,
                    hasSecondaryText = false,
                    hasGeneratedText = false,
                    hasPartialGeneratedText = false,
                    canTranslate = false,
                    task = null,
                    launch = SyncTextLaunchUiState("content", SyncTextLaunchKind.Generation),
                    onGenerate = {},
                    onTranslate = {},
                    onCancel = {},
                    onAbort = {},
                    onRetry = {},
                    onAllowMetered = {},
                )
            }
        }

        compose.onNodeWithText("正在启动语音识别").assertIsDisplayed()
        compose.onNodeWithText("生成同步文字").assertDoesNotExist()
    }

    @Test
    fun runningTaskOffersPauseAndStopActions() {
        var pauses = 0
        var stops = 0
        compose.setContent {
            VortexCueTheme {
                SubtitleStage(
                    previousText = null,
                    primaryText = "当前原文",
                    secondaryText = null,
                    nextText = null,
                    hasPrimaryText = true,
                    hasSecondaryText = false,
                    hasGeneratedText = true,
                    hasPartialGeneratedText = false,
                    canTranslate = false,
                    task = runningTask(progress = 0.55f),
                    launch = null,
                    onGenerate = {},
                    onTranslate = {},
                    onCancel = { pauses += 1 },
                    onAbort = { stops += 1 },
                    onRetry = {},
                    onAllowMetered = {},
                )
            }
        }

        compose.onNodeWithContentDescription("暂停同步文字").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("停止生成").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(1, stops)
        }
    }

    private fun runningTask(
        progress: Float,
        state: GenerationTaskState = GenerationTaskState.Running,
        kind: GenerationTaskKind = GenerationTaskKind.SourceGeneration,
        stage: GenerationStage = GenerationStage.RecognizingSpeech,
        workUnits: List<GenerationWorkUnitSummary> =
            if (state == GenerationTaskState.Running) {
                listOf(
                    GenerationWorkUnitSummary(
                        workUnitId = "window-1",
                        kind = GenerationWorkUnitKind.AsrWindow,
                        ordinal = 0,
                        state = GenerationWorkUnitState.Running,
                        runtimePhase = GenerationRuntimePhase.AwaitingAsr,
                    ),
                )
            } else {
                emptyList()
            },
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
            targetLanguage = null,
            waitingForNetwork = false,
            paused = false,
            needsAttention = false,
            blockedByScheme = false,
            streamedTokenEstimate = streamedTokenEstimate,
            streamActivityRevision = streamActivityRevision,
            workUnits = workUnits,
        )
}
