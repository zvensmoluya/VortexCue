package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.text.SourcePatchOperation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in paid check of the real source-preparation prompt and strict response protocol. */
class LiveSourcePreparationTest {
    @Test
    fun `live model prepares a suspicious whisper timeline`() = runBlocking {
        val config = LiveProviderProbeConfig.fromEnvironment()
        assumeTrue("Set VORTEX_LIVE_TEST=1 to run the paid source-preparation test", config.enabled)
        val apiKey = requireNotNull(config.translationApiKey) {
            "VORTEX_LIVE_TRANSLATION_API_KEY is required"
        }
        val endpoint = requireNotNull(config.translationEndpoint) {
            "VORTEX_LIVE_TRANSLATION_ENDPOINT is required"
        }
        val model = requireNotNull(config.translationModel) {
            "VORTEX_LIVE_TRANSLATION_MODEL is required"
        }
        val source = suspiciousTimeline()
        val signals = SourcePreparationSignalDetector.detect(source)
        val policy =
            SourcePreparationPolicyRegistry.forTesting(::loadLiveSourcePreparationPrompt).resolve()
        val httpClientWrapper = SyncTextHttpClient("live-source-preparation")
        val httpClient = httpClientWrapper.client
        try {
            val gateway =
                TranslationNetworkGateway(
                    TranslationAdapterRegistry(
                        defaultTranslationAdapters(httpClientWrapper, TranslationEndpointResolver()),
                    ),
                    streamResponses = true,
                )
            val response =
                gateway.translate(
                    protocol = config.translationProtocol,
                    request =
                        TranslationProviderRequest(
                            endpoint = endpoint,
                            model = model,
                            apiKey = apiKey,
                            systemPrompt = policy.systemPrompt,
                            userPrompt =
                                SourcePreparationPromptRenderer.render(
                                    source,
                                    sourceLanguage = "ja",
                                    flags = signals.flags,
                                ),
                            reasoningLevel = TranslationReasoningLevel.Off,
                            userAgent = config.userAgent,
                        ),
                )
            val modelOutput =
                SourcePreparationResponseParser.parse(
                    response = response.text,
                    sourceCues = source,
                    policyDropIds = signals.policyPatches.mapTo(mutableSetOf()) { it.cueId },
                )
            val published = SourcePatchApplier.apply(source, signals.policyPatches + modelOutput.patches)

            assertEquals(listOf("cue-4", "cue-9"), signals.policyPatches.map { it.cueId })
            assertTrue(
                modelOutput.patches.any {
                    it.cueId == "cue-8" && it.operation == SourcePatchOperation.Drop
                },
            )
            assertTrue(published.none { it.stableId in setOf("cue-4", "cue-8", "cue-9") })
            assertTrue(modelOutput.sourceContext.styleProfile != io.github.zvensmoluya.vortexcue.text.SourceStyleProfile())
        } finally {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun suspiciousTimeline() =
        listOf(
            SubtitleCue(0L, 1_300L, "ねえ、まだ起きてる？", "cue-1"),
            SubtitleCue(1_600L, 2_800L, "うん、どうしたの？", "cue-2"),
            SubtitleCue(3_000L, 4_200L, "少し話したくて。", "cue-3"),
            SubtitleCue(4_500L, 5_200L, "*Sigh*", "cue-4"),
            SubtitleCue(5_500L, 6_700L, "明日のこと？", "cue-5"),
            SubtitleCue(7_000L, 8_200L, "そう。ちょっと不安なんだ。", "cue-6"),
            SubtitleCue(8_500L, 9_600L, "大丈夫、そばにいるよ。", "cue-7"),
            SubtitleCue(9_700L, 10_800L, "ご視聴ありがとうございました。", "cue-8"),
            SubtitleCue(11_200L, 12_500L, "*Humming*", "cue-9"),
        )
}
