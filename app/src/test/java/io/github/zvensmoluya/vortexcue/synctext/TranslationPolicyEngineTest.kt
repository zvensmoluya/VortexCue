package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPolicyEngineTest {
    @Test
    fun `language tags preserve script while bare zh becomes simplified Chinese`() {
        assertEquals("zh-CN", LanguageTags.normalize("zh"))
        assertEquals("zh-Hant", LanguageTags.normalize("zh_Hant"))
        assertTrue(LanguageTags.isSimplifiedChinese("zh-Hans"))
        assertFalse(LanguageTags.isSimplifiedChinese("zh-Hant"))
    }

    @Test
    fun `bundled policy hashes select Chinese style only for simplified Chinese`() {
        val registry = TranslationPolicyRegistry.forTesting(::promptBytes)

        val simplified = registry.resolve("zh-CN")
        val traditional = registry.resolve("zh-Hant")
        val generic = registry.resolve("ja")

        assertEquals(TranslationPolicyRegistry.OriginRevision, simplified.originRevision)
        assertEquals("style.zh-CN.v1", simplified.stylePromptId)
        assertNull(traditional.stylePromptId)
        assertNull(generic.stylePromptId)
        registry.verifyAll()
    }

    @Test
    fun `batch planner uses source-only eighty forty context and bounded target size`() {
        val cues = CueIdNormalizer.normalize((0 until 250).map(::cue))
        val plans = TranslationBatchPlanner().plan(cues)

        assertEquals(3, plans.size)
        assertEquals(120, plans[0].translateOnly.size)
        assertEquals(40, plans[0].contextAfter.size)
        assertEquals(80, plans[1].contextBefore.size)
        assertEquals(10, plans[1].contextAfter.size)
        assertEquals(80, plans[2].contextBefore.size)
        assertTrue(plans.all { it.translateOnly.size <= 120 })

        val prompt =
            TranslationPromptRenderer.render(
                plans[1],
                sourceLanguage = "en",
                targetLanguage = "zh-CN",
                mode = TranslationPromptMode.Translate,
            )
        assertTrue(prompt.contains("CONTEXT_BEFORE"))
        assertTrue(prompt.contains("TRANSLATE_ONLY"))
        assertTrue(prompt.contains("CONTEXT_AFTER"))
        assertTrue(prompt.contains("[${plans[1].contextBefore.first().stableId}] source-40"))
    }

    @Test
    fun `strict validation rejects duplicate unknown context empty and extreme rows`() {
        val all = CueIdNormalizer.normalize((0 until 4).map(::cue))
        val plan =
            TranslationBatchPlan(
                ordinal = 0,
                firstCueIndex = 1,
                lastCueIndex = 2,
                contextBefore = listOf(all[0]),
                translateOnly = listOf(all[1], all[2]),
                contextAfter = listOf(all[3]),
                estimatedInputTokens = 10,
            )
        val firstId = requireNotNull(all[1].stableId)
        val contextId = requireNotNull(all[0].stableId)
        val response = "[$firstId] ok\n[$firstId] duplicate\n[$contextId] context\n[unknown] no"

        val issues = TranslationResponseValidator.validate(plan, response).issues.map { it.code }.toSet()

        assertTrue(TranslationValidationIssueCode.DuplicateId in issues)
        assertTrue(TranslationValidationIssueCode.ContextId in issues)
        assertTrue(TranslationValidationIssueCode.UnknownId in issues)
        assertTrue(TranslationValidationIssueCode.MissingId in issues)
    }

    @Test
    fun `empty output gets one format recovery`() = runBlocking {
        val plan = singlePlan(2)
        var calls = 0
        val executor =
            TranslationBatchExecutor { _, prompt ->
                calls += 1
                val text =
                    if (calls == 1) "" else plan.translateOnly.joinToString("\n") { "[${it.stableId}] translated" }
                TranslationProviderResponse(
                    text = text,
                    usage = UsageRecord(totalTokens = 1),
                    rawResponse = text.toByteArray(),
                    httpStatus = 200,
                    requestBytes = prompt.length.toLong(),
                    responseBytes = text.length.toLong(),
                    requestId = null,
                    endpoint = "https://example.test/v1/responses",
                )
            }

        val result = executor.execute(plan, testPolicy(), "en", "zh-CN")

        assertEquals(2, calls)
        assertEquals(1, result.formatRecoveryCount)
        assertEquals(0, result.repairCount)
        assertEquals(2L, result.usage.totalTokens)
    }

    @Test
    fun `one missing id is repaired without rewriting successful rows`() = runBlocking {
        val plan = singlePlan(10)
        val missing = requireNotNull(plan.translateOnly.last().stableId)
        var calls = 0
        val executor =
            TranslationBatchExecutor { _, _ ->
                calls += 1
                val text =
                    if (calls == 1) {
                        plan.translateOnly.dropLast(1).joinToString("\n") { "[${it.stableId}] ok-${it.stableId}" }
                    } else {
                        "[$missing] repaired"
                    }
                TranslationProviderResponse(text, UsageRecord(), text.toByteArray(), 200, 1L, text.length.toLong(), null, "https://example.test")
            }

        val result = executor.execute(plan, testPolicy(), "en", "fr")

        assertEquals(2, calls)
        assertEquals(0, result.formatRecoveryCount)
        assertEquals(1, result.repairCount)
        assertEquals("repaired", result.targetCues.last().text)
    }

    private fun singlePlan(size: Int): TranslationBatchPlan =
        TranslationBatchPlanner(maxBatchCues = size).plan((0 until size).map(::cue)).single()

    private fun cue(index: Int) = SubtitleCue(index * 1_000L, index * 1_000L + 900L, "source-$index")

    private fun testPolicy() =
        TranslationPolicy(
            policyId = "test",
            version = 1,
            originRevision = "test",
            systemPromptId = "system",
            systemPrompt = "Translate.",
            systemPromptSha256 = "hash",
            stylePromptId = null,
            stylePrompt = null,
            stylePromptSha256 = null,
        )

    private fun promptBytes(id: String): ByteArray {
        val name =
            when (id) {
                "system.v1" -> "translation_system_v1.md"
                "style.zh-CN.v1" -> "translation_style_zh_cn_v1.md"
                else -> error("Unexpected prompt $id")
            }
        return File("src/main/res/raw/$name").readBytes()
    }
}
