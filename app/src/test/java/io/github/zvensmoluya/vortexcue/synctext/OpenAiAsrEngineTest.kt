package io.github.zvensmoluya.vortexcue.synctext

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiAsrEngineTest {
    @Test
    fun `openai base url resolves to the transcription route`() {
        val engine = OpenAiAsrEngine(BoundedHttpExecutor(testClient()))

        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            engine.validatedEndpoint(OpenAiAsrEngine.OpenAiBaseUrl),
        )
    }

    @Test
    fun `native OpenAI Whisper request uploads wav and requests segment timeline`() {
        val wav = File.createTempFile("vortexcue-openai-asr-", ".wav")
        try {
            wav.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))
            val buffer = Buffer()
            openAiMultipartRequestBody(
                AsrRequest(
                    profile = AsrProfile.OpenAiWhisperV1,
                    apiKey = "test-key",
                    wavFile = wav,
                    window = window(),
                    sourceLanguage = "en-US",
                ),
            ).writeTo(buffer)
            val body = buffer.readUtf8()

            assertTrue(body.contains("name=\"file\"; filename=\"${wav.name}\""))
            assertTrue(body.contains("name=\"model\""))
            assertTrue(body.contains("whisper-1"))
            assertTrue(body.contains("name=\"response_format\""))
            assertTrue(body.contains("verbose_json"))
            assertTrue(body.contains("name=\"timestamp_granularities[]\""))
            assertTrue(body.contains("segment"))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("en"))
        } finally {
            wav.delete()
        }
    }

    @Test
    fun `deprecated GPT Transcribe profile migrates to OpenAI Whisper`() {
        assertEquals(
            AsrProfile.OpenAiWhisperV1,
            AsrProfile.fromStoredValue("OpenAiGptTranscribeV1"),
        )
        assertEquals(
            listOf(AsrProfile.OpenAiWhisperV1),
            AsrProfile.forProvider(AsrProvider.OpenAi),
        )
    }

    @Test
    fun `empty segment array is a valid silent window`() {
        val engine = OpenAiAsrEngine(BoundedHttpExecutor(testClient()))
        val root = Json.parseToJsonElement("""{"segments":[]}""").jsonObject

        assertTrue(engine.parseSegmentTimeline(root).isEmpty())
    }

    @Test
    fun `explicitly blank segments are a valid silent window`() {
        val engine = OpenAiAsrEngine(BoundedHttpExecutor(testClient()))
        val root =
            Json.parseToJsonElement(
                """{"segments":[{"start":0.0,"end":0.0,"text":""},{"start":0.0,"end":1.0,"text":"  "}]}""",
            ).jsonObject

        assertTrue(engine.parseSegmentTimeline(root).isEmpty())
    }

    @Test
    fun `nonempty malformed segment array is rejected`() {
        val engine = OpenAiAsrEngine(BoundedHttpExecutor(testClient()))
        val root = Json.parseToJsonElement("""{"segments":[{"text":"missing timestamps"}]}""").jsonObject

        val failure = assertThrows(AsrException::class.java) { engine.parseSegmentTimeline(root) }
        assertEquals(GenerationErrorCategory.Timeline, failure.category)
    }

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
