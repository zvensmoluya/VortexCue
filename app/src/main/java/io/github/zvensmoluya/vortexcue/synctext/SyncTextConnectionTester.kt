package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.File

class SyncTextConnectionTester(
    private val translation: TranslationNetworkGateway,
    private val asr: AsrEngine,
    private val policies: TranslationPolicyRegistry,
    private val createAsrProbeFile: () -> File,
) {
    suspend fun testTranslation(
        protocol: TranslationProtocol,
        endpoint: String,
        model: String,
        apiKey: String,
        targetLanguage: String,
        reasoningLevel: TranslationReasoningLevel = TranslationReasoningLevel.Off,
        userAgent: String? = null,
    ) {
        val policy = policies.resolve(targetLanguage)
        val plan =
            TranslationBatchPlanner()
                .plan(TranslationProbeCues, policy.systemPrompt + policy.stylePrompt.orEmpty())
                .single()
        TranslationBatchExecutor { systemPrompt, userPrompt ->
            translation.translate(
                protocol = protocol,
                request =
                    TranslationProviderRequest(
                        endpoint = endpoint,
                        model = model,
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        reasoningLevel = reasoningLevel,
                        userAgent = userAgent,
                    ),
            )
        }.execute(
            plan = plan,
            policy = policy,
            sourceLanguage = ProbeSourceLanguage,
            targetLanguage = targetLanguage,
            maxFollowUpCalls = ProbeMaxFollowUpCalls,
        )
    }

    suspend fun testAsr(
        profile: AsrProfile,
        endpoint: String,
        apiKey: String,
    ) {
        val probeFile = createAsrProbeFile()
        try {
            val result =
                asr.transcribe(
                    AsrRequest(
                        profile = profile,
                        apiKey = apiKey,
                        wavFile = probeFile,
                        window = AsrProbeWindow,
                        sourceLanguage = ProbeSourceLanguage,
                        endpoint = endpoint,
                    ),
                )
            ensureUsableAsrProbe(result, result.segments.isNotEmpty())
            val cues =
                when (profile.timelineStrategy) {
                    AsrTimelineStrategy.Segment -> AsrTimelineMerger.mergeSegments(result.segments)
                    AsrTimelineStrategy.Word -> AsrTimelineMerger.wordsToCues(result.segments)
                }
            ensureUsableAsrProbe(result, cues.isNotEmpty())
        } finally {
            probeFile.delete()
        }
    }

    private fun ensureUsableAsrProbe(result: AsrWindowResult, usable: Boolean) {
        if (usable) return
        throw AsrException(
            category = GenerationErrorCategory.Timeline,
            retryable = false,
            shouldSplit = false,
            httpStatus = result.httpStatus,
            rawResponse = result.rawResponse,
            message = "ASR probe returned no usable timestamped speech",
        )
    }

    companion object {
        private const val ProbeSourceLanguage = "en"
        private const val ProbeDurationMs = 1_500L
        private const val ProbeSampleRate = 16_000L
        private const val ProbeMaxFollowUpCalls = 1

        private val TranslationProbeCues =
            listOf(
                SubtitleCue(0L, 900L, "Good night.", stableId = "probe-1"),
                SubtitleCue(1_000L, 2_000L, "See you tomorrow.", stableId = "probe-2"),
            )

        private val AsrProbeWindow =
            AsrWindowPlan(
                windowId = "asr-probe",
                ordinal = 0,
                ownedStartMs = 0L,
                ownedEndMs = ProbeDurationMs,
                uploadStartMs = 0L,
                uploadEndMs = ProbeDurationMs,
                pcmStartFrame = 0L,
                pcmEndFrame = ProbeDurationMs * ProbeSampleRate / 1_000L,
                cutReason = "connection-probe",
                isLast = true,
            )
    }
}
