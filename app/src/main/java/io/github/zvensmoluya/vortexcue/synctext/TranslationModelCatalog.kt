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
    suspend fun fetch(protocol: TranslationProtocol, endpoint: String, apiKey: String): List<DiscoveredTranslationModel> {
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
                return parseDiscoveredModels(protocol, exchange.body)
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

/** A catalogue entry keeps the provider's request identifier separate from presentation metadata. */
data class DiscoveredTranslationModel(
    val requestId: String,
    val displayName: String,
    val groupLabel: String? = null,
)

internal fun resolveModelsEndpoint(protocol: TranslationProtocol, endpoint: String): HttpUrl =
    TranslationEndpointResolver().modelCandidates(protocol, endpoint).first().modelsUrl

internal fun parseDiscoveredModels(
    protocol: TranslationProtocol,
    bytes: ByteArray,
): List<DiscoveredTranslationModel> {
    val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
        ?: return emptyList()
    val candidates = buildList {
        addAll(root["data"].asModelArray())
        addAll(root["models"].asModelArray())
    }
    return candidates
        .mapNotNull { item ->
            val rawId =
                item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["name"]?.jsonPrimitive?.contentOrNull
            val requestId = rawId
                ?.trim()
                ?.let { id ->
                    if (protocol == TranslationProtocol.GoogleGenerateContent) {
                        id.removePrefix("models/")
                    } else {
                        id
                    }
                }
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val responseName =
                item["displayName"]?.jsonPrimitive?.contentOrNull
                    ?: item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { item["id"] != null }
            val openRouterMetadata = item["canonical_slug"]?.jsonPrimitive?.contentOrNull
            val authorSlug =
                requestId
                    .substringBefore('/', missingDelimiterValue = "")
                    .removePrefix("~")
                    .takeIf { openRouterMetadata != null && it.isNotBlank() }
            val namePrefix = responseName
                ?.substringBefore(':', missingDelimiterValue = "")
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val groupLabel = authorSlug?.let { namePrefix ?: it }
            val displayName =
                responseName
                    ?.trim()
                    ?.let { name ->
                        if (groupLabel != null && ':' in name) name.substringAfter(':').trim() else name
                    }
                    ?.takeIf(String::isNotBlank)
                    ?: requestId
            DiscoveredTranslationModel(
                requestId = requestId,
                displayName = displayName,
                groupLabel = groupLabel,
            )
        }
        .distinctBy(DiscoveredTranslationModel::requestId)
}

private fun kotlinx.serialization.json.JsonElement?.asModelArray(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
