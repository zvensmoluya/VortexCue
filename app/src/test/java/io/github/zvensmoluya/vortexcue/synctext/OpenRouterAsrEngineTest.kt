package io.github.zvensmoluya.vortexcue.synctext

import org.junit.Assert.assertEquals
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

    private fun testClient() = okhttp3.OkHttpClient()
}
