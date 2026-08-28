package io.github.zvensmoluya.vortexcue.synctext

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class TranslationProviderRequest(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
    val userPrompt: String,
    val reasoningLevel: TranslationReasoningLevel = TranslationReasoningLevel.Off,
    val userAgent: String? = null,
)

data class TranslationProviderResponse(
    val text: String,
    val usage: UsageRecord,
    val rawResponse: ByteArray,
    val httpStatus: Int,
    val requestBytes: Long,
    val responseBytes: Long,
    val requestId: String?,
    val endpoint: String,
)

data class TranslationNetworkAttempt(
    val attemptNumber: Int,
    val response: TranslationProviderResponse? = null,
    val error: TranslationProtocolException? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val durationMs: Long? = null,
)

class TranslationProtocolException(
    val category: GenerationErrorCategory,
    val retryable: Boolean,
    val capacityExceeded: Boolean,
    val phase: TranslationFailurePhase? = null,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val rawResponse: ByteArray? = null,
    val retryAfterMs: Long? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

enum class TranslationFailurePhase {
    Transport,
    StreamingTransport,
    StreamingDecode,
    ResponseDecode,
    HttpResponse,
}

interface TranslationProtocolAdapter {
    val protocol: TranslationProtocol

    suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse

    /**
     * Streams a provider response when the protocol supports it. Keeping a default implementation
     * preserves compatibility with test and third-party adapters that only implement full JSON.
     */
    suspend fun translateStreaming(request: TranslationProviderRequest): TranslationProviderResponse =
        translate(request)
}

class TranslationAdapterRegistry(adapters: List<TranslationProtocolAdapter>) {
    private val byProtocol = adapters.associateBy(TranslationProtocolAdapter::protocol)

    fun adapter(protocol: TranslationProtocol): TranslationProtocolAdapter =
        requireNotNull(byProtocol[protocol]) { "Translation protocol is not installed: $protocol" }
}

class TranslationNetworkGateway(
    private val registry: TranslationAdapterRegistry,
    private val streamResponses: Boolean = true,
    private val requestBudget: TranslationRequestBudget = TranslationRequestBudget(),
    private val requestTimeoutMs: Long = DefaultRequestTimeoutMs,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
    private val retryJitterMs: (Long) -> Long = { baseDelayMs ->
        Random.nextLong((baseDelayMs / 4L).coerceIn(1L, MaxRetryJitterMs) + 1L)
    },
) {
    init {
        require(requestTimeoutMs > 0L) { "Translation request timeout must be positive" }
    }

    suspend fun translate(
        protocol: TranslationProtocol,
        request: TranslationProviderRequest,
        onAttempt: suspend (TranslationNetworkAttempt) -> Unit = {},
    ): TranslationProviderResponse {
        var lastFailure: TranslationProtocolException? = null
        var attemptEpoch = 0L
        repeat(MaxNetworkAttempts) { index ->
            val startedAtMs = nowMs()
            val startedElapsedMs = elapsedRealtimeMs()
            try {
                val adapter = registry.adapter(protocol)
                val permit = requestBudget.acquire()
                attemptEpoch = permit.cooldownEpoch
                val response = try {
                    try {
                        withTimeout(requestTimeoutMs) {
                            if (streamResponses) adapter.translateStreaming(request) else adapter.translate(request)
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        throw TranslationProtocolException(
                            category = GenerationErrorCategory.TemporaryNetwork,
                            retryable = true,
                            capacityExceeded = false,
                            phase = TranslationFailurePhase.Transport,
                            message = "Translation request timed out after ${requestTimeoutMs}ms",
                            cause = timeout,
                        )
                    }
                } finally {
                    permit.release()
                }
                onAttempt(
                    TranslationNetworkAttempt(
                        attemptNumber = index + 1,
                        response = response,
                        startedAtMs = startedAtMs,
                        finishedAtMs = nowMs(),
                        durationMs = (elapsedRealtimeMs() - startedElapsedMs).coerceAtLeast(0L),
                    ),
                )
                return response
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationProtocolException) {
                lastFailure = error
                if (error.category == GenerationErrorCategory.RateLimited) requestBudget.degradeToSerial()
                onAttempt(
                    TranslationNetworkAttempt(
                        attemptNumber = index + 1,
                        error = error,
                        startedAtMs = startedAtMs,
                        finishedAtMs = nowMs(),
                        durationMs = (elapsedRealtimeMs() - startedElapsedMs).coerceAtLeast(0L),
                    ),
                )
                if (!error.retryable || index == MaxNetworkAttempts - 1) throw error
                val retryDelay = error.retryAfterMs ?: RetryDelaysMs[index]
                if (
                    error.category == GenerationErrorCategory.RateLimited ||
                    error.category == GenerationErrorCategory.TemporaryNetwork
                ) {
                    requestBudget.awaitSharedBackoff(attemptEpoch, retryDelay, waitBeforeRetry)
                } else {
                    waitBeforeRetry(retryDelay)
                }
                if (error.category == GenerationErrorCategory.TemporaryNetwork) {
                    retryJitterMs(retryDelay).coerceAtLeast(0L).takeIf { it > 0L }?.let { waitBeforeRetry(it) }
                }
            }
        }
        throw requireNotNull(lastFailure)
    }

    companion object {
        const val MaxNetworkAttempts = 3
        const val DefaultRequestTimeoutMs = 2 * 60 * 1_000L
        private const val MaxRetryJitterMs = 750L
        private val RetryDelaysMs = longArrayOf(500L, 1_500L, 3_000L)
    }
}

class SyncTextHttpClient(appVersion: String = "unknown") {
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ClientIdentityInterceptor(vortexCueUserAgent(appVersion)))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.MINUTES)
            .writeTimeout(6, TimeUnit.MINUTES)
            .callTimeout(8, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
}

internal class ClientIdentityInterceptor(
    private val defaultUserAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        if (request.header("User-Agent") == null) {
            builder.header("User-Agent", defaultUserAgent)
        }
        if (request.url.host == OpenRouterHost) {
            builder
                .header("HTTP-Referer", VortexCueProjectUrl)
                .header("X-OpenRouter-Title", VortexCueAppTitle)
        }
        return chain.proceed(builder.build())
    }

    companion object {
        private const val OpenRouterHost = "openrouter.ai"
        internal const val VortexCueAppTitle = "VortexCue"
        internal const val VortexCueProjectUrl = "https://github.com/zvensmoluya/VortexCue"
    }
}

