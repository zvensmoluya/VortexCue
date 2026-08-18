package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProtocolAdaptersTest {
    @Test
    fun `all protocols send their exact contract and parse usage`() = runBlocking {
        val cases =
            listOf(
                ProtocolCase(
                    expectedPath = "/responses",
                    adapter = { ResponsesTranslationAdapter(it) },
                    authName = "Authorization",
                    authValue = "Bearer test-key",
                    response = """{"output_text":"[cue-1] one","usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5,"cost":"0.01"}}""",
                    expectedInput = 2L,
                    expectedOutput = 3L,
                ),
                ProtocolCase(
                    expectedPath = "/v1/messages",
                    adapter = { AnthropicMessagesTranslationAdapter(it) },
                    authName = "x-api-key",
                    authValue = "test-key",
                    response = """{"content":[{"type":"text","text":"[cue-1] one"}],"usage":{"input_tokens":4,"output_tokens":6}}""",
                    expectedInput = 4L,
                    expectedOutput = 6L,
                ),
                ProtocolCase(
                    expectedPath = "/chat/completions",
                    adapter = { ChatCompletionsTranslationAdapter(it) },
                    authName = "Authorization",
                    authValue = "Bearer test-key",
                    response = """{"choices":[{"message":{"content":"[cue-1] one"}}],"usage":{"prompt_tokens":7,"completion_tokens":8,"total_tokens":15}}""",
                    expectedInput = 7L,
                    expectedOutput = 8L,
                ),
                ProtocolCase(
                    expectedPath = "/v1beta/models/model-test:generateContent",
                    adapter = { GoogleGenerateContentTranslationAdapter(it) },
                    authName = "x-goog-api-key",
                    authValue = "test-key",
                    response = """{"candidates":[{"content":{"parts":[{"text":"[cue-1] one"}]}}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":10,"totalTokenCount":19}}""",
                    expectedInput = 9L,
                    expectedOutput = 10L,
                ),
            )

        cases.forEach { case ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = case.response))
                val adapter = case.adapter(BoundedHttpExecutor(testClient()))

                val response = adapter.translate(request(server.url("/").toString()))
                val recorded = server.takeRequest()

                assertEquals("POST", recorded.method)
                assertEquals(case.expectedPath, recorded.target)
                assertEquals(case.authValue, recorded.headers[case.authName])
                assertEquals("VortexCue-Test/1", recorded.headers["User-Agent"])
                assertEquals("[cue-1] one", response.text)
                assertEquals(case.expectedInput, response.usage.inputTokens)
                assertEquals(case.expectedOutput, response.usage.outputTokens)
                assertTrue(response.requestBytes > 0L)
                val body = requireNotNull(recorded.body).utf8()
                assertTrue(body.contains("system-policy"))
                assertTrue(body.contains("translate-cues"))
                assertFalse(body.contains("test-key"))
                assertNotNull(Json.parseToJsonElement(body).jsonObject)
            }
        }
    }

    @Test
    fun `chat completions streams deltas and keeps final usage`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body =
                        """
                        data: {"choices":[{"delta":{"content":"[cue-1] "}}]}

                        data: {"choices":[{"delta":{"content":"one"}}]}

                        data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":8,"total_tokens":15}}

                        data: [DONE]

                        """.trimIndent(),
                ),
            )
            val adapter = ChatCompletionsTranslationAdapter(BoundedHttpExecutor(testClient()))

            val response = adapter.translateStreaming(request(server.url("/").toString()))
            val recorded = server.takeRequest()

            assertEquals("/chat/completions", recorded.target)
            assertEquals("[cue-1] one", response.text)
            assertEquals(7L, response.usage.inputTokens)
            assertEquals(8L, response.usage.outputTokens)
            val body = requireNotNull(recorded.body).utf8()
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("\"include_usage\":true"))
        }
    }

    @Test
    fun `responses anthropic and google adapters consume their stream shapes`() = runBlocking {
        val cases =
            listOf(
                StreamingProtocolCase(
                    adapter = { ResponsesTranslationAdapter(it) },
                    expectedPath = "/responses",
                    response =
                        """
                        data: {"type":"response.output_text.delta","delta":"[cue-1] "}

                        data: {"type":"response.output_text.delta","delta":"one"}

                        data: {"type":"response.completed","response":{"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}}}

                        data: [DONE]

                        """.trimIndent(),
                    input = 2L,
                    output = 3L,
                ),
                StreamingProtocolCase(
                    adapter = { AnthropicMessagesTranslationAdapter(it) },
                    expectedPath = "/v1/messages",
                    response =
                        """
                        event: message_start
                        data: {"type":"message_start","message":{"usage":{"input_tokens":4,"output_tokens":0}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"[cue-1] one"}}

                        event: message_delta
                        data: {"type":"message_delta","usage":{"output_tokens":6}}

                        """.trimIndent(),
                    input = 4L,
                    output = 6L,
                ),
                StreamingProtocolCase(
                    adapter = { GoogleGenerateContentTranslationAdapter(it) },
                    expectedPath = "/v1beta/models/model-test:streamGenerateContent",
                    response =
                        """
                        data: {"candidates":[{"content":{"parts":[{"text":"[cue-1] one"}]}}]}

                        data: {"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":10,"totalTokenCount":19}}

                        """.trimIndent(),
                    input = 9L,
                    output = 10L,
                ),
            )

        cases.forEach { case ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse(
                        headers = headersOf("Content-Type", "text/event-stream"),
                        body = case.response,
                    ),
                )
                val response = case.adapter(BoundedHttpExecutor(testClient())).translateStreaming(request(server.url("/").toString()))
                val recorded = server.takeRequest()

                assertTrue(recorded.target.startsWith(case.expectedPath))
                assertEquals("[cue-1] one", response.text)
                assertEquals(case.input, response.usage.inputTokens)
                assertEquals(case.output, response.usage.outputTokens)
            }
        }
    }

    @Test
    fun `google template switches to the streaming operation`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}]}}]}\n\n",
                ),
            )
            val adapter = GoogleGenerateContentTranslationAdapter(BoundedHttpExecutor(testClient()))

            adapter.translateStreaming(
                request(server.url("/v1beta/models/{model}:generateContent").toString()),
            )

            assertEquals(
                "/v1beta/models/model-test:streamGenerateContent?alt=sse",
                server.takeRequest().target,
            )
        }
    }

    @Test
    fun `streaming gateway has one switch and defaults to stream`() = runBlocking {
        var streamingCalls = 0
        var fullCalls = 0
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
                    fullCalls += 1
                    return TranslationProviderResponse("full", UsageRecord(), byteArrayOf(), 200, 1L, 1L, null, request.endpoint)
                }

                override suspend fun translateStreaming(request: TranslationProviderRequest): TranslationProviderResponse {
                    streamingCalls += 1
                    return TranslationProviderResponse("stream", UsageRecord(), byteArrayOf(), 200, 1L, 1L, null, request.endpoint)
                }
            }

        val response =
            TranslationNetworkGateway(TranslationAdapterRegistry(listOf(adapter))).translate(
                TranslationProtocol.Responses,
                request("https://example.test/v1/responses"),
            )

        assertEquals("stream", response.text)
        assertEquals(1, streamingCalls)
        assertEquals(0, fullCalls)
    }

    @Test
    fun `authentication errors are categorized and secrets are redacted`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 401,
                    body = """{"error":{"message":"bad secret-key and Bearer secret-key"}}""",
                ),
            )
            val adapter = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))

            val error =
                runCatching { adapter.translate(request(server.url("/").toString(), apiKey = "secret-key")) }
                    .exceptionOrNull() as TranslationProtocolException

            assertEquals(GenerationErrorCategory.Authentication, error.category)
            assertFalse(error.retryable)
            assertFalse(requireNotNull(error.message).contains("secret-key"))
            assertTrue(error.rawResponse?.toString(Charsets.UTF_8)?.contains("secret-key") == true)
        }
    }

    @Test
    fun `cross origin redirects are rejected before credentials can reach the second server`() = runBlocking {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.start()
                second.start()
                first.enqueue(
                    MockResponse(
                        code = 307,
                        headers = headersOf("Location", second.url("/stolen").toString()),
                    ),
                )
                val adapter = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))

                val error =
                    runCatching { adapter.translate(request(first.url("/").toString())) }
                        .exceptionOrNull() as TranslationProtocolException

                assertEquals(GenerationErrorCategory.ModelOrProtocol, error.category)
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test
    fun `gateway retries temporary failures at most three times`() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
                    calls += 1
                    if (calls < 3) {
                        throw TranslationProtocolException(
                            category = GenerationErrorCategory.TemporaryNetwork,
                            retryable = true,
                            capacityExceeded = false,
                            message = "temporary",
                        )
                    }
                    return TranslationProviderResponse("ok", UsageRecord(), byteArrayOf(), 200, 1L, 1L, null, request.endpoint)
                }
            }
        val gateway = TranslationNetworkGateway(TranslationAdapterRegistry(listOf(adapter))) { waits += it }

        val response = gateway.translate(TranslationProtocol.Responses, request("https://example.test/v1/responses"))

        assertEquals("ok", response.text)
        assertEquals(3, calls)
        assertEquals(listOf(500L, 1_500L), waits)
    }

    private fun request(
        endpoint: String,
        apiKey: String = "test-key",
    ) =
        TranslationProviderRequest(
            endpoint = endpoint,
            model = "model-test",
            apiKey = apiKey,
            systemPrompt = "system-policy",
            userPrompt = "translate-cues",
            userAgent = "VortexCue-Test/1",
        )

    private fun testClient(): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    private data class ProtocolCase(
        val expectedPath: String,
        val adapter: (BoundedHttpExecutor) -> JsonTranslationProtocolAdapter,
        val authName: String,
        val authValue: String,
        val response: String,
        val expectedInput: Long,
        val expectedOutput: Long,
    )

    private data class StreamingProtocolCase(
        val adapter: (BoundedHttpExecutor) -> JsonTranslationProtocolAdapter,
        val expectedPath: String,
        val response: String,
        val input: Long,
        val output: Long,
    )
}
