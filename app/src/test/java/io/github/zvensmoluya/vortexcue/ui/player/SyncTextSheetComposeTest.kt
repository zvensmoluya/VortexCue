package io.github.zvensmoluya.vortexcue.ui.player

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zvensmoluya.vortexcue.core.model.ActiveTextSelection
import io.github.zvensmoluya.vortexcue.core.model.LoadedGeneratedText
import io.github.zvensmoluya.vortexcue.core.model.MediaLocator
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.PlayableContentUnit
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextLocator
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.mediaIdentityKey
import io.github.zvensmoluya.vortexcue.core.model.stableMediaLocatorId
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleTimeline
import io.github.zvensmoluya.vortexcue.synctext.GeneratedResultCompleteness
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncTextSheetComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overviewKeepsTechnicalTrackDetailsOutOfThePrimarySurface() {
        compose.setContent {
            VortexCueTheme {
                SyncTextSheet(
                    contentUnit = contentUnit(),
                    onDismiss = {},
                    onSelectTrack = { _, _ -> },
                    onRestoreAutomatic = {},
                    onRestoreAllAutomatic = {},
                    onAddTrack = {},
                    generatedText = generatedText(),
                    subtitleOffsetMs = 0L,
                    onSubtitleOffsetChanged = {},
                    onPresentationModeChanged = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("同步文字").assertIsDisplayed()
        compose.onNodeWithText("显示方式").assertIsDisplayed()
        compose.onNodeWithText("译文").assertIsDisplayed()
        compose.onNodeWithText("双语").assertIsDisplayed()
        compose.onNodeWithText("主文字").assertIsDisplayed()
        compose.onNodeWithText("中文（翻译）").assertIsDisplayed()
        compose.onNodeWithText("第二文字").assertIsDisplayed()
        compose.onNodeWithText("日文（原文）").assertIsDisplayed()
        compose.onNodeWithText("时间校准").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新生成").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生成结果展示").assertDoesNotExist()
        compose.onNodeWithText("设为主轨").assertDoesNotExist()
        compose.onNodeWithText("设为次轨").assertDoesNotExist()
        compose.onNodeWithText("完成").assertDoesNotExist()
    }

    @Test
    fun regenerateStaysAOnePurposeActionInTheExistingSheet() {
        var requested = false
        compose.setContent {
            VortexCueTheme {
                SyncTextSheet(
                    contentUnit = contentUnit(),
                    onDismiss = {},
                    onSelectTrack = { _, _ -> },
                    onRestoreAutomatic = {},
                    onRestoreAllAutomatic = {},
                    onAddTrack = {},
                    generatedText = generatedText(),
                    subtitleOffsetMs = 0L,
                    onSubtitleOffsetChanged = {},
                    onPresentationModeChanged = { _, _ -> },
                    onRegenerate = { requested = true },
                )
            }
        }

        compose.onNodeWithText("重新生成").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, requested) }
        compose.onNodeWithText("模型").assertDoesNotExist()
        compose.onNodeWithText("版本管理").assertDoesNotExist()
    }

    @Test
    fun selectingPrimaryTextOpensOnePurposePicker() {
        var addedSlot: TextTrackSlot? = null
        compose.setContent {
            VortexCueTheme {
                SyncTextSheet(
                    contentUnit = contentUnit(),
                    onDismiss = {},
                    onSelectTrack = { _, _ -> },
                    onRestoreAutomatic = {},
                    onRestoreAllAutomatic = {},
                    onAddTrack = { addedSlot = it },
                    generatedText = generatedText(),
                    subtitleOffsetMs = 0L,
                    onSubtitleOffsetChanged = {},
                    onPresentationModeChanged = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("主文字").performClick()
        compose.onNodeWithText("选择主文字").assertIsDisplayed()
        compose.onNodeWithText("自动选择").assertIsDisplayed()
        compose.onNodeWithText("从文件添加文字").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(TextTrackSlot.Primary, addedSlot) }
    }

    @Test
    fun playerMenuContainsOnlyCurrentContentActions() {
        compose.setContent {
            VortexCueTheme {
                PlayerHeader(
                    title = "当前内容",
                    artist = null,
                    isCollected = true,
                    onBack = {},
                    onOpenSyncText = {},
                    onToggleCollected = {},
                )
            }
        }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("同步文字").assertIsDisplayed()
        compose.onNodeWithText("从“我的内容”移除").assertIsDisplayed()
        compose.onNodeWithText("添加其他内容").assertDoesNotExist()
        compose.onNodeWithText("重新选择媒体").assertDoesNotExist()
    }

    private fun contentUnit(): PlayableContentUnit {
        val media =
            MediaSourceRef(
                uri = Uri.parse("content://vortexcue/work"),
                displayName = "work.m4a",
                mimeType = "audio/mp4",
            )
        val mediaLocator =
            MediaLocator(
                stableId = stableMediaLocatorId(media.uri.toString(), media.sourceId),
                identityKey = mediaIdentityKey(media.uri.toString()),
                media = media,
            )
        val target = generatedTrack("target", "zh-CN", "生成译文 · zh-CN")
        val source = generatedTrack("source", "ja-JP", "生成原文 · ja-JP")
        return PlayableContentUnit(
            stableId = "content",
            mediaLocators = listOf(mediaLocator),
            activeMediaLocatorId = mediaLocator.stableId,
            timedTextTracks = listOf(target, source),
            activeText =
                ActiveTextSelection(
                    primaryTrackId = target.stableId,
                    secondaryTrackId = source.stableId,
                    primaryMode = TextSelectionMode.Automatic,
                    secondaryMode = TextSelectionMode.Automatic,
                ),
        )
    }

    private fun generatedTrack(
        id: String,
        language: String,
        label: String,
    ) =
        TimedTextTrack(
            stableId = id,
            origin = TimedTextOrigin.Generated,
            locator = TimedTextLocator.GeneratedResult(id, "$id.vtt"),
            format = "vtt",
            language = language,
            label = label,
            matchBasis = TimedTextMatchBasis.GeneratedResult,
            readiness = TimedTextReadiness.Ready,
        )

    private fun generatedText() =
        LoadedGeneratedText(
            resultId = "result",
            slotId = "slot",
            sourceTimeline = SubtitleTimeline(emptyList()),
            targetTimeline = SubtitleTimeline(emptyList()),
            presentationMode = TextPresentationMode.TargetOnly,
            completeness = GeneratedResultCompleteness.Final,
        )
}
