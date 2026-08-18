package io.github.zvensmoluya.vortexcue.synctext

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Best-effort model discovery for the product-facing translation configuration.
 *
 * Providers are allowed to omit a model catalogue. An empty result is therefore a
 * normal outcome and the UI keeps a manual model field as the fallback.
 */
class TranslationModelCatalog(
    private val executor: BoundedHttpExecutor,
) {
    suspend fun fetch(protocol: TranslationProtocol, endpoint: String, apiKey: String): List<String> {
        val requestUrl = resolveModelsEndpoint(protocol, endpoint)
        val request =
            Request.Builder()
                .url(requestUrl)
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
        if (exchange.status !in 200..299) {
            throw IOException("模型列表暂时不可用（HTTP ${exchange.status}）")
        }
        return parseModelIds(exchange.body)
    }
}

internal fun resolveModelsEndpoint(protocol: TranslationProtocol, endpoint: String): HttpUrl {
    val base = endpoint.trim().removeSuffix("/").toHttpUrl()
    val path = base.encodedPath.trimEnd('/')
    val resourceSuffixes = listOf("/responses", "/messages", "/chat/completions")
    val withoutResource =
        resourceSuffixes.firstOrNull { path.endsWith(it) }?.let { path.removeSuffix(it) } ?: path
    val builder = base.newBuilder().encodedPath(withoutResource.ifBlank { "/" })
    val currentPath = withoutResource.trimEnd('/')
    if (protocol == TranslationProtocol.GoogleGenerateContent) {
        if (currentPath.isEmpty() || currentPath == "/") builder.addPathSegment("v1beta")
        else if (!currentPath.endsWith("/v1beta")) builder.addPathSegment("v1beta")
    } else if (currentPath.isEmpty() || currentPath == "/") {
        builder.addPathSegment("v1")
    } else if (!currentPath.endsWith("/v1") && !currentPath.endsWith("/v1beta")) {
        builder.addPathSegment("v1")
    }
    return builder.addPathSegment("models").build()
}

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
