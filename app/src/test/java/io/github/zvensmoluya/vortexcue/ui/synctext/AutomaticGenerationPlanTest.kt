package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.core.model.ActiveTextSelection
import io.github.zvensmoluya.vortexcue.core.model.TimedTextLocator
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.data.DiscoveredTextCandidate
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
    fun `single trusted text is adopted before audio`() {
        val plan = planAutomaticGeneration(content(tracks = listOf(track("one"))))

        assertEquals(GenerationInput.ExistingTrack("one"), (plan as GenerationLaunchPlan.Submit).input)
        assertTrue(plan.allowAsrFallback)
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
    fun `materializable embedded text wins over strong external match`() {
        val embedded =
            track("embedded").copy(
                origin = TimedTextOrigin.Embedded,
                locator =
                    TimedTextLocator.Embedded(
                        embeddedTrackId = "embedded:0",
                        displayName = "embedded",
                    ),
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
            )
        val plan =
            planAutomaticGeneration(
                content(tracks = listOf(track("external"), embedded)),
            ) as GenerationLaunchPlan.Submit

        assertEquals(GenerationInput.EmbeddedTrack("embedded", "embedded:0"), plan.input)
    }

    @Test
    fun `unreadable text falls back to audio`() {
        val unreadable = track("bad").copy(readiness = TimedTextReadiness.ParseFailed)

        assertEquals(
            GenerationLaunchPlan.InspectAudio,
            planAutomaticGeneration(content(tracks = listOf(unreadable))),
        )
    }

    @Test
    fun `ambiguous filename candidate does not silently replace asr`() {
        val candidate = track("candidate").copy(matchBasis = TimedTextMatchBasis.FilenameCandidate)

        assertEquals(
            GenerationLaunchPlan.InspectAudio,
            planAutomaticGeneration(content(tracks = listOf(candidate))),
        )
    }

    @Test
    fun `strong discovered sidecar is materialized before generation submission`() {
        val details =
            content(
                candidates =
                    listOf(
                        DiscoveredTextCandidate(
                            candidateId = "candidate",
                            displayName = "item.srt",
                            mimeType = "application/x-subrip",
                            format = "srt",
                            language = null,
                            label = null,
                            origin = TimedTextOrigin.Sidecar,
                            roles = emptyList(),
                            matchBasis = TimedTextMatchBasis.ExactStem,
                            failureMessage = null,
                        ),
                    ),
            )

        assertEquals(
            GenerationLaunchPlan.Submit(GenerationInput.DiscoveredCandidate("candidate"), allowAsrFallback = true),
            planAutomaticGeneration(details),
        )
    }

    private fun content(
        tracks: List<TimedTextTrack> = emptyList(),
        activeText: ActiveTextSelection = ActiveTextSelection(),
        candidates: List<DiscoveredTextCandidate> = emptyList(),
    ) =
        ContentDetails(
            contentId = "content",
            mediaLocators = emptyList(),
            activeMediaLocatorId = "",
            timedTextTracks = tracks,
            discoveredTextCandidates = candidates,
            activeText = activeText,
            subtitleOffsetMs = 0L,
            disposition = null,
        )

    private fun track(id: String) =
        TimedTextTrack(
            stableId = id,
            origin = TimedTextOrigin.Sidecar,
            locator = TimedTextLocator.Revision("revision-$id", "$id.srt"),
            matchBasis = TimedTextMatchBasis.ExactStem,
            readiness = TimedTextReadiness.Ready,
        )
}
