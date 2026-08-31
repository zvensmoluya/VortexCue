package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrPlanningTest {
    @Test
    fun `qwen uses 175 second windows with 3 second overlap`() {
        assertEquals(175_000L, AsrProfile.OpenRouterQwen3Asr17BV1.windowDurationMs)
        assertEquals(3_000L, AsrProfile.OpenRouterQwen3Asr17BV1.windowOverlapMs)
        assertEquals(300_000L, AsrProfile.OpenRouterWhisperV1.windowDurationMs)
        assertEquals(300_000L, AsrProfile.OpenAiWhisperV1.windowDurationMs)
    }

    @Test
    fun `fixed windows own the complete timeline exactly once`() {
        val durationMs = 897_000L
        val windows = FixedAsrWindowPlanner().plan(durationMs)

        assertEquals(4, windows.size)
        assertEquals(0L, windows.first().ownedStartMs)
        assertEquals(durationMs, windows.last().ownedEndMs)
        windows.zipWithNext().forEach { (first, second) ->
            assertEquals(first.ownedEndMs, second.ownedStartMs)
            assertTrue(first.uploadEndMs > second.uploadStartMs)
        }
        assertEquals(298_500L, windows[0].ownedEndMs)
        assertEquals(595_500L, windows[1].ownedEndMs)
        assertEquals(892_500L, windows[2].ownedEndMs)
    }

    @Test
    fun `retry split preserves ownership and bounded upload overlap`() {
        val planner = FixedAsrWindowPlanner()
        val parent = planner.plan(300_000L).single()

        val (left, right) = planner.split(parent, mediaDurationMs = 300_000L, childOrdinalStart = 4)

        assertEquals(parent.ownedStartMs, left.ownedStartMs)
        assertEquals(left.ownedEndMs, right.ownedStartMs)
        assertEquals(parent.ownedEndMs, right.ownedEndMs)
        assertTrue(left.uploadEndMs > right.uploadStartMs)
        assertEquals("retry_split_v1", left.cutReason)
        assertEquals(parent.windowId, right.parentWindowId)
        assertEquals(parent.plannerVersion, left.plannerVersion)
        assertEquals(parent.plannerVersion, right.plannerVersion)
    }

    @Test
    fun `new windows retain complete upload segments for seam reconciliation`() {
        val windows = FixedAsrWindowPlanner(targetUploadMs = 175_000L).plan(347_000L)
        val fromFirst =
            AsrTimelineMerger.windowSegments(
                windows[0],
                listOf(AsrTimestampedToken(170_000L, 175_000L, "boundary")),
            )
        val fromSecond =
            AsrTimelineMerger.windowSegments(
                windows[1],
                listOf(AsrTimestampedToken(0L, 6_000L, "boundary")),
            )

        assertEquals(AsrTimestampedToken(170_000L, 175_000L, "boundary"), fromFirst.single())
        assertEquals(AsrTimestampedToken(172_000L, 178_000L, "boundary"), fromSecond.single())
    }

    @Test
    fun `stitcher reconciles matching boundary segments without clipping`() {
        val windows = FixedAsrWindowPlanner(targetUploadMs = 175_000L).plan(347_000L)

        val stitched =
            AsrOverlapStitcher.stitch(
                listOf(
                    AsrStitchWindow(
                        windows[0],
                        listOf(AsrTimestampedToken(170_000L, 175_000L, "Good night.")),
                    ),
                    AsrStitchWindow(
                        windows[1],
                        listOf(AsrTimestampedToken(172_000L, 178_000L, "Good night")),
                    ),
                ),
            )

        assertEquals(listOf(AsrTimestampedToken(170_000L, 178_000L, "Good night")), stitched)
    }

    @Test
    fun `stitcher is shared by every asr profile`() {
        AsrProfile.entries.forEach { profile ->
            val durationMs = profile.windowDurationMs * 2L - profile.windowOverlapMs
            val windows =
                FixedAsrWindowPlanner(
                    targetUploadMs = profile.windowDurationMs,
                    overlapMs = profile.windowOverlapMs,
                ).plan(durationMs)
            val boundaryMs = windows.first().ownedEndMs

            val stitched =
                AsrOverlapStitcher.stitch(
                    listOf(
                        AsrStitchWindow(
                            windows[0],
                            listOf(AsrTimestampedToken(boundaryMs - 2_000L, windows[0].uploadEndMs, profile.name)),
                        ),
                        AsrStitchWindow(
                            windows[1],
                            listOf(AsrTimestampedToken(windows[1].uploadStartMs, boundaryMs + 2_000L, profile.name)),
                        ),
                    ),
                )

            assertEquals(profile.name, 1, stitched.size)
            assertEquals(profile.name, boundaryMs - 2_000L, stitched.single().startMs)
            assertEquals(profile.name, boundaryMs + 2_000L, stitched.single().endMs)
        }
    }

    @Test
    fun `stitcher aligns one boundary segment against two adjacent segments`() {
        val windows = FixedAsrWindowPlanner(targetUploadMs = 175_000L).plan(347_000L)

        val stitched =
            AsrOverlapStitcher.stitch(
                listOf(
                    AsrStitchWindow(
                        windows[0],
                        listOf(AsrTimestampedToken(170_000L, 175_000L, "good night")),
                    ),
                    AsrStitchWindow(
                        windows[1],
                        listOf(
                            AsrTimestampedToken(172_000L, 174_000L, "good"),
                            AsrTimestampedToken(174_000L, 178_000L, "night"),
                        ),
                    ),
                ),
            )

        assertEquals(listOf("good", "night"), stitched.map(AsrTimestampedToken::text))
        assertEquals(170_000L, stitched.first().startMs)
        assertEquals(178_000L, stitched.last().endMs)
    }

    @Test
    fun `stitcher keeps repeated short utterances distinct`() {
        val windows = FixedAsrWindowPlanner(targetUploadMs = 175_000L).plan(347_000L)

        val stitched =
            AsrOverlapStitcher.stitch(
                listOf(
                    AsrStitchWindow(
                        windows[0],
                        listOf(
                            AsrTimestampedToken(172_000L, 173_000L, "嗯"),
                            AsrTimestampedToken(174_000L, 175_000L, "嗯"),
                        ),
                    ),
                    AsrStitchWindow(
                        windows[1],
                        listOf(
                            AsrTimestampedToken(172_100L, 173_100L, "嗯"),
                            AsrTimestampedToken(174_100L, 176_000L, "嗯"),
                        ),
                    ),
                ),
            )

        assertEquals(listOf("嗯", "嗯"), stitched.map(AsrTimestampedToken::text))
    }

    @Test
    fun `stitcher retains unmatched evidence from the non owning window`() {
        val windows = FixedAsrWindowPlanner(targetUploadMs = 175_000L).plan(347_000L)

        val stitched =
            AsrOverlapStitcher.stitch(
                listOf(
                    AsrStitchWindow(windows[0], emptyList()),
                    AsrStitchWindow(
                        windows[1],
                        listOf(AsrTimestampedToken(172_000L, 174_000L, "recovered")),
                    ),
                ),
            )

        assertEquals(listOf(AsrTimestampedToken(172_000L, 174_000L, "recovered")), stitched)
    }

    @Test
    fun `stitcher bounds incompatible qwen segments at the ownership seam`() {
        val windows = FixedAsrWindowPlanner(targetUploadMs = 175_000L).plan(330_000L)
        val longRightText =
            "シャウラ！どうしたおっさん？その調子で頼む。メイリもシャウラと協力してくれるか？"

        val stitched =
            AsrOverlapStitcher.stitch(
                listOf(
                    AsrStitchWindow(
                        windows[0],
                        listOf(AsrTimestampedToken(169_380L, 175_000L, "哎！")),
                    ),
                    AsrStitchWindow(
                        windows[1],
                        listOf(AsrTimestampedToken(172_000L, 199_300L, longRightText)),
                    ),
                ),
            )

        assertEquals(
            listOf(
                AsrTimestampedToken(169_380L, 173_500L, "哎！"),
                AsrTimestampedToken(173_500L, 199_300L, longRightText),
            ),
            stitched,
        )
    }

    @Test
    fun `stitcher still reconciles similarly timed paraphrases`() {
        val windows = FixedAsrWindowPlanner(targetUploadMs = 175_000L).plan(347_000L)

        val stitched =
            AsrOverlapStitcher.stitch(
                listOf(
                    AsrStitchWindow(
                        windows[0],
                        listOf(AsrTimestampedToken(171_000L, 175_000L, "Let's go now")),
                    ),
                    AsrStitchWindow(
                        windows[1],
                        listOf(AsrTimestampedToken(172_000L, 176_000L, "We should leave")),
                    ),
                ),
            )

        assertEquals(listOf(AsrTimestampedToken(171_000L, 176_000L, "Let's go now")), stitched)
    }

    @Test
    fun `stitcher merges a shared whisper suffix and prefix without repeating text`() {
        val windows = FixedAsrWindowPlanner().plan(330_000L)
        val shared = "相手が暴食となれば逃す手はない"
        val leftText = "一度も見たことのない相手です。$shared。"
        val rightText = "${shared}同感ね"

        val stitched =
            AsrOverlapStitcher.stitch(
                listOf(
                    AsrStitchWindow(
                        windows[0],
                        listOf(AsrTimestampedToken(290_652L, 300_000L, leftText)),
                    ),
                    AsrStitchWindow(
                        windows[1],
                        listOf(AsrTimestampedToken(297_000L, 301_140L, rightText)),
                    ),
                ),
            )

        assertEquals(
            listOf(AsrTimestampedToken(290_652L, 301_140L, "${leftText}同感ね")),
            stitched,
        )
    }

    @Test
    fun `word cue joining keeps CJK tokens unspaced but separates Latin words`() {
        val cues =
            AsrTimelineMerger.wordsToCues(
                listOf(
                    AsrTimestampedToken(0L, 200L, "ご"),
                    AsrTimestampedToken(200L, 400L, "主人様"),
                    AsrTimestampedToken(2_000L, 2_200L, "good"),
                    AsrTimestampedToken(2_200L, 2_400L, "night"),
                ),
            )

        assertEquals("ご主人様", cues.first().text)
        assertEquals("good night", cues.last().text)
    }

    @Test
    fun `empty provider timelines remain valid empty work units`() {
        assertTrue(AsrTimelineMerger.segmentsToCues(emptyList()).isEmpty())
        assertTrue(AsrTimelineMerger.mergeSegments(emptyList()).isEmpty())
        assertTrue(AsrTimelineMerger.wordsToCues(emptyList()).isEmpty())
    }

    @Test
    fun `legacy windows remain clipped to their checkpoint ownership range`() {
        val windows = FixedAsrWindowPlanner().plan(597_000L)
        val firstBoundary = windows[0].ownedEndMs
        val firstCrossing =
            AsrTimelineMerger.windowSegments(
                windows[0].copy(plannerVersion = FixedAsrWindowPlanner.LegacyPlannerVersion),
                listOf(
                    AsrTimestampedToken(
                        startMs = firstBoundary - windows[0].uploadStartMs - 1_000L,
                        endMs = firstBoundary - windows[0].uploadStartMs + 500L,
                        text = "first",
                    ),
                ),
            ).single()
        val secondBoundary = windows[1].ownedStartMs
        val secondCrossing =
            AsrTimelineMerger.windowSegments(
                windows[1].copy(plannerVersion = FixedAsrWindowPlanner.LegacyPlannerVersion),
                listOf(
                    AsrTimestampedToken(
                        startMs = secondBoundary - windows[1].uploadStartMs - 500L,
                        endMs = secondBoundary - windows[1].uploadStartMs + 1_000L,
                        text = "second",
                    ),
                ),
            ).single()

        assertEquals(firstBoundary - 1_000L, firstCrossing.startMs)
        assertEquals(firstBoundary, firstCrossing.endMs)
        assertEquals(secondBoundary, secondCrossing.startMs)
        assertEquals(secondBoundary + 1_000L, secondCrossing.endMs)
    }

    @Test
    fun `encoded budget enforces byte and request concurrency then degrades to serial`() = runBlocking {
        val budget = EncodedAudioBudget(maxBytes = 32L, maxParallelRequests = 2)
        val first = budget.acquire(20L)
        val waitingForBytes = async { budget.acquire(20L) }
        yield()
        assertFalse(waitingForBytes.isCompleted)

        first.release()
        val second = waitingForBytes.await()
        assertEquals(20L, budget.snapshot().inFlightBytes)
        second.release()

        budget.degradeToSerial()
        val serialFirst = budget.acquire(1L)
        val serialWaiting = async { budget.acquire(1L) }
        yield()
        assertFalse(serialWaiting.isCompleted)
        serialFirst.release()
        serialWaiting.await().release()
        assertEquals(0L, budget.snapshot().inFlightBytes)
        assertEquals(1, budget.snapshot().parallelLimit)
    }
}
