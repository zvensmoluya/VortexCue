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
                    expectedPath = "/v1/responses",
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
                    expectedPath = "/v1/chat/completions",
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
}