internal fun vortexCueUserAgent(appVersion: String): String {
    val safeVersion = appVersion.trim().map { character ->
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '.' || character == '_' || character == '-'
        ) {
            character
        } else {
            '_'
        }
    }.joinToString("").ifBlank { "unknown" }
    return "VortexCue/$safeVersion (Android)"
}

class BoundedHttpExecutor(
    private val client: OkHttpClient,
    private val maxResponseBytes: Int = 8 * 1024 * 1024,
) {
    suspend fun execute(initial: Request): HttpExchange {
        var request = initial
        repeat(MaxRedirects + 1) { redirectCount ->
            val exchange = executeOnce(request)
            if (exchange.status !in RedirectStatuses) return exchange
            val location = exchange.headers["Location"]
                ?: throw protocolFailure(exchange, "Redirect response omitted Location")
            val next = request.url.resolve(location)
                ?: throw protocolFailure(exchange, "Redirect Location is invalid")
            if (!sameOrigin(request.url, next)) {
                throw protocolFailure(exchange, "Cross-origin redirect was rejected")
            }
            if (redirectCount >= MaxRedirects) throw protocolFailure(exchange, "Too many redirects")
            request = request.newBuilder().url(next).build()
        }
        error("Unreachable")
    }

    /**
     * Reads a response incrementally while preserving the same redirect, cancellation, and size
     * limits as [execute]. The callback receives complete UTF-8 response lines, which is the unit
     * needed by SSE without exposing OkHttp response objects to protocol adapters.
     */
    suspend fun executeStreaming(
        initial: Request,
        onLine: (String) -> Unit,
    ): HttpExchange {
        var request = initial
        repeat(MaxRedirects + 1) { redirectCount ->
            val exchange = executeOnce(request, onLine)
            if (exchange.status !in RedirectStatuses) return exchange
            val location = exchange.headers["Location"]
                ?: throw protocolFailure(exchange, "Redirect response omitted Location")
            val next = request.url.resolve(location)
                ?: throw protocolFailure(exchange, "Redirect Location is invalid")
            if (!sameOrigin(request.url, next)) {
                throw protocolFailure(exchange, "Cross-origin redirect was rejected")
            }
            if (redirectCount >= MaxRedirects) throw protocolFailure(exchange, "Too many redirects")
            request = request.newBuilder().url(next).build()
        }
        error("Unreachable")
    }

    private suspend fun executeOnce(
        request: Request,
        onLine: ((String) -> Unit)? = null,
    ): HttpExchange =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCompleted) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val bytes =
                                    if (onLine != null && response.code in 200..299) {
                                        readStreamingBody(response, onLine)
                                    } else {
                                        readBoundedBody(response)
                                    }
                                if (!continuation.isCompleted) {
                                    continuation.resume(
                                        HttpExchange(
                                            status = response.code,
                                            headers = response.headers,
                                            body = bytes,
                                            endpoint = response.request.url.toString(),
                                            requestBytes = request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L,
                                        ),
                                    )
                                }
                            }
                        } catch (error: Throwable) {
                            if (!continuation.isCompleted) continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }

    private fun readStreamingBody(response: Response, onLine: (String) -> Unit): ByteArray {
        val declaredLength = response.body.contentLength()
        if (declaredLength > maxResponseBytes) {
            throw IOException("Response exceeds size limit")
        }
        val source = response.body.source()
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val line = source.readUtf8Line() ?: break
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            total += bytes.size
            if (total > maxResponseBytes) throw IOException("Response exceeds size limit")
            output.write(bytes)
            onLine(line)
        }
        return output.toByteArray()
    }

    private fun readBoundedBody(response: Response): ByteArray {
        val declaredLength = response.body.contentLength()
        if (declaredLength > maxResponseBytes) {
            throw IOException("Response exceeds size limit")
        }
        return response.body.byteStream().use { stream ->
            val buffer = ByteArray(8 * 1024)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxResponseBytes) throw IOException("Response exceeds size limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun protocolFailure(exchange: HttpExchange, message: String) =
        TranslationProtocolException(
            category = GenerationErrorCategory.ModelOrProtocol,
            retryable = false,
            capacityExceeded = false,
            phase = TranslationFailurePhase.HttpResponse,
            httpStatus = exchange.status,
            requestId = exchange.requestId,
            rawResponse = exchange.body,
            message = message,
        )

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    companion object {
        private const val MaxRedirects = 3
        private val RedirectStatuses = setOf(301, 302, 303, 307, 308)
    }
}

data class HttpExchange(
    val status: Int,
    val headers: Headers,
    val body: ByteArray,
    val endpoint: String,
    val requestBytes: Long,
) {
    val requestId: String?
        get() = headers["x-request-id"] ?: headers["request-id"] ?: headers["openai-request-id"]
}

internal class SseEventReader(
    private val onEvent: (String) -> Unit,
) {
    private val data = StringBuilder()
    var sawSseField: Boolean = false
        private set

    fun accept(line: String) {
        when {
            line.isEmpty() -> dispatch()
            line.startsWith(":") -> sawSseField = true
            line.startsWith("data:") -> {
                sawSseField = true
                if (data.isNotEmpty()) data.append('\n')
                val value = line.removePrefix("data:")
                data.append(if (value.startsWith(' ')) value.drop(1) else value)
            }
            line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:") -> {
                sawSseField = true
            }
        }
    }

    fun finish() {
        dispatch()
    }

    private fun dispatch() {
        if (data.isEmpty()) return
        onEvent(data.toString())
        data.setLength(0)
    }
}

