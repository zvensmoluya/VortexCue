package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `explicitly blank segments are a valid silent window`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())
        val root =
            Json.parseToJsonElement(
                """{"segments":[{"start":0.0,"end":0.0,"text":""},{"start":0.0,"end":1.0,"text":"  "}]}""",
            ).jsonObject

        assertTrue(engine.parseSegmentTimeline(root).isEmpty())
    }

    @Test
    fun `nonblank segment without timestamps remains a protocol failure`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())
        val root = Json.parseToJsonElement("""{"segments":[{"text":"speech"}]}""").jsonObject

        val failure = assertThrows(AsrException::class.java) { engine.parseSegmentTimeline(root) }
        assertEquals(GenerationErrorCategory.Timeline, failure.category)
    }

    @Test
    fun `empty word array is a valid silent window`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())
        val root = Json.parseToJsonElement("""{"words":[]}""").jsonObject

        assertTrue(engine.parseGrokWords(root).isEmpty())
    }

    @Test
    fun `explicitly blank words are a valid silent window`() {
        val engine = OpenRouterAsrEngine(BoundedHttpExecutor(testClient()), EncodedAudioBudget())
        val root = Json.parseToJsonElement("""{"words":[{"start":0.0,"end":0.0,"word":""}]}""").jsonObject

        assertTrue(engine.parseGrokWords(root).isEmpty())
    }

    private fun testClient() = okhttp3.OkHttpClient()
}
