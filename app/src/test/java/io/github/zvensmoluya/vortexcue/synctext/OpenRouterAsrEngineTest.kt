package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `empty segment array is a valid silent window`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())
        val root = Json.parseToJsonElement("""{"segments":[]}""").jsonObject

        assertTrue(engine.parseSegmentTimeline(root).isEmpty())
    }

    @Test
    fun `empty word array is a valid silent window`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())
        val root = Json.parseToJsonElement("""{"words":[]}""").jsonObject

        assertTrue(engine.parseGrokWords(root).isEmpty())
    }

    private fun testClient() = okhttp3.OkHttpClient()
}