data class StreamingTextEvent(
    val text: String = "",
    val usage: UsageRecord = UsageRecord(),
    val providerRequestId: String? = null,
)

abstract class JsonTranslationProtocolAdapter(
    private val executor: BoundedHttpExecutor,
    protected val endpointResolver: TranslationEndpointResolver = TranslationEndpointResolver(),
) : TranslationProtocolAdapter {
    protected val json = Json { ignoreUnknownKeys = true; isLenient = false }

    final override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
        val body = json.encodeToString(JsonObject.serializer(), requestBody(request))
        val httpRequest =
            Request.Builder()
                .url(endpoint(request))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .apply { request.userAgent?.let { header("User-Agent", it) } }
                .apply { authenticationHeaders(request.apiKey).forEach { (name, value) -> header(name, value) } }
                .post(body.toRequestBody(JsonMediaType))
                .build()
        val exchange =
            try {
                executor.execute(httpRequest)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationProtocolException) {
                throw error
            } catch (error: IOException) {
                throw TranslationProtocolException(
                    category = GenerationErrorCategory.TemporaryNetwork,
                    retryable = true,
                    capacityExceeded = false,
                    phase = TranslationFailurePhase.Transport,
                    message = "Translation request failed before a valid response",
                    cause = error,
                )
            }
        if (exchange.status !in 200..299) throw classifyHttpFailure(exchange, request.apiKey)
        val root = parseRoot(exchange)
        val text = extractText(root).trim()
        return TranslationProviderResponse(
            text = text,
            usage = extractUsage(root),
            rawResponse = exchange.body,
            httpStatus = exchange.status,
            requestBytes = exchange.requestBytes,
            responseBytes = exchange.body.size.toLong(),
            requestId = exchange.requestId ?: extractResponseId(root),
            endpoint = exchange.endpoint,
        )
    }

    final override suspend fun translateStreaming(request: TranslationProviderRequest): TranslationProviderResponse {
        val body = json.encodeToString(JsonObject.serializer(), streamingRequestBody(request))
        val httpRequest =
            Request.Builder()
                .url(streamingEndpoint(request))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Content-Type", "application/json")
                .apply { request.userAgent?.let { header("User-Agent", it) } }
                .apply { authenticationHeaders(request.apiKey).forEach { (name, value) -> header(name, value) } }
                .post(body.toRequestBody(JsonMediaType))
                .build()
        val events = mutableListOf<StreamingTextEvent>()
        val reader = SseEventReader { data ->
            if (data.trim() != "[DONE]" && data.trimStart().startsWith("{")) {
                events += parseStreamingEvent(data)
            }
        }
        val exchange =
            try {
                executor.executeStreaming(httpRequest, reader::accept).also { reader.finish() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationProtocolException) {
                throw error
            } catch (error: IOException) {
                throw TranslationProtocolException(
                    category = GenerationErrorCategory.TemporaryNetwork,
                    retryable = true,
                    capacityExceeded = false,
                    phase = TranslationFailurePhase.StreamingTransport,
                    message = "Streaming translation request failed before a valid response",
                    cause = error,
                )
            } catch (error: Throwable) {
                throw TranslationProtocolException(
                    category = GenerationErrorCategory.InvalidResponse,
                    retryable = false,
                    capacityExceeded = false,
                    phase = TranslationFailurePhase.StreamingDecode,
                    message = "Streaming translation response was invalid",
                    cause = error,
                )
            }
        if (exchange.status !in 200..299) throw classifyHttpFailure(exchange, request.apiKey)

        val text: String
        val usage: UsageRecord
        if (reader.sawSseField) {
            text = events.joinToString(separator = "") { it.text }.trim()
            usage = events.map(StreamingTextEvent::usage).sumRealUsage()
        } else {
            val root = parseRoot(exchange)
            text = extractText(root).trim()
            usage = extractUsage(root)
        }
        return TranslationProviderResponse(
            text = text,
            usage = usage,
            rawResponse = exchange.body,
            httpStatus = exchange.status,
            requestBytes = exchange.requestBytes,
            responseBytes = exchange.body.size.toLong(),
            requestId = exchange.requestId ?: events.mapNotNull(StreamingTextEvent::providerRequestId).lastOrNull(),
            endpoint = exchange.endpoint,
        )
    }

    internal fun requestBodyForTest(request: TranslationProviderRequest): JsonObject = requestBody(request)

    internal fun endpointForTest(request: TranslationProviderRequest): String = endpoint(request).toString()

    internal fun parseForTest(bytes: ByteArray): Pair<String, UsageRecord> {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return extractText(root) to extractUsage(root)
    }

    protected abstract fun endpoint(request: TranslationProviderRequest): HttpUrl

    protected open fun streamingEndpoint(request: TranslationProviderRequest): HttpUrl = endpoint(request)

    protected abstract fun authenticationHeaders(apiKey: String): Map<String, String>

    protected abstract fun requestBody(request: TranslationProviderRequest): JsonObject

    protected open fun streamingRequestBody(request: TranslationProviderRequest): JsonObject = requestBody(request)

    protected abstract fun extractText(root: JsonObject): String

    protected abstract fun extractUsage(root: JsonObject): UsageRecord

    protected open fun extractResponseId(root: JsonObject): String? = root.string("id")

    protected abstract fun parseStreamingEvent(data: String): StreamingTextEvent

    private fun parseRoot(exchange: HttpExchange): JsonObject =
        try {
            json.parseToJsonElement(exchange.body.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Throwable) {
            throw TranslationProtocolException(
                category = GenerationErrorCategory.InvalidResponse,
                retryable = false,
                capacityExceeded = false,
                phase = TranslationFailurePhase.ResponseDecode,
                httpStatus = exchange.status,
                requestId = exchange.requestId,
                rawResponse = exchange.body,
                message = "Translation response was not a JSON object",
                cause = error,
            )
        }

    private fun classifyHttpFailure(exchange: HttpExchange, apiKey: String): TranslationProtocolException {
        val rawMessage = extractErrorMessage(exchange.body)
        val message = sanitizeError(rawMessage, apiKey)
        val capacity =
            exchange.status == 413 || CapacityMarkers.any { marker -> rawMessage.contains(marker, ignoreCase = true) }
        val category =
            when {
                exchange.status == 401 || exchange.status == 403 -> GenerationErrorCategory.Authentication
                exchange.status == 429 -> GenerationErrorCategory.RateLimited
                exchange.status == 408 || exchange.status == 425 || exchange.status in 500..599 ->
                    GenerationErrorCategory.TemporaryNetwork
                exchange.status in 400..499 -> GenerationErrorCategory.ModelOrProtocol
                else -> GenerationErrorCategory.InvalidResponse
            }
        return TranslationProtocolException(
            category = category,
            retryable = category == GenerationErrorCategory.RateLimited || category == GenerationErrorCategory.TemporaryNetwork,
            capacityExceeded = capacity,
            phase = TranslationFailurePhase.HttpResponse,
            httpStatus = exchange.status,
            requestId = exchange.requestId,
            rawResponse = exchange.body,
            retryAfterMs = if (category == GenerationErrorCategory.RateLimited) parseRetryAfterMs(exchange.headers["Retry-After"]) else null,
            message = message,
        )
    }

    private fun extractErrorMessage(bytes: ByteArray): String {
        val body = bytes.toString(Charsets.UTF_8)
        return runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            val error = root?.get("error")
            when (error) {
                is JsonObject -> error.string("message") ?: error.string("type")
                is JsonPrimitive -> error.contentOrNull
                else -> root?.string("message")
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "Upstream returned HTTP error"
    }

    private fun sanitizeError(message: String, apiKey: String): String =
        message.replace(apiKey, "[redacted]").replace(AuthorizationValue, "[redacted]").take(600)

    companion object {
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
        private val AuthorizationValue = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+")
        private val CapacityMarkers =
            listOf("context length", "context_length", "too many tokens", "maximum context", "request too large", "payload too large")
    }
}

class ResponsesTranslationAdapter(
    executor: BoundedHttpExecutor,
    endpointResolver: TranslationEndpointResolver = TranslationEndpointResolver(),
) : JsonTranslationProtocolAdapter(executor, endpointResolver) {
    override val protocol = TranslationProtocol.Responses

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        endpointResolver.requestEndpoint(protocol, request.endpoint, "responses")

    override fun authenticationHeaders(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun requestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            put("model", request.model)
            put("instructions", request.systemPrompt)
            put("reasoning", buildJsonObject { put("effort", request.reasoningLevel.wireValue()) })
            if (request.requiresDeepSeekTranslationBounds()) {
                put("max_output_tokens", DeepSeekTranslationMaxOutputTokens)
            }
            put(
                "input",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "input_text"); put("text", request.userPrompt) })
                                },
                            )
                        },
                    )
                },
            )
        }

    override fun streamingRequestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            requestBody(request).forEach { (name, value) -> put(name, value) }
            put("stream", true)
        }

    override fun extractText(root: JsonObject): String {
        root.string("output_text")?.let { return it }
        return root.array("output")
            .flatMap { (it as? JsonObject)?.array("content").orEmpty() }
            .mapNotNull { (it as? JsonObject)?.string("text") }
            .joinToString("\n")
    }

    override fun extractUsage(root: JsonObject): UsageRecord =
        root.objectValue("usage").toUsage("input_tokens", "output_tokens", "total_tokens")

    override fun parseStreamingEvent(data: String): StreamingTextEvent {
        val root = json.parseToJsonElement(data).jsonObject
        val type = root.string("type")
        val text = if (type == "response.output_text.delta") root.string("delta").orEmpty() else ""
        val usageRoot = root.firstNonEmptyObject("usage")
            ?: root.objectValue("response").firstNonEmptyObject("usage")
        return StreamingTextEvent(
            text = text,
            usage = usageRoot?.toUsage("input_tokens", "output_tokens", "total_tokens") ?: UsageRecord(),
            providerRequestId = root.string("id") ?: root.objectValue("response").string("id"),
        )
    }
}

