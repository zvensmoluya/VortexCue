package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationModelCatalogTest {
    @Test
    fun `base urls resolve to a models endpoint`() {
        assertEquals(
            "https://example.test/v1/models",
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
            assertEquals("/v1/models", request.target)
            assertEquals("Bearer secret", request.headers["Authorization"])
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
