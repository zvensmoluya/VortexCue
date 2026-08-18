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

            assertEquals(listOf("model-a", "model-b"), models)
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
                catalog.fetch(TranslationProtocol.ChatCompletions, server.url("/").toString(), "secret"),
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
        assertTrue(parseModelIds("{}".toByteArray()).isEmpty())
        assertEquals(listOf("gemini-2"), parseModelIds("""{"models":[{"name":"models/gemini-2"}]}""".toByteArray()))
    }

    private fun testClient() =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
}
