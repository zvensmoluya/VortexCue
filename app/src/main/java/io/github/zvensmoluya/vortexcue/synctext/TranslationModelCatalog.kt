package io.github.zvensmoluya.vortexcue.synctext

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Best-effort model discovery for the product-facing translation configuration.
 *
 * Providers are allowed to omit a model catalogue. An empty result is therefore a
 * normal outcome and the UI keeps a manual model field as the fallback.
 */
class TranslationModelCatalog(
    private val executor: BoundedHttpExecutor,
    private val endpointResolver: TranslationEndpointResolver = TranslationEndpointResolver(),
) {
    suspend fun fetch(protocol: TranslationProtocol, endpoint: String, apiKey: String): List<String> {
        var lastRouteStatus: Int? = null
        endpointResolver.modelCandidates(protocol, endpoint).forEach { route ->
            val request =
                Request.Builder()
                    .url(route.modelsUrl)
                    .get()
                    .apply {
                        when (protocol) {
                            TranslationProtocol.AnthropicMessages -> header("x-api-key", apiKey)
                            TranslationProtocol.GoogleGenerateContent -> header("x-goog-api-key", apiKey)
                            TranslationProtocol.Responses,
                            TranslationProtocol.ChatCompletions,
                            -> header("Authorization", "Bearer $apiKey")
                        }
                    }
                    .build()
            val exchange = executor.execute(request)
            if (exchange.status in 200..299) {
                endpointResolver.remember(protocol, endpoint, route.requestBase)
                return parseModelIds(exchange.body)
            }
            lastRouteStatus = exchange.status
            if (exchange.status !in RouteFallbackStatuses) {
                throw IOException("模型列表请求失败（HTTP ${exchange.status}）")
            }
        }
        throw IOException("服务未提供模型列表，可以直接填写模型（HTTP ${lastRouteStatus ?: 404}）")
    }

    companion object {
        private val RouteFallbackStatuses = setOf(404, 405)
    }
}

internal fun resolveModelsEndpoint(protocol: TranslationProtocol, endpoint: String): HttpUrl =
    TranslationEndpointResolver().modelCandidates(protocol, endpoint).first().modelsUrl

internal fun parseModelIds(bytes: ByteArray): List<String> {
    val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
        ?: return emptyList()
    val candidates = buildList {
        addAll(root["data"].asModelArray())
        addAll(root["models"].asModelArray())
    }
    return candidates
        .mapNotNull { item ->
            val id = item["id"]?.jsonPrimitive?.contentOrNull
                ?: item["name"]?.jsonPrimitive?.contentOrNull
            id?.removePrefix("models/")?.trim()?.takeIf(String::isNotBlank)
        }
        .distinct()
        .sorted()
}

private fun kotlinx.serialization.json.JsonElement?.asModelArray(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
