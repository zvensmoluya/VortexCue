package io.github.zvensmoluya.vortexcue.synctext

import okhttp3.OkHttpClient
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenRouterAsrEngineTest {
    @Test
    fun `openrouter base url resolves to the transcription route`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())

        assertEquals(
            "https://openrouter.ai/api/v1/audio/transcriptions",
            engine.validatedEndpoint("https://openrouter.ai/api/v1"),
        )
    }

    @Test
    fun `connection test response validation rejects a successful response without transcript data`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())
        val exchange = HttpExchange(200, Headers.Builder().build(), "{}".toByteArray(), "https://example.test", 0L)

        assertThrows(AsrException::class.java) { engine.validateConnectionResponse(exchange) }
    }

    private fun testClient() =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
}
