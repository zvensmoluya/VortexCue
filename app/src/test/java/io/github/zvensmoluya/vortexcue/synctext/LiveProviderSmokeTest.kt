package io.github.zvensmoluya.vortexcue.synctext

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in host-side smoke test for real provider contracts. It intentionally does not run as part
 * of the normal unit-test path unless VORTEX_LIVE_TEST=1 is present.
 */
class LiveProviderSmokeTest {
    @Test
    fun `live provider probe`() = runBlocking {
        val config = LiveProviderProbeConfig.fromEnvironment()
        assumeTrue(
            "Set VORTEX_LIVE_TEST=1 to run the paid provider smoke test",
            config.enabled,
        )
        require(config.asrApiKey != null || config.translationApiKey != null) {
            "Configure at least one live provider API key"
        }

        val httpClientWrapper = SyncTextHttpClient("live-probe")
        val httpClient = httpClientWrapper.client
        try {
            val executor = BoundedHttpExecutor(httpClient)
            val translation =
                TranslationNetworkGateway(
                    registry =
                        TranslationAdapterRegistry(
                            defaultTranslationAdapters(httpClientWrapper, TranslationEndpointResolver()),
                        ),
                    streamResponses = true,
                )
            val asr =
                AsrEngineRouter(
                    mapOf(
                        AsrProvider.OpenRouter to OpenRouterAsrEngine(executor, EncodedAudioBudget()),
                        AsrProvider.OpenAi to OpenAiAsrEngine(executor),
                    ),
                )
            val tester =
                SyncTextConnectionTester(
                    translation = translation,
                    asr = asr,
                    policies = TranslationPolicyRegistry.forTesting(::loadLivePromptAsset),
                    createAsrProbeFile = ::createAsrProbeFile,
                )

            config.asrApiKey?.let { apiKey ->
                tester.testAsr(
                    profile = config.asrProfile,
                    endpoint = config.asrEndpoint,
                    apiKey = apiKey,
                )
            }
            config.translationApiKey?.let { apiKey ->
                val endpoint = requireNotNull(config.translationEndpoint) {
                    "VORTEX_LIVE_TRANSLATION_ENDPOINT is required for translation"
                }
                val model = requireNotNull(config.translationModel) {
                    "VORTEX_LIVE_TRANSLATION_MODEL is required for translation"
                }
                tester.testTranslation(
                    protocol = config.translationProtocol,
                    endpoint = endpoint,
                    model = model,
                    apiKey = apiKey,
                    targetLanguage = config.targetLanguage,
                    userAgent = config.userAgent,
                )
            }
        } finally {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
        Unit
    }

    private fun createAsrProbeFile(): File {
        val source = requireNotNull(findRepositoryFile("app/src/main/res/raw/asr_probe_en_us.wav")) {
            "ASR probe fixture is missing"
        }
        return File.createTempFile("vortexcue-live-asr-", ".wav").also { output ->
            try {
                source.copyTo(output, overwrite = true)
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
        }
    }
}
