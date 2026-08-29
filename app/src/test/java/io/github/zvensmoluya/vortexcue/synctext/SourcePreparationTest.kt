package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.text.SourcePatchOperation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePreparationTest {
    @Test
    fun `preparation policy prefers low reasoning`() {
        assertEquals(TranslationReasoningLevel.Low, SourcePreparationPolicyRegistry.ReasoningLevel)
    }

    @Test
    fun `explicit reasoning rejection retries once without provider controls`() = runBlocking {
        val levels = mutableListOf<TranslationReasoningLevel?>()
        var fallbackCount = 0
        val request =
            TranslationProviderRequest(
                endpoint = "https://example.test",
                model = "model",
                apiKey = "secret",
                systemPrompt = "prepare",
                userPrompt = "timeline",
                reasoningLevel = TranslationReasoningLevel.Low,
            )

        val response =
            requestSourcePreparationWithReasoningFallback(
                request = request,
                onFallback = { fallbackCount += 1 },
            ) { effectiveRequest ->
                levels += effectiveRequest.reasoningLevel
                if (effectiveRequest.reasoningLevel != null) {
                    throw TranslationProtocolException(
                        category = GenerationErrorCategory.ModelOrProtocol,
                        retryable = false,
                        capacityExceeded = false,
                        httpStatus = 400,
                        rawResponse = "unknown parameter reasoning_effort".toByteArray(),
                        message = "reasoning is unsupported",
                    )
                }
                TranslationProviderResponse(
                    text = "{}",
                    usage = UsageRecord(totalTokens = 1L),
                    rawResponse = byteArrayOf(),
                    httpStatus = 200,
                    requestBytes = 1L,
                    responseBytes = 2L,
                    requestId = null,
                    endpoint = effectiveRequest.endpoint,
                )
            }

        assertEquals(listOf<TranslationReasoningLevel?>(TranslationReasoningLevel.Low, null), levels)
        assertEquals(1, fallbackCount)
        assertEquals("{}", response.text)
    }

    @Test
    fun `ordinary model errors do not trigger a compatibility request`() = runBlocking {
        var calls = 0
        val request =
            TranslationProviderRequest(
                endpoint = "https://example.test",
                model = "model",
                apiKey = "secret",
                systemPrompt = "prepare",
                userPrompt = "timeline",
                reasoningLevel = TranslationReasoningLevel.Low,
            )

        val failure =
            runCatching {
                requestSourcePreparationWithReasoningFallback(request) {
                    calls += 1
                    throw TranslationProtocolException(
                        category = GenerationErrorCategory.ModelOrProtocol,
                        retryable = false,
                        capacityExceeded = false,
                        httpStatus = 400,
                        rawResponse = "invalid response format".toByteArray(),
                        message = "invalid response format",
                    )
                }
            }.exceptionOrNull()

        assertTrue(failure is TranslationProtocolException)
        assertEquals(1, calls)
    }

    @Test
    fun `detector drops marked sound events but only flags whisper phrases`() {
        val cues =
            listOf(
                cue("cue-1", 0L, 1_000L, "*Sigh*"),
                cue("cue-2", 1_000L, 2_000L, "ご視聴ありがとうございました。"),
                cue("cue-3", 2_000L, 3_000L, "ありがとうございました"),
                cue("cue-4", 3_000L, 4_000L, "次の話をしましょう。"),
            )

        val signals = SourcePreparationSignalDetector.detect(cues)

        assertEquals(listOf("cue-1"), signals.policyPatches.map { it.cueId })
        assertEquals(SourcePatchOperation.Drop, signals.policyPatches.single().operation)
        assertTrue(
            signals.flags.any {
                it.cueId == "cue-2" && it.kind == SourcePreparationFlagKind.SuspiciousPattern
            },
        )
        assertFalse(signals.policyPatches.any { it.cueId == "cue-2" })
        assertTrue(
            signals.flags.any {
                it.cueId == "cue-3" &&
                    it.kind == SourcePreparationFlagKind.SuspiciousPattern &&
                    it.patternId == "whisper.jp.standalone_thanks.v1"
            },
        )
        assertFalse(signals.policyPatches.any { it.cueId == "cue-3" })
    }

    @Test
    fun `prompt exposes the complete frozen timeline and deterministic flags`() {
        val cues =
            listOf(
                cue("cue-1", 100L, 900L, "*Humming*"),
                cue("cue-2", 1_200L, 2_000L, "こんにちは"),
            )
        val signals = SourcePreparationSignalDetector.detect(cues)

        val prompt = SourcePreparationPromptRenderer.render(cues, "ja", signals.flags)

        assertTrue(prompt.contains("CUE_COUNT: 2"))
        assertTrue(prompt.contains("[cue-1] 100 --> 900 <POLICY_DROP:marked_sound_event.v1> *Humming*"))
        assertTrue(prompt.contains("[cue-2] 1200 --> 2000 こんにちは"))
    }

    @Test
    fun `parser and applier keep ids and timing while applying sparse source patches`() {
        val source =
            listOf(
                cue("cue-1", 100L, 900L, "*Sigh*"),
                cue("cue-2", 1_200L, 2_000L, "たなかさん"),
                cue("cue-3", 2_500L, 3_400L, "また明日"),
            )
        val response =
            """
            {
              "patches": [
                {"cue_id":"cue-2","operation":"REPLACE","replacement_text":"田中さん"}
              ],
              "source_context": {
                "style_profile": {
                  "content_mode":"dialogue",
                  "register":"casual",
                  "politeness":null,
                  "utterance_shape":"short turns",
                  "preserve_traits":["hesitation"]
                },
                "entity_mentions": [
                  {
                    "entity_id":"person-1",
                    "kind":"PERSON",
                    "observed_forms":["田中さん"],
                    "spelling_status":"observed",
                    "evidence_cue_ids":["cue-2"]
                  }
                ],
                "alias_clusters": [],
                "addressing_functions": [
                  {"source_form":"さん","function":"polite familiar address","evidence_cue_ids":["cue-2"]}
                ]
              }
            }
            """.trimIndent()

        val model = SourcePreparationResponseParser.parse(response, source, setOf("cue-1"))
        val combined = SourcePreparationSignalDetector.detect(source).policyPatches + model.patches
        val refined = SourcePatchApplier.apply(source, combined)

        assertEquals(listOf("cue-2", "cue-3"), refined.map { it.stableId })
        assertEquals(listOf(1_200L, 2_500L), refined.map { it.startMs })
        assertEquals(listOf(2_000L, 3_400L), refined.map { it.endMs })
        assertEquals("田中さん", refined.first().text)
        assertEquals("dialogue", model.sourceContext.styleProfile.contentMode)
        assertEquals("person-1", model.sourceContext.entityMentions.single().entityId)
    }

    @Test
    fun `parser rejects unknown duplicate and policy owned patches`() {
        val source = listOf(cue("cue-1", 0L, 1_000L, "alpha"), cue("cue-2", 1_000L, 2_000L, "beta"))
        fun response(patches: String) = """{"patches":[$patches],"source_context":{}}"""

        assertThrows(SourcePreparationOutputException::class.java) {
            SourcePreparationResponseParser.parse(
                response("""{"cue_id":"missing","operation":"DROP"}"""),
                source,
                emptySet(),
            )
        }
        assertThrows(SourcePreparationOutputException::class.java) {
            SourcePreparationResponseParser.parse(
                response(
                    """{"cue_id":"cue-1","operation":"DROP"},{"cue_id":"cue-1","operation":"REPLACE","replacement_text":"x"}""",
                ),
                source,
                emptySet(),
            )
        }
        assertThrows(SourcePreparationOutputException::class.java) {
            SourcePreparationResponseParser.parse(
                response("""{"cue_id":"cue-1","operation":"DROP"}"""),
                source,
                setOf("cue-1"),
            )
        }
        assertThrows(SourcePreparationOutputException::class.java) {
            SourcePreparationResponseParser.parse(
                """{"patches":[],"source_context":{},"reasoning":"hidden"}""",
                source,
                emptySet(),
            )
        }
    }

    private fun cue(id: String, startMs: Long, endMs: Long, text: String) =
        SubtitleCue(startMs, endMs, text, id)
}