class AnthropicMessagesTranslationAdapter(
    executor: BoundedHttpExecutor,
    endpointResolver: TranslationEndpointResolver = TranslationEndpointResolver(),
) : JsonTranslationProtocolAdapter(executor, endpointResolver) {
    override val protocol = TranslationProtocol.AnthropicMessages

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        endpointResolver.requestEndpoint(protocol, request.endpoint, "messages")

    override fun authenticationHeaders(apiKey: String) =
        mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")

    override fun requestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            put("model", request.model)
            put("max_tokens", 16_384)
            put("system", request.systemPrompt)
            if (request.reasoningLevel == TranslationReasoningLevel.Off) {
                put("thinking", buildJsonObject { put("type", "disabled") })
            } else {
                put("thinking", buildJsonObject { put("type", "adaptive") })
                put(
                    "output_config",
                    buildJsonObject { put("effort", request.reasoningLevel.wireValue()) },
                )
            }
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", request.userPrompt) })
                },
            )
        }

    override fun streamingRequestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            requestBody(request).forEach { (name, value) -> put(name, value) }
            put("stream", true)
        }

    override fun extractText(root: JsonObject): String =
        root.array("content")
            .mapNotNull { (it as? JsonObject)?.takeIf { node -> node.string("type") == "text" }?.string("text") }
            .joinToString("\n")

    override fun extractUsage(root: JsonObject): UsageRecord =
        root.objectValue("usage").toUsage("input_tokens", "output_tokens", "total_tokens")

    override fun parseStreamingEvent(data: String): StreamingTextEvent {
        val root = json.parseToJsonElement(data).jsonObject
        val text =
            if (root.string("type") == "content_block_delta") {
                root.objectValue("delta").string("text").orEmpty()
            } else {
                ""
            }
        val usageRoot =
            root.firstNonEmptyObject("usage")
                ?: root.objectValue("message").firstNonEmptyObject("usage")
                ?: root.objectValue("message_delta").firstNonEmptyObject("usage")
        return StreamingTextEvent(
            text = text,
            usage = usageRoot?.toUsage("input_tokens", "output_tokens", "total_tokens") ?: UsageRecord(),
            providerRequestId = root.string("id") ?: root.objectValue("message").string("id"),
        )
    }
}

