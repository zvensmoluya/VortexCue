package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationModelCatalogTest {
    @Test
    fun `base urls resolve to a models endpoint`() {
        assertEquals(
            "https://example.test/models",
            resolveModelsEndpoint(TranslationProtocol.ChatCompletions, "https://example.test").toString(),
        )
        assertEquals(
            "https://example.test/v1/models",
            resolveModelsEndpoint(TranslationProtocol.ChatCompletions, "https://example.test/v1/").toString(),
        )
        assertEquals(
            "https://example.test/api/v1/models",
            resolveModelsEndpoint(TranslationProtocol.ChatCompletions, "https://example.test/api/v1/chat/completions").toString(),
        )
        assertEquals(
            "https://example.test/api/v2/models",
            resolveModelsEndpoint(TranslationProtocol.ChatCompletions, "https://example.test/api/v2").toString(),
        )
        assertEquals(
            "https://example.test/v1beta/models",
            resolveModelsEndpoint(TranslationProtocol.GoogleGenerateContent, "https://example.test").toString(),
        )
    }

    @Test
    fun `catalog fetch parses openai and google shaped model lists`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"data":[{"id":"model-b"},{"id":"model-a"}]}"""))
            val catalog = TranslationModelCatalog(BoundedHttpExecutor(testClient()))

            val models = catalog.fetch(TranslationProtocol.ChatCompletions, server.url("/").toString(), "secret")

            assertEquals(listOf("model-b", "model-a"), models.map { it.requestId })
            val request = server.takeRequest()
            assertEquals("/models", request.target)
            assertEquals("Bearer secret", request.headers["Authorization"])
        }
    }

    @Test
    fun `catalog falls back to v1 only when the direct models route is missing`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 404))
            server.enqueue(MockResponse(body = """{"data":[{"id":"deepseek-chat"}]}"""))
            val resolver = TranslationEndpointResolver()
            val catalog = TranslationModelCatalog(BoundedHttpExecutor(testClient()), resolver)

            assertEquals(
                listOf("deepseek-chat"),
                catalog.fetch(TranslationProtocol.ChatCompletions, server.url("/").toString(), "secret").map { it.requestId },
            )
            assertEquals("/models", server.takeRequest().target)
            assertEquals("/v1/models", server.takeRequest().target)
            assertEquals(
                server.url("/v1/chat/completions").toString(),
                resolver.requestEndpoint(TranslationProtocol.ChatCompletions, server.url("/").toString(), "chat/completions").toString(),
            )
        }
    }

    @Test
    fun `successful discovery route is reused by translation requests`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"data":[{"id":"deepseek-chat"}]}"""))
            server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"OK"}}]}"""))
            val resolver = TranslationEndpointResolver()
            val executor = BoundedHttpExecutor(testClient())
            val catalog = TranslationModelCatalog(executor, resolver)

            catalog.fetch(TranslationProtocol.ChatCompletions, server.url("/").toString(), "secret")
            ChatCompletionsTranslationAdapter(executor, resolver).translate(
                TranslationProviderRequest(
                    endpoint = server.url("/").toString(),
                    model = "deepseek-chat",
                    apiKey = "secret",
                    systemPrompt = "Reply with OK.",
                    userPrompt = "Reply with OK.",
                ),
            )

            assertEquals("/models", server.takeRequest().target)
            assertEquals("/chat/completions", server.takeRequest().target)
        }
    }

    @Test
    fun `catalog does not probe another route after authentication failure`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 401, body = "unauthorized"))
            server.enqueue(MockResponse(body = """{"data":[{"id":"should-not-be-read"}]}"""))
            val catalog = TranslationModelCatalog(BoundedHttpExecutor(testClient()))

            assertThrows(Exception::class.java) {
                runBlocking { catalog.fetch(TranslationProtocol.ChatCompletions, server.url("/").toString(), "secret") }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `catalog returns empty when provider has no model data`() {
        assertTrue(parseDiscoveredModels(TranslationProtocol.ChatCompletions, "{}".toByteArray()).isEmpty())
    }

    @Test
    fun `OpenRouter entries retain canonical ids and presentation metadata`() {
        val models =
            parseDiscoveredModels(
                TranslationProtocol.ChatCompletions,
                """
                {
                  "data": [
                    {
                      "id": "google/gemini-3.7-flash",
                      "canonical_slug": "google/gemini-3.7-flash-20260813",
                      "name": "Google: Gemini 3.7 Flash"
                    }
                  ]
                }
                """.trimIndent().toByteArray(),
            )

        assertEquals(
            DiscoveredTranslationModel(
                requestId = "google/gemini-3.7-flash",
                displayName = "Gemini 3.7 Flash",
                groupLabel = "Google",
            ),
            models.single(),
        )
    }

    @Test
    fun `OpenRouter provider endpoints retain routable tags`() {
        val providers =
            parseOpenRouterProviders(
                """
                {"data":{"endpoints":[
                  {"provider_name":"Google AI Studio","tag":"google-ai-studio/flex"},
                  {"provider_name":"Google","tag":"google-vertex/global"},
                  {"provider_name":"Google","tag":"google-vertex/global"}
                ]}}
                """.trimIndent().toByteArray(),
            )

        assertEquals(
            listOf("Google AI Studio · flex", "Google · global"),
            providers.map(OpenRouterProviderOption::displayName),
        )
        assertEquals(listOf("google-ai-studio/flex", "google-vertex/global"), providers.map(OpenRouterProviderOption::tag))
    }

    @Test
    fun `Google resource names are normalized only for Google protocol`() {
        val response =
            """
            {"models":[{"name":"models/gemini-2","displayName":"Gemini 2"}]}
            """.trimIndent().toByteArray()

        assertEquals(
            "gemini-2",
            parseDiscoveredModels(TranslationProtocol.GoogleGenerateContent, response).single().requestId,
        )
        assertEquals(
            "models/gemini-2",
            parseDiscoveredModels(TranslationProtocol.ChatCompletions, response).single().requestId,
        )
    }

    @Test
    fun `custom namespaced ids remain opaque request values`() {
        val models =
            parseDiscoveredModels(
                TranslationProtocol.ChatCompletions,
                """{"data":[{"id":"tenant/models/custom-v1"}]}""".toByteArray(),
            )

        assertEquals("tenant/models/custom-v1", models.single().requestId)
        assertEquals("tenant/models/custom-v1", models.single().displayName)
        assertEquals(null, models.single().groupLabel)
    }

    private fun testClient() =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
}
