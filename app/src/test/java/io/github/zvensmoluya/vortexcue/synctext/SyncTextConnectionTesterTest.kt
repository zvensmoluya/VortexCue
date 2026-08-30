package io.github.zvensmoluya.vortexcue.synctext

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTextConnectionTesterTest {
    @Test
    fun `translation probe uses formal policy renderer streaming and validator`() = runBlocking {
        val adapter =
            RecordingTranslationAdapter(
                mutableListOf("[probe-1] 晚安。\n[probe-2] 明天见。"),
            )
        val tester = connectionTester(adapter)

        tester.testTranslation(
            protocol = TranslationProtocol.Responses,
            endpoint = "https://example.test/v1",
            model = "translation-model",
            apiKey = "secret-key",
            targetLanguage = "zh-CN",
            reasoningLevel = TranslationReasoningLevel.Medium,
            userAgent = "PrivateGatewayClient/1.0",
        )

        val request = adapter.requests.single()
        assertEquals(1, adapter.streamingCalls)
        assertEquals(0, adapter.nonStreamingCalls)
        assertEquals("https://example.test/v1", request.endpoint)
        assertEquals("translation-model", request.model)
        assertEquals("secret-key", request.apiKey)
        assertEquals(TranslationReasoningLevel.Medium, request.reasoningLevel)
        assertEquals("PrivateGatewayClient/1.0", request.userAgent)
        assertTrue(request.systemPrompt.contains("translate prepared source subtitles"))
        assertTrue(request.systemPrompt.contains("natural Simplified Chinese subtitles"))
        assertTrue(request.userPrompt.contains("MODE: TRANSLATE"))
        assertTrue(request.userPrompt.contains("[probe-1] Good night."))
        assertTrue(request.userPrompt.contains("[probe-2] See you tomorrow."))
    }

    @Test
    fun `translation probe allows only one format or repair follow-up`() = runBlocking {
        val adapter =
            RecordingTranslationAdapter(
                mutableListOf(
                    "",
                    "[probe-1] 晚安。",
                    "[probe-2] 明天见。",
                ),
            )
        val tester = connectionTester(adapter)

        val failure =
            runCatching {
                tester.testTranslation(
                    protocol = TranslationProtocol.Responses,
                    endpoint = "https://example.test/v1",
                    model = "translation-model",
                    apiKey = "secret-key",
                    targetLanguage = "zh-CN",
                )
            }.exceptionOrNull()

        assertTrue(failure is TranslationOutputException)
        assertEquals(2, adapter.streamingCalls)
        assertTrue(
            (failure as TranslationOutputException).issues.any {
                it.code == TranslationValidationIssueCode.MissingId && it.cueId == "probe-2"
            },
        )
    }

    @Test
    fun `asr probe uses formal engine and removes its temporary file`() = runBlocking {
        val probeFile = createProbeFile()
        var received: AsrRequest? = null
        val asr =
            object : AsrEngine {
                override suspend fun transcribe(request: AsrRequest): AsrWindowResult {
                    assertTrue(request.wavFile.isFile)
                    received = request
                    return asrResult(
                        listOf(
                            AsrTimestampedToken(100L, 350L, "Good"),
                            AsrTimestampedToken(400L, 900L, "night"),
                        ),
                    )
                }
            }
        val tester = connectionTester(asr = asr, createProbeFile = { probeFile })

        tester.testAsr(
            profile = AsrProfile.OpenRouterQwen3Asr17BV1,
            endpoint = "https://openrouter.ai/api/v1",
            apiKey = "asr-key",
        )

        assertNotNull(received)
        val request = requireNotNull(received)
        assertEquals(AsrProfile.OpenRouterQwen3Asr17BV1, request.profile)
        assertEquals("https://openrouter.ai/api/v1", request.endpoint)
        assertEquals("asr-key", request.apiKey)
        assertEquals("en", request.sourceLanguage)
        assertEquals(0L, request.window.ownedStartMs)
        assertEquals(1_500L, request.window.ownedEndMs)
        assertFalse(probeFile.exists())
    }

    @Test
    fun `asr probe rejects response without a usable timeline and cleans up`() = runBlocking {
        val probeFile = createProbeFile()
        val asr =
            object : AsrEngine {
                override suspend fun transcribe(request: AsrRequest): AsrWindowResult = asrResult(emptyList())
            }
        val tester = connectionTester(asr = asr, createProbeFile = { probeFile })

        val failure =
            runCatching {
                tester.testAsr(
                    profile = AsrProfile.OpenRouterWhisperV1,
                    endpoint = "https://openrouter.ai/api/v1",
                    apiKey = "asr-key",
                )
            }.exceptionOrNull()

        assertTrue(failure is AsrException)
        assertEquals(GenerationErrorCategory.Timeline, (failure as AsrException).category)
        assertFalse(probeFile.exists())
    }

    private fun connectionTester(
        adapter: RecordingTranslationAdapter = RecordingTranslationAdapter(mutableListOf()),
        asr: AsrEngine = noCallAsrEngine(),
        createProbeFile: () -> File = { error("ASR probe file was not expected") },
    ): SyncTextConnectionTester =
        SyncTextConnectionTester(
            translation =
                TranslationNetworkGateway(
                    TranslationAdapterRegistry(listOf(adapter)),
                    streamResponses = true,
                ),
            asr = asr,
            policies = TranslationPolicyRegistry.forTesting(::promptBytes),
            createAsrProbeFile = createProbeFile,
        )

    private fun noCallAsrEngine(): AsrEngine =
        object : AsrEngine {
            override suspend fun transcribe(request: AsrRequest): AsrWindowResult =
                error("ASR was not expected")
        }

    private fun createProbeFile(): File =
        File.createTempFile("vortexcue-asr-probe-test-", ".wav").apply {
            writeBytes("RIFF-test-probe".toByteArray())
        }

    private fun asrResult(segments: List<AsrTimestampedToken>) =
        AsrWindowResult(
            segments = segments,
            detectedLanguage = "en",
            usage = UsageRecord(),
            rawResponse = "{}".toByteArray(),
            httpStatus = 200,
            requestBytes = 100L,
            responseBytes = 2L,
            requestId = "request-id",
        )

    private fun promptBytes(id: String): ByteArray {
        val name =
            when (id) {
                "system.v2" -> "translation_system_v2.md"
                "style.zh-CN.v3" -> "translation_style_zh_cn_v3.md"
                else -> error("Unexpected prompt $id")
            }
        return File("src/main/res/raw/$name").readBytes()
    }

    private class RecordingTranslationAdapter(
        private val responses: MutableList<String>,
    ) : TranslationProtocolAdapter {
        override val protocol = TranslationProtocol.Responses
        val requests = mutableListOf<TranslationProviderRequest>()
        var streamingCalls = 0
        var nonStreamingCalls = 0

        override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
            nonStreamingCalls += 1
            error("Connection probes must use streaming translation")
        }

        override suspend fun translateStreaming(request: TranslationProviderRequest): TranslationProviderResponse {
            streamingCalls += 1
            requests += request
            val text = responses.removeAt(0)
            return TranslationProviderResponse(
                text = text,
                usage = UsageRecord(totalTokens = 1L),
                rawResponse = text.toByteArray(),
                httpStatus = 200,
                requestBytes = request.userPrompt.length.toLong(),
                responseBytes = text.length.toLong(),
                requestId = "request-$streamingCalls",
                endpoint = request.endpoint,
            )
        }
    }
}
