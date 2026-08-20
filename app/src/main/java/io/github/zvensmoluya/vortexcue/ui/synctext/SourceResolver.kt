package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.GenerationInput

internal sealed interface GenerationLaunchPlan {
    data class Submit(
        val input: GenerationInput,
        val allowAsrFallback: Boolean,
    ) : GenerationLaunchPlan

    data class Choose(val inputs: List<GenerationInput>) : GenerationLaunchPlan

    data class NeedsConfirmation(val message: String) : GenerationLaunchPlan

    data object InspectAudio : GenerationLaunchPlan
}

internal interface SourceResolver {
    fun resolve(content: ContentDetails): GenerationLaunchPlan
}

internal object TrustedSourceResolver : SourceResolver {
    override fun resolve(content: ContentDetails): GenerationLaunchPlan {
        val usable = content.availableTextTracks.filter(TimedTextTrack::isUsable)
        val selected = usable.firstOrNull { it.stableId == content.activeText.primaryTrackId }
        if (selected != null) {
            val input = selected.toGenerationInputOrNull()
                ?: return GenerationLaunchPlan.NeedsConfirmation("已选择的同步文字轨道无法读取，请重新选择")
            return GenerationLaunchPlan.Submit(
                input = input,
                allowAsrFallback =
                    content.activeText.primaryMode == TextSelectionMode.Automatic &&
                        selected.matchBasis !in
                        setOf(TimedTextMatchBasis.UserSelected, TimedTextMatchBasis.GeneratedResult),
            )
        }

        val associated =
            usable.filter {
                it.matchBasis in setOf(TimedTextMatchBasis.UserSelected, TimedTextMatchBasis.GeneratedResult)
            }
        resolveTier(associated, allowAsrFallback = false)?.let { return it }
        resolveTier(usable.filter { it.origin == TimedTextOrigin.Embedded }, allowAsrFallback = true)?.let { return it }
        val strongExternal =
            usable.filter {
                it.origin != TimedTextOrigin.Embedded &&
                    it.matchBasis in setOf(TimedTextMatchBasis.ExactStem, TimedTextMatchBasis.LanguageSuffix)
            }
        return resolveTier(strongExternal, allowAsrFallback = true) ?: GenerationLaunchPlan.InspectAudio
    }

    private fun resolveTier(
        tracks: List<TimedTextTrack>,
        allowAsrFallback: Boolean,
    ): GenerationLaunchPlan? {
        val inputs = tracks.mapNotNull(TimedTextTrack::toGenerationInputOrNull)
        return when (inputs.size) {
            0 -> GenerationLaunchPlan.InspectAudio
            1 -> GenerationLaunchPlan.Submit(inputs.single(), allowAsrFallback)
            else -> GenerationLaunchPlan.Choose(inputs)
        }.takeUnless { it == GenerationLaunchPlan.InspectAudio }
    }
}

internal fun planAutomaticGeneration(content: ContentDetails): GenerationLaunchPlan =
    TrustedSourceResolver.resolve(content)

private fun TimedTextTrack.isUsable(): Boolean =
    readiness !in
        setOf(
            TimedTextReadiness.Unreadable,
            TimedTextReadiness.ParseFailed,
            TimedTextReadiness.MissingArtifact,
        )

private fun TimedTextTrack.toGenerationInputOrNull(): GenerationInput? =
    locator.candidateId?.let(GenerationInput::DiscoveredCandidate) ?: when {
        origin == TimedTextOrigin.Embedded ->
            locator.embeddedTrackId?.let { GenerationInput.EmbeddedTrack(stableId, it) }
        else -> GenerationInput.ExistingTrack(stableId)
    }
