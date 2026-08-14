package io.github.zvensmoluya.vortexcue.synctext

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

data class TranslationProviderRequest(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
    val userPrompt: String,
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
)

class TranslationProtocolException(
    val category: GenerationErrorCategory,
    val retryable: Boolean,
    val capacityExceeded: Boolean,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val rawResponse: ByteArray? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

interface TranslationProtocolAdapter {
    val protocol: TranslationProtocol

    suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse
}

class TranslationAdapterRegistry(adapters: List<TranslationProtocolAdapter>) {
    private val byProtocol = adapters.associateBy(TranslationProtocolAdapter::protocol)

    fun adapter(protocol: TranslationProtocol): TranslationProtocolAdapter =
        requireNotNull(byProtocol[protocol]) { "Translation protocol is not installed: $protocol" }
}

class TranslationNetworkGateway(
    private val registry: TranslationAdapterRegistry,
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun translate(
        protocol: TranslationProtocol,
        request: TranslationProviderRequest,
        onAttempt: suspend (TranslationNetworkAttempt) -> Unit = {},
    ): TranslationProviderResponse {
        var lastFailure: TranslationProtocolException? = null
        repeat(MaxNetworkAttempts) { index ->
            try {
                val response = registry.adapter(protocol).translate(request)
                onAttempt(TranslationNetworkAttempt(index + 1, response = response))
                return response
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationProtocolException) {
                lastFailure = error
                onAttempt(TranslationNetworkAttempt(index + 1, error = error))
                if (!error.retryable || index == MaxNetworkAttempts - 1) throw error
                waitBeforeRetry(RetryDelaysMs[index])
            }
        }
        throw requireNotNull(lastFailure)
    }

    companion object {
        const val MaxNetworkAttempts = 3
        private val RetryDelaysMs = longArrayOf(500L, 1_500L, 3_000L)
    }
}

class SyncTextHttpClient {
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.MINUTES)
            .writeTimeout(6, TimeUnit.MINUTES)
            .callTimeout(8, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
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

    private suspend fun executeOnce(request: Request): HttpExchange =
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
                                val declaredLength = response.body.contentLength()
                                if (declaredLength > maxResponseBytes) {
                                    throw IOException("Response exceeds size limit")
                                }
                                val bytes = response.body.byteStream().use { stream ->
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

    private fun protocolFailure(exchange: HttpExchange, message: String) =
        TranslationProtocolException(
            category = GenerationErrorCategory.ModelOrProtocol,
            retryable = false,
            capacityExceeded = false,
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

abstract class JsonTranslationProtocolAdapter(
    private val executor: BoundedHttpExecutor,
) : TranslationProtocolAdapter {
    protected val json = Json { ignoreUnknownKeys = true; isLenient = false }

    final override suspend fun translate(request: TranslationProviderRequest): TranslationProviderResponse {
        val body = json.encodeToString(JsonObject.serializer(), requestBody(request))
        val httpRequest =
            Request.Builder()
                .url(endpoint(request))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
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
                    message = "Translation request failed before a valid response",
                    cause = error,
                )
            }
        if (exchange.status !in 200..299) throw classifyHttpFailure(exchange, request.apiKey)
        val root = parseRoot(exchange)
        val text = extractText(root).trim()
        if (text.isBlank()) {
            throw TranslationProtocolException(
                category = GenerationErrorCategory.InvalidResponse,
                retryable = false,
                capacityExceeded = false,
                httpStatus = exchange.status,
                requestId = exchange.requestId,
                rawResponse = exchange.body,
                message = "Translation response did not contain text",
            )
        }
        return TranslationProviderResponse(
            text = text,
            usage = extractUsage(root),
            rawResponse = exchange.body,
            httpStatus = exchange.status,
            requestBytes = exchange.requestBytes,
            responseBytes = exchange.body.size.toLong(),
            requestId = exchange.requestId,
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

    protected abstract fun authenticationHeaders(apiKey: String): Map<String, String>

    protected abstract fun requestBody(request: TranslationProviderRequest): JsonObject

    protected abstract fun extractText(root: JsonObject): String

    protected abstract fun extractUsage(root: JsonObject): UsageRecord

    private fun parseRoot(exchange: HttpExchange): JsonObject =
        try {
            json.parseToJsonElement(exchange.body.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Throwable) {
            throw TranslationProtocolException(
                category = GenerationErrorCategory.InvalidResponse,
                retryable = false,
                capacityExceeded = false,
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
            httpStatus = exchange.status,
            requestId = exchange.requestId,
            rawResponse = exchange.body,
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

class ResponsesTranslationAdapter(executor: BoundedHttpExecutor) : JsonTranslationProtocolAdapter(executor) {
    override val protocol = TranslationProtocol.Responses

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        resolveOpenAiStyleEndpoint(request.endpoint, "responses")

    override fun authenticationHeaders(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun requestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            put("model", request.model)
            put("instructions", request.systemPrompt)
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

    override fun extractText(root: JsonObject): String {
        root.string("output_text")?.let { return it }
        return root.array("output")
            .flatMap { (it as? JsonObject)?.array("content").orEmpty() }
            .mapNotNull { (it as? JsonObject)?.string("text") }
            .joinToString("\n")
    }

    override fun extractUsage(root: JsonObject): UsageRecord =
        root.objectValue("usage").toUsage("input_tokens", "output_tokens", "total_tokens")
}

class AnthropicMessagesTranslationAdapter(executor: BoundedHttpExecutor) : JsonTranslationProtocolAdapter(executor) {
    override val protocol = TranslationProtocol.AnthropicMessages

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        resolveAnthropicEndpoint(request.endpoint)

    override fun authenticationHeaders(apiKey: String) =
        mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")

    override fun requestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            put("model", request.model)
            put("max_tokens", 16_384)
            put("system", request.systemPrompt)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", request.userPrompt) })
                },
            )
        }

    override fun extractText(root: JsonObject): String =
        root.array("content")
            .mapNotNull { (it as? JsonObject)?.takeIf { node -> node.string("type") == "text" }?.string("text") }
            .joinToString("\n")

    override fun extractUsage(root: JsonObject): UsageRecord =
        root.objectValue("usage").toUsage("input_tokens", "output_tokens", "total_tokens")
}

class ChatCompletionsTranslationAdapter(executor: BoundedHttpExecutor) : JsonTranslationProtocolAdapter(executor) {
    override val protocol = TranslationProtocol.ChatCompletions

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        resolveOpenAiStyleEndpoint(request.endpoint, "chat/completions")

    override fun authenticationHeaders(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun requestBody(request: TranslationProviderRequest) =
        buildJsonObject {
            put("model", request.model)
            put("stream", false)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", request.systemPrompt) })
                    add(buildJsonObject { put("role", "user"); put("content", request.userPrompt) })
                },
            )
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
}

class GoogleGenerateContentTranslationAdapter(executor: BoundedHttpExecutor) : JsonTranslationProtocolAdapter(executor) {
    override val protocol = TranslationProtocol.GoogleGenerateContent

    override fun endpoint(request: TranslationProviderRequest): HttpUrl =
        resolveGoogleEndpoint(request.endpoint, request.model)

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
            put("generationConfig", buildJsonObject { put("maxOutputTokens", 16_384) })
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
}

fun defaultTranslationAdapters(httpClient: SyncTextHttpClient): List<TranslationProtocolAdapter> {
    val executor = BoundedHttpExecutor(httpClient.client)
    return listOf(
        ResponsesTranslationAdapter(executor),
        AnthropicMessagesTranslationAdapter(executor),
        ChatCompletionsTranslationAdapter(executor),
        GoogleGenerateContentTranslationAdapter(executor),
    )
}

private fun resolveOpenAiStyleEndpoint(endpoint: String, resource: String): HttpUrl {
    val base = endpoint.toHttpUrl()
    val path = base.encodedPath.trimEnd('/')
    if (path.endsWith("/$resource")) return base
    if (path == "" || path == "/") return base.newBuilder().addPathSegment("v1").addPathSegments(resource).build()
    if (path.endsWith("/v1")) return base.newBuilder().addPathSegments(resource).build()
    return base
}

private fun resolveAnthropicEndpoint(endpoint: String): HttpUrl {
    val base = endpoint.toHttpUrl()
    val path = base.encodedPath.trimEnd('/')
    if (path.endsWith("/messages")) return base
    if (path == "" || path == "/") return base.newBuilder().addPathSegment("v1").addPathSegment("messages").build()
    if (path.endsWith("/v1")) return base.newBuilder().addPathSegment("messages").build()
    return base
}

private fun resolveGoogleEndpoint(endpoint: String, model: String): HttpUrl {
    if ("{model}" in endpoint) return endpoint.replace("{model}", model).toHttpUrl()
    val base = endpoint.toHttpUrl()
    val path = base.encodedPath.trimEnd('/')
    if (path.endsWith(":generateContent")) return base
    val builder = base.newBuilder()
    if (path == "" || path == "/") builder.addPathSegment("v1beta")
    if (path == "" || path == "/" || path.endsWith("/v1") || path.endsWith("/v1beta")) {
        return builder.addPathSegment("models").addPathSegment("$model:generateContent").build()
    }
    return base
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.array(name: String): JsonArray =
    get(name) as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.objectValue(name: String): JsonObject =
    get(name) as? JsonObject ?: JsonObject(emptyMap())

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