class ChatCompletionsTranslationAdapter(
    executor: BoundedHttpExecutor,
    endpointResolver: TranslationEndpointResolver = TranslationEndpointResolver(),
) : JsonTranslationProtocolAdapter(executor, endpointResolver) {
    override val protocol = TranslationProtocol.ChatCompletions

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        endpointResolver.requestEndpoint(protocol, request.endpoint, "chat/completions")

    override fun authenticationHeaders(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun requestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            put("model", request.model)
            put("stream", false)
            if (request.requiresDeepSeekTranslationBounds()) {
                put(
                    "thinking",
                    buildJsonObject {
                        put(
                            "type",
                            if (request.reasoningLevel == TranslationReasoningLevel.Off) "disabled" else "enabled",
                        )
                    },
                )
                if (request.reasoningLevel != TranslationReasoningLevel.Off) {
                    put("reasoning_effort", request.reasoningLevel.deepSeekChatEffort())
                }
                put("max_tokens", DeepSeekTranslationMaxOutputTokens)
            } else {
                put("reasoning_effort", request.reasoningLevel.wireValue())
            }
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", request.systemPrompt) })
                    add(buildJsonObject { put("role", "user"); put("content", request.userPrompt) })
                },
            )
        }

    override fun streamingRequestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            requestBody(request).forEach { (name, value) -> put(name, value) }
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }

    override fun extractText(root: JsonObject): String {
        val message =
            root.array("choices").firstOrNull()
                ?.let { it as? JsonObject }
                ?.objectValue("message")
                ?: return ""
        val content = message["content"]
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("\n")
            else -> ""
        }
    }

    override fun extractUsage(root: JsonObject): UsageRecord =
        root.objectValue("usage").toUsage("prompt_tokens", "completion_tokens", "total_tokens")

    override fun parseStreamingEvent(data: String): StreamingTextEvent {
        val root = json.parseToJsonElement(data).jsonObject
        val choice = root.array("choices").firstOrNull() as? JsonObject
        val delta = choice?.objectValue("delta")
        val text =
            when (val content = delta?.get("content")) {
                is JsonPrimitive -> content.contentOrNull.orEmpty()
                is JsonArray -> content.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("\n")
                else -> ""
            }
        return StreamingTextEvent(
            text = text,
            usage = root.objectValue("usage").toUsage("prompt_tokens", "completion_tokens", "total_tokens"),
            providerRequestId = root.string("id"),
        )
    }
}

