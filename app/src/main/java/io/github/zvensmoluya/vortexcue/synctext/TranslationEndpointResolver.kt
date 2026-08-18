package io.github.zvensmoluya.vortexcue.synctext

import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Resolves provider routes without assuming that every OpenAI-compatible service uses /v1.
 *
 * The user-entered endpoint remains the source of truth. Model discovery may remember the
 * prefix that answered successfully so later POST requests use the same route.
 */
class TranslationEndpointResolver {
    private val resolvedBases = ConcurrentHashMap<ResolvedEndpointKey, HttpUrl>()

    internal fun modelCandidates(protocol: TranslationProtocol, endpoint: String): List<TranslationModelRoute> {
        val base = normalizeBase(endpoint)
        if (hasExplicitApiVersion(base)) {
            return listOf(TranslationModelRoute(base.addPath("models"), base))
        }
        return when (protocol) {
            TranslationProtocol.GoogleGenerateContent -> {
                val versionedBase = base.addPath("v1beta")
                listOf(TranslationModelRoute(versionedBase.addPath("models"), versionedBase))
            }
            TranslationProtocol.Responses,
            TranslationProtocol.ChatCompletions,
            TranslationProtocol.AnthropicMessages,
            -> {
                val direct = TranslationModelRoute(base.addPath("models"), base)
                val versionedBase = base.addPath("v1")
                listOf(direct, TranslationModelRoute(versionedBase.addPath("models"), versionedBase))
            }
        }
    }

    internal fun remember(protocol: TranslationProtocol, endpoint: String, resolvedBase: HttpUrl) {
        resolvedBases[cacheKey(protocol, endpoint)] = resolvedBase
    }

    internal fun requestEndpoint(protocol: TranslationProtocol, endpoint: String, resource: String, model: String? = null): HttpUrl {
        if (protocol == TranslationProtocol.GoogleGenerateContent && "{model}" in endpoint && model != null) {
            val templated = endpoint.replace("{model}", model).toHttpUrl()
            if (resource.endsWith(":streamGenerateContent") && templated.encodedPath.endsWith(":generateContent")) {
                return templated.newBuilder()
                    .encodedPath(templated.encodedPath.removeSuffix(":generateContent") + ":streamGenerateContent")
                    .build()
            }
            return templated
        }
        val normalizedBase = normalizeBase(endpoint)
        val base =
            resolvedBases[cacheKey(protocol, endpoint)]
                ?: defaultRequestBase(protocol, normalizedBase)
        return base.addPath(resource)
    }

    private fun normalizeBase(endpoint: String): HttpUrl {
        val parsed = endpoint.trim().removeSuffix("/").replace("{model}", "model").toHttpUrl()
        val path = parsed.encodedPath.trimEnd('/')
        val operationSuffixes = listOf("/responses", "/messages", "/chat/completions")
        val withoutOperation =
            operationSuffixes.firstOrNull { path.endsWith(it) }?.let(path::removeSuffix)
                ?: when {
                    path.endsWith("/models") -> path.removeSuffix("/models")
                    "/models/" in path -> path.substringBefore("/models/")
                    else -> path
                }
        return parsed.newBuilder().encodedPath(withoutOperation.ifBlank { "/" }).build()
    }

    private fun defaultRequestBase(protocol: TranslationProtocol, base: HttpUrl): HttpUrl =
        when {
            hasExplicitApiVersion(base) -> base
            protocol == TranslationProtocol.GoogleGenerateContent -> base.addPath("v1beta")
            protocol == TranslationProtocol.AnthropicMessages -> base.addPath("v1")
            else -> base
        }

    private fun cacheKey(protocol: TranslationProtocol, endpoint: String): ResolvedEndpointKey =
        ResolvedEndpointKey(protocol, normalizeBase(endpoint).toString())

    private fun hasExplicitApiVersion(base: HttpUrl): Boolean {
        val path = base.encodedPath.trimEnd('/')
        return ApiVersionPattern.matches(path)
    }

    private fun HttpUrl.addPath(path: String): HttpUrl =
        newBuilder().addPathSegments(path.trim('/')).build()

    private data class ResolvedEndpointKey(
        val protocol: TranslationProtocol,
        val endpoint: String,
    )

    private companion object {
        val ApiVersionPattern = Regex(".*/v\\d+[a-z]*$")
    }
}

internal data class TranslationModelRoute(
    val modelsUrl: HttpUrl,
    val requestBase: HttpUrl,
)
