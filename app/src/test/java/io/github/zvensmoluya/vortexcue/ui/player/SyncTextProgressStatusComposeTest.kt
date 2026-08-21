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
    fun showsDeterminateAsrProgress() {
        compose.setContent {
            VortexCueTheme {
                SyncTextProgressStatus(
                    task = runningTask(progress = 0.42f),
                    launch = null,
                )
            }
        }

        compose.onNodeWithText("正在语音识别").assertIsDisplayed()
        compose.onNodeWithText("42%").assertIsDisplayed()
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
                    onRetry = {},
                    onAllowMetered = {},
                )
            }
        }

        compose.onNodeWithText("正在启动语音识别").assertIsDisplayed()
        compose.onNodeWithText("生成同步文字").assertDoesNotExist()
    }

    private fun runningTask(
        progress: Float,
        state: GenerationTaskState = GenerationTaskState.Running,
    ) =
        GenerationTaskSummary(
            taskId = "task",
            contentId = "content",
            schemeId = "config",
            kind = GenerationTaskKind.SourceGeneration,
            queuePosition = 0,
            state = state,
            stage = GenerationStage.RecognizingSpeech,
            progress = progress,
            targetLanguage = null,
            waitingForNetwork = false,
            paused = false,
            needsAttention = false,
            blockedByScheme = false,
        )
}