private fun TranslationProviderRequest.requiresDeepSeekTranslationBounds(): Boolean {
    val host = runCatching { endpoint.toHttpUrl().host }.getOrNull() ?: return false
    return (host == DeepSeekApiHost || host.endsWith(".$DeepSeekApiHost")) &&
        model.startsWith(DeepSeekV4ModelPrefix)
}

private const val DeepSeekApiHost = "api.deepseek.com"
private const val DeepSeekV4ModelPrefix = "deepseek-v4-"
private const val DeepSeekTranslationMaxOutputTokens = 16_384

class GoogleGenerateContentTranslationAdapter(
    executor: BoundedHttpExecutor,
    endpointResolver: TranslationEndpointResolver = TranslationEndpointResolver(),
) : JsonTranslationProtocolAdapter(executor, endpointResolver) {
    override val protocol = TranslationProtocol.GoogleGenerateContent

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        endpointResolver.requestEndpoint(protocol, request.endpoint, "models/${request.model}:generateContent", request.model)

    override fun streamingEndpoint(request: TranslationProviderRequest): HttpUrl =
        endpointResolver
            .requestEndpoint(protocol, request.endpoint, "models/${request.model}:streamGenerateContent", request.model)
            .newBuilder()
            .setQueryParameter("alt", "sse")
            .build()

    override fun authenticationHeaders(apiKey: String) = mapOf("x-goog-api-key" to apiKey)

    override fun requestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", request.systemPrompt) }) })
                },
            )
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", request.userPrompt) }) })
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("maxOutputTokens", 16_384)
                    put(
                        "thinkingConfig",
                        buildJsonObject {
                            if (request.reasoningLevel == TranslationReasoningLevel.Off) {
                                put("thinkingBudget", 0)
                            } else {
                                put("thinkingLevel", request.reasoningLevel.wireValue().uppercase())
                            }
                        },
                    )
                },
            )
        }

    override fun extractText(root: JsonObject): String =
        root.array("candidates")
            .firstOrNull()
            ?.let { it as? JsonObject }
            ?.objectValue("content")
            ?.array("parts")
            ?.mapNotNull { (it as? JsonObject)?.string("text") }
            ?.joinToString("\n")
            .orEmpty()

    override fun extractUsage(root: JsonObject): UsageRecord =
        root.objectValue("usageMetadata").toUsage("promptTokenCount", "candidatesTokenCount", "totalTokenCount")

    override fun parseStreamingEvent(data: String): StreamingTextEvent {
        val root = json.parseToJsonElement(data).jsonObject
        val text =
            root.array("candidates")
                .firstOrNull()
                ?.let { it as? JsonObject }
                ?.objectValue("content")
                ?.array("parts")
                ?.mapNotNull { (it as? JsonObject)?.string("text") }
                ?.joinToString("\n")
                .orEmpty()
        return StreamingTextEvent(
            text = text,
            usage = root.objectValue("usageMetadata").toUsage("promptTokenCount", "candidatesTokenCount", "totalTokenCount"),
            providerRequestId = root.string("responseId"),
        )
    }
}

