package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `openrouter chat requests express routing without affecting generic gateways`() {
        val adapter = ChatCompletionsTranslationAdapter(BoundedHttpExecutor(testClient()))
        val price = adapter.requestBodyForTest(
            request("https://openrouter.ai/api/v1").copy(
                openRouterRouting = OpenRouterRoutingPolicy(OpenRouterRoutingMode.Price),
            ),
        )
        val provider = adapter.requestBodyForTest(
            request("https://openrouter.ai/api/v1").copy(
                openRouterRouting =
                    OpenRouterRoutingPolicy(
                        mode = OpenRouterRoutingMode.Provider,
                        providerTag = "google-ai-studio",
                        allowFallbacks = false,
                    ),
            ),
        )
        val generic = adapter.requestBodyForTest(
            request("https://example.test/v1").copy(
                openRouterRouting = OpenRouterRoutingPolicy(OpenRouterRoutingMode.Throughput),
            ),
        )

        assertEquals("price", price["provider"]!!.jsonObject["sort"]!!.jsonPrimitive.content)
        assertEquals(
            """{"only":["google-ai-studio"],"allow_fallbacks":false}""",
            provider["provider"]!!.jsonObject.toString(),
        )
        assertFalse("provider" in generic)
    }

    @Test
    fun `empty successful response reaches format recovery layer`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"output":[],"usage":{"total_tokens":1}}"""))
            val adapter = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))

            val response = adapter.translate(request(server.url("/").toString()))

            assertEquals("", response.text)
            assertEquals(1L, response.usage.totalTokens)
        }
    }

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
    fun `responses reports reasoning as activity without mixing it into translation text`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body =
                        """
                        data: {"type":"response.reasoning_summary_text.delta","delta":"working"}

                        data: {"type":"response.output_text.delta","delta":"[cue-1] one"}

                        data: {"type":"response.completed","response":{"usage":{"output_tokens":3}}}

                        """.trimIndent(),
                ),
            )
            val activity = mutableListOf<TranslationStreamActivity>()
            val adapter = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))

            val response = adapter.translateStreaming(request(server.url("/").toString()), activity::add)

            assertEquals("[cue-1] one", response.text)
            assertEquals(
                listOf(
                    TranslationStreamActivity(TranslationStreamActivityKind.Reasoning, "working"),
                    TranslationStreamActivity(TranslationStreamActivityKind.Output, "[cue-1] one"),
                ),
                activity,
            )
        }
    }

    @Test
    fun `responses accepts output text done as the final snapshot without duplicating deltas`() = runBlocking {
        val cases =
            listOf(
                """
                data: {"type":"response.reasoning_summary_text.delta","delta":"working"}

                data: {"type":"response.output_text.done","text":"[cue-1] one"}

                data: {"type":"response.completed","response":{"usage":{"output_tokens":3}}}

                """.trimIndent(),
                """
                data: {"type":"response.output_text.delta","delta":"[cue-1] "}

                data: {"type":"response.output_text.delta","delta":"one"}

                data: {"type":"response.output_text.done","text":"[cue-1] one"}

                data: {"type":"response.completed","response":{"usage":{"output_tokens":3}}}

                """.trimIndent(),
            )

        cases.forEach { stream ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse(
                        headers = headersOf("Content-Type", "text/event-stream"),
                        body = stream,
                    ),
                )
                val adapter = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))

                val response = adapter.translateStreaming(request(server.url("/").toString()))

                assertEquals("[cue-1] one", response.text)
                assertEquals(3L, response.usage.outputTokens)
            }
        }
    }

    @Test
    fun `responses adapter falls back to provider ids carried in response bodies`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    body =
                        """{"id":"response-full","output_text":"[cue-1] one","usage":{"total_tokens":1}}""",
                ),
            )
            server.enqueue(
                MockResponse(
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body =
                        """
                        event: response.created
                        data: {"type":"response.created","response":{"id":"response-stream"}}

                        event: response.output_text.delta
                        data: {"type":"response.output_text.delta","delta":"[cue-1] one"}

                        event: response.completed
                        data: {"type":"response.completed","response":{"id":"response-stream","usage":{"total_tokens":1}}}

                        """.trimIndent(),
                ),
            )
            val adapter = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))

            val full = adapter.translate(request(server.url("/").toString()))
            val streamed = adapter.translateStreaming(request(server.url("/").toString()))

            assertEquals("response-full", full.requestId)
            assertEquals("response-stream", streamed.requestId)
        }
    }

    @Test
    fun `deepseek translation maps reasoning and bounds output`() {
        val deepSeekRequest =
            request("https://api.deepseek.com").copy(model = "deepseek-v4-flash")
        val responses = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient())).requestBodyForTest(deepSeekRequest)
        val chat = ChatCompletionsTranslationAdapter(BoundedHttpExecutor(testClient())).requestBodyForTest(deepSeekRequest)

        assertEquals("none", responses["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("16384", responses["max_output_tokens"]?.jsonPrimitive?.content)
        assertEquals("disabled", chat["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("16384", chat["max_tokens"]?.jsonPrimitive?.content)

        val generic =
            ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))
                .requestBodyForTest(request("https://example.test").copy(model = "deepseek-v4-flash"))
        assertEquals("none", generic["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertFalse("max_output_tokens" in generic)

        val thinking = deepSeekRequest.copy(reasoningLevel = TranslationReasoningLevel.Medium)
        val thinkingChat = ChatCompletionsTranslationAdapter(BoundedHttpExecutor(testClient())).requestBodyForTest(thinking)
        assertEquals("enabled", thinkingChat["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", thinkingChat["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `product reasoning levels map to each protocol contract`() {
        val executor = BoundedHttpExecutor(testClient())
        val high = request("https://example.test").copy(reasoningLevel = TranslationReasoningLevel.High)

        val responses = ResponsesTranslationAdapter(executor).requestBodyForTest(high)
        assertEquals("high", responses["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)

        val anthropic = AnthropicMessagesTranslationAdapter(executor).requestBodyForTest(high)
        assertEquals("adaptive", anthropic["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", anthropic["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        val anthropicOff =
            AnthropicMessagesTranslationAdapter(executor)
                .requestBodyForTest(high.copy(reasoningLevel = TranslationReasoningLevel.Off))
        assertEquals("disabled", anthropicOff["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse("output_config" in anthropicOff)

        val chat = ChatCompletionsTranslationAdapter(executor).requestBodyForTest(high)
        assertEquals("high", chat["reasoning_effort"]?.jsonPrimitive?.content)

        val google = GoogleGenerateContentTranslationAdapter(executor).requestBodyForTest(high)
        val googleThinking =
            google["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject
        assertEquals("HIGH", googleThinking?.get("thinkingLevel")?.jsonPrimitive?.content)
        val googleOff =
            GoogleGenerateContentTranslationAdapter(executor)
                .requestBodyForTest(high.copy(reasoningLevel = TranslationReasoningLevel.Off))
        assertEquals(
            "0",
            googleOff["generationConfig"]
                ?.jsonObject
                ?.get("thinkingConfig")
                ?.jsonObject
                ?.get("thinkingBudget")
                ?.jsonPrimitive
                ?.content,
        )

        val omitted = high.copy(reasoningLevel = null)
        assertFalse("reasoning" in ResponsesTranslationAdapter(executor).requestBodyForTest(omitted))
        val anthropicOmitted = AnthropicMessagesTranslationAdapter(executor).requestBodyForTest(omitted)
        assertFalse("thinking" in anthropicOmitted)
        assertFalse("output_config" in anthropicOmitted)
        assertFalse("reasoning_effort" in ChatCompletionsTranslationAdapter(executor).requestBodyForTest(omitted))
        val googleOmitted = GoogleGenerateContentTranslationAdapter(executor).requestBodyForTest(omitted)
        assertFalse("thinkingConfig" in requireNotNull(googleOmitted["generationConfig"]).jsonObject)
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
    fun `gateway converts a hung provider call into a bounded retryable failure`() = runBlocking {
        var calls = 0
        val attempts = mutableListOf<TranslationNetworkAttempt>()
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
                    calls += 1
                    awaitCancellation()
                }
            }
        val gateway =
            TranslationNetworkGateway(
                registry = TranslationAdapterRegistry(listOf(adapter)),
                firstOutputTimeoutMs = 20L,
                waitBeforeRetry = {},
                retryJitterMs = { 0L },
            )

        val error =
            runCatching {
                gateway.translate(
                    TranslationProtocol.Responses,
                    request("https://example.test/v1/responses"),
                    onAttempt = attempts::add,
                )
            }.exceptionOrNull() as TranslationProtocolException

        assertEquals(GenerationErrorCategory.TemporaryNetwork, error.category)
        assertTrue(error.retryable)
        assertEquals(TranslationNetworkGateway.MaxNetworkAttempts, calls)
        assertEquals(TranslationNetworkGateway.MaxNetworkAttempts, attempts.size)
        assertTrue(attempts.all { it.error?.phase == TranslationFailurePhase.FirstOutputTimeout })
    }

    @Test
    fun `stream activity keeps a response alive beyond the first output timeout`() = runBlocking {
        val events = mutableListOf<TranslationStreamEvent>()
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse =
                    error("Streaming path expected")

                override suspend fun translateStreaming(
                    request: TranslationProviderRequest,
                    onActivity: (TranslationStreamActivity) -> Unit,
                ): TranslationProviderResponse {
                    repeat(3) {
                        delay(200L)
                        onActivity(TranslationStreamActivity(TranslationStreamActivityKind.Output, "abcd"))
                    }
                    return TranslationProviderResponse("done", UsageRecord(), byteArrayOf(), 200, 1L, 1L, null, request.endpoint)
                }
            }
        val gateway =
            TranslationNetworkGateway(
                registry = TranslationAdapterRegistry(listOf(adapter)),
                firstOutputTimeoutMs = 500L,
                streamIdleTimeoutMs = 500L,
            )

        val response =
            gateway.translate(
                TranslationProtocol.Responses,
                request("https://example.test/v1/responses"),
                onStreamEvent = events::add,
            )

        assertEquals("done", response.text)
        assertEquals(3, events.filterIsInstance<TranslationStreamEvent.OutputDelta>().size)
        assertTrue(events.first() is TranslationStreamEvent.AttemptStarted)
        assertTrue(events.last() is TranslationStreamEvent.AttemptCompleted)
    }

    @Test
    fun `gateway times out a stream that becomes idle after output`() = runBlocking {
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse =
                    error("Streaming path expected")

                override suspend fun translateStreaming(
                    request: TranslationProviderRequest,
                    onActivity: (TranslationStreamActivity) -> Unit,
                ): TranslationProviderResponse {
                    onActivity(TranslationStreamActivity(TranslationStreamActivityKind.Output, "first"))
                    awaitCancellation()
                }
            }
        val gateway =
            TranslationNetworkGateway(
                registry = TranslationAdapterRegistry(listOf(adapter)),
                firstOutputTimeoutMs = 100L,
                streamIdleTimeoutMs = 20L,
                waitBeforeRetry = {},
                retryJitterMs = { 0L },
            )

        val error =
            runCatching {
                gateway.translate(TranslationProtocol.Responses, request("https://example.test/v1/responses"))
            }.exceptionOrNull() as TranslationProtocolException

        assertEquals(TranslationFailurePhase.StreamIdleTimeout, error.phase)
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
        var wallClockMs = 1_000L
        var elapsedClockMs = 500L
        val waits = mutableListOf<Long>()
        val attempts = mutableListOf<TranslationNetworkAttempt>()
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
                    calls += 1
                    wallClockMs += 20L
                    elapsedClockMs += 15L
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
        val gateway =
            TranslationNetworkGateway(
                registry = TranslationAdapterRegistry(listOf(adapter)),
                nowMs = { wallClockMs },
                elapsedRealtimeMs = { elapsedClockMs },
                waitBeforeRetry = {
                    waits += it
                    wallClockMs += it
                    elapsedClockMs += it
                },
                retryJitterMs = { 0L },
            )

        val response =
            gateway.translate(
                TranslationProtocol.Responses,
                request("https://example.test/v1/responses"),
                onAttempt = attempts::add,
            )

        assertEquals("ok", response.text)
        assertEquals(3, calls)
        assertEquals(listOf(500L, 1_500L), waits)
        assertEquals(listOf(1, 2, 3), attempts.map(TranslationNetworkAttempt::attemptNumber))
        assertEquals(listOf(15L, 15L, 15L), attempts.map(TranslationNetworkAttempt::durationMs))
        assertTrue(attempts.all { requireNotNull(it.finishedAtMs) > requireNotNull(it.startedAtMs) })
    }

    @Test
    fun `temporary network retries use shared cooldown and per request jitter`() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
                    calls += 1
                    if (calls == 1) {
                        throw TranslationProtocolException(
                            category = GenerationErrorCategory.TemporaryNetwork,
                            retryable = true,
                            capacityExceeded = false,
                            phase = TranslationFailurePhase.Transport,
                            message = "temporary",
                        )
                    }
                    return TranslationProviderResponse("ok", UsageRecord(), byteArrayOf(), 200, 1L, 1L, null, request.endpoint)
                }
            }
        val gateway =
            TranslationNetworkGateway(
                registry = TranslationAdapterRegistry(listOf(adapter)),
                waitBeforeRetry = { waits += it },
                retryJitterMs = { 125L },
            )

        gateway.translate(TranslationProtocol.Responses, request("https://example.test/v1/responses"))

        assertEquals(listOf(500L, 125L), waits)
    }

    @Test
    fun `rate limit uses server delay and degrades shared concurrency`() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val budget = TranslationRequestBudget(maxParallelRequests = 4)
        val adapter =
            object : TranslationProtocolAdapter {
                override val protocol = TranslationProtocol.Responses

                override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
                    calls += 1
                    if (calls == 1) {
                        throw TranslationProtocolException(
                            category = GenerationErrorCategory.RateLimited,
                            retryable = true,
                            capacityExceeded = false,
                            retryAfterMs = 12_000L,
                            message = "rate limited",
                        )
                    }
                    return TranslationProviderResponse("ok", UsageRecord(), byteArrayOf(), 200, 1L, 1L, null, request.endpoint)
                }
            }
        val gateway =
            TranslationNetworkGateway(
                registry = TranslationAdapterRegistry(listOf(adapter)),
                requestBudget = budget,
                waitBeforeRetry = { waits += it },
            )

        gateway.translate(TranslationProtocol.Responses, request("https://example.test/v1/responses"))

        assertEquals(listOf(12_000L), waits)
        assertEquals(1, budget.parallelLimit())
    }

    @Test
    fun `new requests wait while shared backoff is active`() = runBlocking {
        val budget = TranslationRequestBudget(maxParallelRequests = 4)
        val backoffEntered = CompletableDeferred<Unit>()
        val releaseBackoff = CompletableDeferred<Unit>()
        val backoff =
            async {
                budget.awaitSharedBackoff(0L, 1L) {
                    backoffEntered.complete(Unit)
                    releaseBackoff.await()
                }
            }

        backoffEntered.await()
        val blocked = async { budget.acquire() }
        yield()
        assertFalse(blocked.isCompleted)

        releaseBackoff.complete(Unit)
        backoff.await()
        blocked.await().release()
    }

    @Test
    fun `http rate limit parses retry after`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 429,
                    headers = headersOf("Retry-After", "12"),
                    body = """{"error":{"message":"slow down"}}""",
                ),
            )
            val adapter = ResponsesTranslationAdapter(BoundedHttpExecutor(testClient()))

            val error =
                runCatching { adapter.translate(request(server.url("/").toString())) }
                    .exceptionOrNull() as TranslationProtocolException

            assertEquals(GenerationErrorCategory.RateLimited, error.category)
            assertEquals(12_000L, error.retryAfterMs)
        }
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
