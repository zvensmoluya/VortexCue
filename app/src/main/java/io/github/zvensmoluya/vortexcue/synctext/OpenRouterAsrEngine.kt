package io.github.zvensmoluya.vortexcue.synctext

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

data class AsrRequest(
    val profile: AsrProfile,
    val apiKey: String,
    val wavFile: File,
    val window: AsrWindowPlan,
    val sourceLanguage: String? = null,
    val endpoint: String = OpenRouterAsrEngine.OpenRouterTranscriptionUrl,
)

data class AsrWindowResult(
    val segments: List<AsrTimestampedToken>,
    val detectedLanguage: String?,
    val usage: UsageRecord,
    val rawResponse: ByteArray,
    val httpStatus: Int,
    val requestBytes: Long,
    val responseBytes: Long,
    val requestId: String?,
)

class AsrException(
    val category: GenerationErrorCategory,
    val retryable: Boolean,
    val shouldSplit: Boolean,
    val retryAfterMs: Long? = null,
    val httpStatus: Int? = null,
    val rawResponse: ByteArray? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

interface AsrEngine {
    suspend fun transcribe(request: AsrRequest): AsrWindowResult
}

class OpenRouterAsrEngine(
    private val executor: BoundedHttpExecutor,
    private val budget: EncodedAudioBudget,
) : AsrEngine {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    override suspend fun transcribe(request: AsrRequest): AsrWindowResult {
        require(request.wavFile.isFile) { "ASR WAV file is unavailable" }
        val body = StreamingAsrJsonRequestBody(request)
        if (body.contentLength() > MaxEncodedRequestBytes) {
            throw AsrException(
                category = GenerationErrorCategory.Media,
                retryable = false,
                shouldSplit = true,
                message = "Encoded ASR request exceeds 24 MiB profile limit",
            )
        }
        val permit = budget.acquire(body.contentLength())
        try {
            val httpRequest =
                Request.Builder()
                    .url(validatedEndpoint(request.endpoint))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${request.apiKey}")
                    .post(body)
                    .build()
            val exchange =
                try {
                    executor.execute(httpRequest)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: TranslationProtocolException) {
                    throw AsrException(
                        category = error.category,
                        retryable = error.retryable,
                        shouldSplit = false,
                        httpStatus = error.httpStatus,
                        rawResponse = error.rawResponse,
                        message = error.message ?: "ASR protocol failed",
                        cause = error,
                    )
                } catch (error: IOException) {
                    budget.degradeToSerial()
                    throw asrTransportFailure(error)
                }
            if (exchange.status !in 200..299) throw classifyFailure(exchange, request.apiKey)
            val root = parseRoot(exchange)
            val relative =
                when (request.profile) {
                    AsrProfile.OpenRouterWhisperV1 -> parseWhisperSegments(root)
                    AsrProfile.OpenRouterGrokV1 -> parseGrokWords(root)
                }
            val owned = AsrTimelineMerger.ownedSegments(request.window, relative)
            return AsrWindowResult(
                segments = owned,
                detectedLanguage = normalizeDetectedLanguage(root.string("language"), request.sourceLanguage),
                usage = root.objectValue("usage").usage(),
                rawResponse = exchange.body,
                httpStatus = exchange.status,
                requestBytes = exchange.requestBytes,
                responseBytes = exchange.body.size.toLong(),
                requestId = exchange.requestId,
            )
        } finally {
            permit.release()
        }
    }

    internal suspend fun classifyFailure(exchange: HttpExchange, apiKey: String): AsrException {
        val message = extractError(exchange.body).replace(apiKey, "[redacted]").take(600)
        val temporary = exchange.status == 408 || exchange.status == 429 || exchange.status in 500..599
        if (temporary) budget.degradeToSerial()
        return AsrException(
            category =
                when {
                    exchange.status == 401 || exchange.status == 403 -> GenerationErrorCategory.Authentication
                    exchange.status == 413 -> GenerationErrorCategory.Media
                    exchange.status == 429 -> GenerationErrorCategory.RateLimited
                    temporary -> GenerationErrorCategory.TemporaryNetwork
                    else -> GenerationErrorCategory.ModelOrProtocol
                },
            retryable = temporary,
            shouldSplit = exchange.status == 413,
            retryAfterMs = if (temporary) parseRetryAfterMs(exchange.headers["Retry-After"]) else null,
            httpStatus = exchange.status,
            rawResponse = exchange.body,
            message = message,
        )
    }

    private fun parseRoot(exchange: HttpExchange): JsonObject =
        try {
            json.parseToJsonElement(exchange.body.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Throwable) {
            throw AsrException(
                category = GenerationErrorCategory.InvalidResponse,
                retryable = false,
                shouldSplit = false,
                httpStatus = exchange.status,
                rawResponse = exchange.body,
                message = "ASR response was not a JSON object",
                cause = error,
            )
        }

    private fun parseWhisperSegments(root: JsonObject): List<AsrTimestampedToken> {
        val values = root["segments"] as? JsonArray
            ?: throw timelineFailure("Whisper response omitted segment timestamps")
        val parsed = values.mapNotNull(::parseTimestampedText)
        if (parsed.isEmpty()) throw timelineFailure("Whisper response contained no valid segments")
        return parsed
    }

    private fun parseGrokWords(root: JsonObject): List<AsrTimestampedToken> {
        val values = root["words"] as? JsonArray
            ?: throw timelineFailure("Grok response omitted word timestamps")
        val parsed = values.mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            parseTimestampedText(value, value.string("word") ?: value.string("text"))
        }
        if (parsed.isEmpty()) throw timelineFailure("Grok response contained no valid words")
        return parsed
    }

    private fun parseTimestampedText(element: kotlinx.serialization.json.JsonElement): AsrTimestampedToken? =
        (element as? JsonObject)?.let { parseTimestampedText(it, it.string("text")) }

    private fun parseTimestampedText(value: JsonObject, text: String?): AsrTimestampedToken? {
        val start = (value["start"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val end = (value["end"] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!start.isFinite() || !end.isFinite() || end <= start || text.isNullOrBlank()) return null
        return AsrTimestampedToken(secondsToMilliseconds(start), secondsToMilliseconds(end), text.trim())
    }

    private fun timelineFailure(message: String) =
        AsrException(
            category = GenerationErrorCategory.Timeline,
            retryable = false,
            shouldSplit = false,
            message = message,
        )

    private fun extractError(bytes: ByteArray): String {
        val body = bytes.toString(Charsets.UTF_8)
        return runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            val error = root?.get("error")
            when (error) {
                is JsonObject -> error.string("message")
                is JsonPrimitive -> error.contentOrNull
                else -> root?.string("message")
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "OpenRouter returned an ASR error"
    }

    private fun normalizeDetectedLanguage(detected: String?, requested: String?): String? =
        detected?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() }
            ?: LanguageTags.normalizeOrNull(requested)

    companion object {
        const val OpenRouterBaseUrl = "https://openrouter.ai/api/v1"
        const val OpenRouterTranscriptionUrl = "https://openrouter.ai/api/v1/audio/transcriptions"
        const val MaxEncodedRequestBytes = 24L * 1024L * 1024L
    }

    internal fun validatedEndpoint(value: String): String {
        val endpoint = value.trim().removeSuffix("/")
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull()
        require(
            uri?.scheme.equals("https", ignoreCase = true) &&
                !uri?.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) {
            "ASR endpoint must be HTTPS"
        }
        val path = uri.path?.trimEnd('/') ?: ""
        return when {
            path.endsWith("/audio/transcriptions") -> endpoint
            path.isEmpty() || path == "/" ->
                if (uri.host.equals("openrouter.ai", ignoreCase = true)) {
                    "$endpoint/api/v1/audio/transcriptions"
                } else {
                    "$endpoint/v1/audio/transcriptions"
                }
            path.endsWith("/v1") -> "$endpoint/audio/transcriptions"
            else -> endpoint
        }
    }
}

internal fun asrTransportFailure(error: IOException) =
    AsrException(
        category = GenerationErrorCategory.TemporaryNetwork,
        retryable = true,
        shouldSplit = false,
        message = "ASR request failed before a valid response",
        cause = error,
    )

internal fun parseRetryAfterMs(
    value: String?,
    nowMs: Long = System.currentTimeMillis(),
): Long? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val seconds = raw.toDoubleOrNull()
    val delayMs =
        if (seconds != null && seconds.isFinite() && seconds >= 0.0) {
            (seconds * 1_000.0).toLong()
        } else {
            try {
                ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs
            } catch (_: DateTimeParseException) {
                return null
            }
        }
    return delayMs.coerceIn(0L, MaxAsrRetryAfterMs)
}

private const val MaxAsrRetryAfterMs = 60_000L

private class StreamingAsrJsonRequestBody(request: AsrRequest) : RequestBody() {
    private val file = request.wavFile
    private val prefix: ByteArray
    private val suffix: ByteArray
    private val encodedLength = ((file.length() + 2L) / 3L) * 4L

    init {
        val language = request.sourceLanguage?.let { LanguageTags.normalize(it).substringBefore('-') }
        val timestampGranularity =
            if (request.profile == AsrProfile.OpenRouterWhisperV1) "segment" else "word"
        prefix =
            buildString {
                append("{\"model\":")
                append(jsonString(request.profile.modelId))
                append(",\"input_audio\":{\"data\":\"")
            }.toByteArray(Charsets.UTF_8)
        suffix =
            buildString {
                append("\",\"format\":\"wav\"}")
                if (language != null) {
                    append(",\"language\":")
                    append(jsonString(language))
                }
                append(",\"response_format\":\"verbose_json\"")
                append(",\"timestamp_granularities\":[")
                append(jsonString(timestampGranularity))
                append("]}")
            }.toByteArray(Charsets.UTF_8)
    }

    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    override fun contentLength(): Long = prefix.size + encodedLength + suffix.size

    override fun writeTo(sink: BufferedSink) {
        sink.write(prefix)
        val nonClosing =
            object : FilterOutputStream(sink.outputStream()) {
                override fun close() {
                    flush()
                }
            }
        Base64.getEncoder().wrap(nonClosing).use { encoded ->
            file.inputStream().buffered().use { input -> input.copyTo(encoded, 64 * 1024) }
        }
        sink.write(suffix)
    }

    companion object {
        private val json = Json

        private fun jsonString(value: String): String =
            json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))
    }
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objectValue(name: String): JsonObject =
    get(name) as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.usage(): UsageRecord =
    UsageRecord(
        inputTokens = (get("input_tokens") as? JsonPrimitive)?.longOrNull,
        outputTokens = (get("output_tokens") as? JsonPrimitive)?.longOrNull,
        totalTokens = (get("total_tokens") as? JsonPrimitive)?.longOrNull,
        costUsd =
            listOf("cost", "total_cost", "cost_usd")
                .firstNotNullOfOrNull { name -> (get(name) as? JsonPrimitive)?.contentOrNull },
    )