fun defaultTranslationAdapters(
    httpClient: SyncTextHttpClient,
    endpointResolver: TranslationEndpointResolver = TranslationEndpointResolver(),
): List<TranslationProtocolAdapter> {
    val executor = BoundedHttpExecutor(httpClient.client)
    return listOf(
        ResponsesTranslationAdapter(executor, endpointResolver),
        AnthropicMessagesTranslationAdapter(executor, endpointResolver),
        ChatCompletionsTranslationAdapter(executor, endpointResolver),
        GoogleGenerateContentTranslationAdapter(executor, endpointResolver),
    )
}

private fun TranslationReasoningLevel.wireValue(): String =
    when (this) {
        TranslationReasoningLevel.Off -> "none"
        else -> name.lowercase()
    }

private fun TranslationReasoningLevel.deepSeekChatEffort(): String =
    when (this) {
        TranslationReasoningLevel.Off -> error("Non-thinking DeepSeek requests do not have reasoning effort")
        TranslationReasoningLevel.Low -> "low"
        TranslationReasoningLevel.Medium,
        TranslationReasoningLevel.High,
        -> "high"
    }

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.array(name: String): JsonArray =
    get(name) as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.objectValue(name: String): JsonObject =
    get(name) as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.firstNonEmptyObject(vararg names: String): JsonObject? =
    names.asSequence()
        .mapNotNull { get(it) as? JsonObject }
        .firstOrNull { it.isNotEmpty() }

private fun JsonObject.toUsage(inputName: String, outputName: String, totalName: String): UsageRecord {
    val input = (get(inputName) as? JsonPrimitive)?.longOrNull
    val output = (get(outputName) as? JsonPrimitive)?.longOrNull
    val total = (get(totalName) as? JsonPrimitive)?.longOrNull
    val cost =
        listOf("cost", "total_cost", "cost_usd")
            .firstNotNullOfOrNull { name -> (get(name) as? JsonPrimitive)?.contentOrNull }
    return UsageRecord(input, output, total, cost)
}

internal fun List<UsageRecord>.sumRealUsage(): UsageRecord {
    fun sumLong(selector: (UsageRecord) -> Long?): Long? = mapNotNull(selector).takeIf { it.isNotEmpty() }?.sum()
    val costs = mapNotNull(UsageRecord::costUsd).mapNotNull { runCatching { BigDecimal(it) }.getOrNull() }
    return UsageRecord(
        inputTokens = sumLong(UsageRecord::inputTokens),
        outputTokens = sumLong(UsageRecord::outputTokens),
        totalTokens = sumLong(UsageRecord::totalTokens),
        costUsd = costs.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)?.toPlainString(),
    )
}
