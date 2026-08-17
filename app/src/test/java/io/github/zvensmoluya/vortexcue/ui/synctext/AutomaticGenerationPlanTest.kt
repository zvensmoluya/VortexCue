package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.core.model.ActiveTextSelection
import io.github.zvensmoluya.vortexcue.core.model.TimedTextLocator
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.GenerationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticGenerationPlanTest {
    @Test
    fun `no usable text inspects audio`() {
        assertEquals(GenerationLaunchPlan.InspectAudio, planAutomaticGeneration(content()))
    }

    @Test
    fun `single existing text translates immediately`() {
        val plan = planAutomaticGeneration(content(tracks = listOf(track("one"))))

        assertEquals(GenerationInput.ExistingTrack("one"), (plan as GenerationLaunchPlan.Submit).input)
    }

    @Test
    fun `multiple text tracks ask only when no primary resolves ambiguity`() {
        val tracks = listOf(track("one"), track("two"))

        assertTrue(planAutomaticGeneration(content(tracks = tracks)) is GenerationLaunchPlan.Choose)
        val selected =
            planAutomaticGeneration(
                content(tracks = tracks, activeText = ActiveTextSelection(primaryTrackId = "two")),
            ) as GenerationLaunchPlan.Submit
        assertEquals(GenerationInput.ExistingTrack("two"), selected.input)
    }

    @Test
    fun `unreadable text falls back to audio`() {
        val unreadable = track("bad").copy(readiness = TimedTextReadiness.ParseFailed)

        assertEquals(
            GenerationLaunchPlan.InspectAudio,
            planAutomaticGeneration(content(tracks = listOf(unreadable))),
        )
    }

    private fun content(
        tracks: List<TimedTextTrack> = emptyList(),
        activeText: ActiveTextSelection = ActiveTextSelection(),
    ) =
        ContentDetails(
            contentId = "content",
            mediaLocators = emptyList(),
            activeMediaLocatorId = "",
            timedTextTracks = tracks,
            activeText = activeText,
            subtitleOffsetMs = 0L,
            disposition = null,
        )

    private fun track(id: String) =
        TimedTextTrack(
            stableId = id,
            origin = TimedTextOrigin.Sidecar,
            locator = TimedTextLocator.External("content://text/$id", "$id.srt"),
            matchBasis = TimedTextMatchBasis.ExactStem,
            readiness = TimedTextReadiness.Ready,
        )
}
