package io.github.zvensmoluya.vortexcue.synctext

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
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
    fun `Qwen profile uses its dedicated segment request contract`() {
        val wav = File.createTempFile("vortexcue-qwen-asr-", ".wav")
        try {
            val audio = byteArrayOf(0x52, 0x49, 0x46, 0x46)
            wav.writeBytes(audio)
            val request =
                AsrRequest(
                    profile = AsrProfile.OpenRouterQwen3Asr17BV1,
                    apiKey = "test-key",
                    wavFile = wav,
                    window = window(),
                    sourceLanguage = "ja-JP",
                )
            val buffer = Buffer()
            openRouterAsrJsonRequestBody(request, OpenRouterQwen3Asr17BAdapter).writeTo(buffer)
            val root = Json.parseToJsonElement(buffer.readUtf8()).jsonObject

            assertEquals("qwen/qwen3-asr-1.7b", root["model"]?.jsonPrimitive?.content)
            assertEquals("ja", root["language"]?.jsonPrimitive?.content)
            assertEquals("verbose_json", root["response_format"]?.jsonPrimitive?.content)
            assertEquals(
                listOf("segment"),
                root["timestamp_granularities"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            val inputAudio = root["input_audio"]?.jsonObject
            assertEquals("wav", inputAudio?.get("format")?.jsonPrimitive?.content)
            assertEquals(Base64.getEncoder().encodeToString(audio), inputAudio?.get("data")?.jsonPrimitive?.content)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun `Qwen adapter parses observed OpenRouter response and language name`() {
        val root =
            Json.parseToJsonElement(
                """{
                    "text":"Good night.",
                    "language":"english",
                    "duration":1.45,
                    "segments":[{
                        "id":0,
                        "start":0,
                        "end":1.4500000476837158,
                        "text":"Good night."
                    }]
                }""".trimIndent(),
            ).jsonObject

        val parsed = OpenRouterQwen3Asr17BAdapter.parseResponse(root, requestedLanguage = null)

        assertEquals("en", parsed.detectedLanguage)
        assertEquals(listOf(AsrTimestampedToken(0L, 1_450L, "Good night.")), parsed.timeline)
    }

    @Test
    fun `Qwen adapter maps detected Japanese and Chinese language names`() {
        val japanese = response(language = "japanese")
        val chinese = response(language = "chinese")

        assertEquals("ja", OpenRouterQwen3Asr17BAdapter.parseResponse(japanese, null).detectedLanguage)
        assertEquals("zh-CN", OpenRouterQwen3Asr17BAdapter.parseResponse(chinese, null).detectedLanguage)
    }

    @Test
    fun `empty Qwen segment array is a valid silent window`() {
        val root = Json.parseToJsonElement("""{"segments":[],"language":"japanese"}""").jsonObject

        assertTrue(OpenRouterQwen3Asr17BAdapter.parseResponse(root, null).timeline.isEmpty())
    }

    @Test
    fun `explicitly blank Qwen segments are a valid silent window`() {
        val root =
            Json.parseToJsonElement(
                """{"segments":[{"start":0.0,"end":0.0,"text":""},{"start":0.0,"end":1.0,"text":"  "}]}""",
            ).jsonObject

        assertTrue(OpenRouterQwen3Asr17BAdapter.parseResponse(root, null).timeline.isEmpty())
    }

    @Test
    fun `nonblank Qwen segment without timestamps remains a timeline failure`() {
        val root = Json.parseToJsonElement("""{"segments":[{"text":"speech"}]}""").jsonObject

        val failure =
            assertThrows(AsrException::class.java) {
                OpenRouterQwen3Asr17BAdapter.parseResponse(root, requestedLanguage = null)
            }
        assertEquals(GenerationErrorCategory.Timeline, failure.category)
    }

    @Test
    fun `OpenRouter offers Qwen and retires stored Grok selection`() {
        assertEquals(
            listOf(AsrProfile.OpenRouterWhisperV1, AsrProfile.OpenRouterQwen3Asr17BV1),
            AsrProfile.forProvider(AsrProvider.OpenRouter),
        )
        assertEquals(
            AsrProfile.OpenRouterWhisperV1,
            AsrProfile.fromStoredValue("OpenRouterGrokV1"),
        )
    }

    private fun response(language: String) =
        Json.parseToJsonElement(
            """{"language":"$language","segments":[{"start":0.1,"end":0.5,"text":"text"}]}""",
        ).jsonObject

    private fun testClient() = okhttp3.OkHttpClient()

    private fun window() =
        AsrWindowPlan(
            windowId = "test",
            ordinal = 0,
            ownedStartMs = 0L,
            ownedEndMs = 1_000L,
            uploadStartMs = 0L,
            uploadEndMs = 1_000L,
            pcmStartFrame = 0L,
            pcmEndFrame = 16_000L,
            cutReason = "test",
            isLast = true,
        )
}
