package io.github.zvensmoluya.vortexcue.synctext

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody

/** OpenAI's native transcription API uses multipart upload rather than OpenRouter's JSON audio payload. */
class OpenAiAsrEngine(
    private val executor: BoundedHttpExecutor,
) : AsrEngine {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    override suspend fun transcribe(request: AsrRequest): AsrWindowResult {
        require(request.profile.provider == AsrProvider.OpenAi) {
            "OpenAI ASR engine cannot handle ${request.profile.provider} profiles"
        }
        require(request.wavFile.isFile) { "ASR WAV file is unavailable" }
        if (request.wavFile.length() > MaxUploadBytes) {
            throw AsrException(
                category = GenerationErrorCategory.Media,
                retryable = false,
                shouldSplit = true,
                message = "ASR WAV exceeds the 24 MiB upload limit",
            )
        }

        val body = openAiMultipartRequestBody(request)
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
                    requestId = error.requestId,
                    rawResponse = error.rawResponse,
                    message = error.message ?: "OpenAI ASR protocol failed",
                    cause = error,
                )
            } catch (error: IOException) {
                throw asrTransportFailure(error)
            }
        if (exchange.status !in 200..299) throw classifyFailure(exchange, request.apiKey)

        val root = parseRoot(exchange)
        val relative =
            try {
                parseSegmentTimeline(root)
            } catch (error: AsrException) {
                throw error.withResponseContext(exchange)
            }
        return AsrWindowResult(
            segments = AsrTimelineMerger.windowSegments(request.window, relative),
            detectedLanguage =
                root.string("language")
                    ?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() }
                    ?: LanguageTags.normalizeOrNull(request.sourceLanguage),
            usage = UsageRecord(),
            rawResponse = exchange.body,
            httpStatus = exchange.status,
            requestBytes = exchange.requestBytes,
            responseBytes = exchange.body.size.toLong(),
            requestId = exchange.requestId,
        )
    }

    internal fun classifyFailure(exchange: HttpExchange, apiKey: String): AsrException {
        val message = extractError(exchange.body).replace(apiKey, "[redacted]").take(MaxErrorMessageLength)
        val temporary = exchange.status == 408 || exchange.status == 429 || exchange.status in 500..599
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
            requestId = exchange.requestId,
            rawResponse = exchange.body,
            message = message,
        )
    }

    internal fun validatedEndpoint(value: String): String {
        val endpoint = value.trim().removeSuffix("/")
        val uri =
            requireNotNull(runCatching { java.net.URI(endpoint) }.getOrNull()) {
                "OpenAI ASR endpoint must use https://api.openai.com/v1"
            }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(OpenAiHost, ignoreCase = true) &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) {
            "OpenAI ASR endpoint must use https://api.openai.com/v1"
        }
        val path = uri.path?.trimEnd('/').orEmpty()
        return when (path) {
            "/v1" -> "$endpoint/audio/transcriptions"
            "/v1/audio/transcriptions" -> endpoint
            else -> throw IllegalArgumentException("OpenAI ASR endpoint must use /v1")
        }
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
                requestId = exchange.requestId,
                rawResponse = exchange.body,
                message = "OpenAI ASR response was not a JSON object",
                cause = error,
            )
        }

    internal fun parseSegmentTimeline(root: JsonObject): List<AsrTimestampedToken> {
        val values = root["segments"] as? JsonArray
            ?: throw timelineFailure("OpenAI ASR response omitted segment timestamps")
        if (values.isEmpty()) return emptyList()
        if (values.containsOnlyExplicitBlankText("text")) return emptyList()
        val parsed =
            values.mapNotNull { element ->
                val value = element as? JsonObject ?: return@mapNotNull null
                val start = (value["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val end = (value["end"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val text = value.string("text")?.trim().orEmpty()
                if (!start.isFinite() || !end.isFinite() || end <= start || text.isBlank()) return@mapNotNull null
                AsrTimestampedToken(secondsToMilliseconds(start), secondsToMilliseconds(end), text)
            }
        if (parsed.isEmpty()) throw timelineFailure("OpenAI ASR response contained no valid segments")
        return parsed
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
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "OpenAI returned an ASR error"
    }

    companion object {
        const val OpenAiBaseUrl = "https://api.openai.com/v1"
        const val OpenAiTranscriptionUrl = "$OpenAiBaseUrl/audio/transcriptions"
        const val MaxUploadBytes = 24L * 1024L * 1024L

        private const val OpenAiHost = "api.openai.com"
        private const val MaxErrorMessageLength = 600
    }
}

internal fun openAiMultipartRequestBody(request: AsrRequest): RequestBody {
    val language = request.sourceLanguage?.let { LanguageTags.normalize(it).substringBefore('-') }
    return MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", request.wavFile.name, request.wavFile.asRequestBody("audio/wav".toMediaType()))
        .addFormDataPart("model", request.profile.modelId)
        .addFormDataPart("response_format", "verbose_json")
        .addFormDataPart("timestamp_granularities[]", "segment")
        .apply { language?.let { addFormDataPart("language", it) } }
        .build()
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull
