package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.security.MessageDigest

data class TranslationBatchPlan(
    val ordinal: Int,
    val firstCueIndex: Int,
    val lastCueIndex: Int,
    val contextBefore: List<SubtitleCue>,
    val translateOnly: List<SubtitleCue>,
    val contextAfter: List<SubtitleCue>,
    val estimatedInputTokens: Int,
)

object CueIdNormalizer {
    fun normalize(cues: List<SubtitleCue>): List<SubtitleCue> {
        require(cues.isNotEmpty()) { "Cues must not be empty" }
        val sorted = cues.sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
        val used = mutableSetOf<String>()
        return sorted.mapIndexed { index, cue ->
            val supplied = cue.stableId?.takeIf { CueId.matches(it) && used.add(it) }
            cue.copy(stableId = supplied ?: deterministicId(index, cue).also(used::add))
        }
    }

    private fun deterministicId(index: Int, cue: SubtitleCue): String {
        val value = "$index|${cue.startMs}|${cue.endMs}|${cue.text}"
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "cue-${digest.take(20)}"
    }

    private val CueId = Regex("[A-Za-z0-9_.:-]{1,128}")
}

class TranslationBatchPlanner(
    private val maxBatchCues: Int = 120,
    private val maxEstimatedInputTokens: Int = 12_000,
    private val contextBeforeCount: Int = 80,
    private val contextAfterCount: Int = 40,
) {
    fun plan(cues: List<SubtitleCue>, promptOverhead: String = ""): List<TranslationBatchPlan> {
        val normalized = CueIdNormalizer.normalize(cues)
        val batches = mutableListOf<TranslationBatchPlan>()
        var start = 0
        while (start < normalized.size) {
            var endExclusive = minOf(normalized.size, start + maxBatchCues)
            var estimate = Int.MAX_VALUE
            while (endExclusive > start) {
                val before = normalized.subList(maxOf(0, start - contextBeforeCount), start)
                val target = normalized.subList(start, endExclusive)
                val after = normalized.subList(endExclusive, minOf(normalized.size, endExclusive + contextAfterCount))
                estimate = estimateTokens(promptOverhead, before, target, after)
                if (estimate <= maxEstimatedInputTokens || endExclusive == start + 1) break
                endExclusive -= maxOf(1, (endExclusive - start) / 4)
            }
            val before = normalized.subList(maxOf(0, start - contextBeforeCount), start).toList()
            val target = normalized.subList(start, endExclusive).toList()
            val after = normalized.subList(endExclusive, minOf(normalized.size, endExclusive + contextAfterCount)).toList()
            batches +=
                TranslationBatchPlan(
                    ordinal = batches.size,
                    firstCueIndex = start,
                    lastCueIndex = endExclusive - 1,
                    contextBefore = before,
                    translateOnly = target,
                    contextAfter = after,
                    estimatedInputTokens = estimate,
                )
            start = endExclusive
        }
        return batches
    }

    fun split(plan: TranslationBatchPlan): Pair<TranslationBatchPlan, TranslationBatchPlan> {
        require(plan.translateOnly.size > 1) { "A single-cue batch cannot be split" }
        val midpoint = plan.translateOnly.size / 2
        val leftTarget = plan.translateOnly.take(midpoint)
        val rightTarget = plan.translateOnly.drop(midpoint)
        val left =
            plan.copy(
                lastCueIndex = plan.firstCueIndex + midpoint - 1,
                translateOnly = leftTarget,
                contextAfter = (rightTarget + plan.contextAfter).take(contextAfterCount),
                estimatedInputTokens = estimateTokens("", plan.contextBefore, leftTarget, rightTarget + plan.contextAfter),
            )
        val right =
            plan.copy(
                firstCueIndex = plan.firstCueIndex + midpoint,
                translateOnly = rightTarget,
                contextBefore = (plan.contextBefore + leftTarget).takeLast(contextBeforeCount),
                estimatedInputTokens = estimateTokens("", plan.contextBefore + leftTarget, rightTarget, plan.contextAfter),
            )
        return left to right
    }

    private fun estimateTokens(
        overhead: String,
        before: List<SubtitleCue>,
        target: List<SubtitleCue>,
        after: List<SubtitleCue>,
    ): Int {
        val text = buildString {
            append(overhead)
            (before + target + after).forEach { cue ->
                append(cue.stableId)
                append(cue.text)
            }
        }
        val ascii = text.count { it.code < 128 }
        val nonAscii = text.length - ascii
        return (ascii + 3) / 4 + nonAscii + 64
    }
}

enum class TranslationPromptMode {
    Translate,
    FormatRecovery,
    Repair,
}

object TranslationPromptRenderer {
    fun render(
        plan: TranslationBatchPlan,
        sourceLanguage: String?,
        targetLanguage: String,
        mode: TranslationPromptMode,
        repairIds: Set<String> = emptySet(),
    ): String {
        val translate =
            if (mode == TranslationPromptMode.Repair) {
                plan.translateOnly.filter { it.stableId in repairIds }
            } else {
                plan.translateOnly
            }
        require(translate.isNotEmpty()) { "Translation prompt has no target cues" }
        return buildString {
            appendLine("MODE: ${mode.name.uppercase()}")
            appendLine("SOURCE_LANGUAGE: ${sourceLanguage ?: "auto"}")
            appendLine("TARGET_LANGUAGE: ${LanguageTags.normalize(targetLanguage)}")
            appendLine("OUTPUT_CONTRACT: Return only one line per TRANSLATE_ONLY cue as [id] translated text.")
            if (mode == TranslationPromptMode.FormatRecovery) {
                appendLine("RECOVERY: The previous output failed the exact line protocol. Do not add any explanation or fences.")
            }
            if (mode == TranslationPromptMode.Repair) {
                appendLine("REPAIR: Return only the listed failed ids. Do not rewrite successful ids.")
            }
            appendSection("CONTEXT_BEFORE", plan.contextBefore)
            appendSection("TRANSLATE_ONLY", translate)
            appendSection("CONTEXT_AFTER", plan.contextAfter)
        }.trimEnd()
    }

    private fun StringBuilder.appendSection(label: String, cues: List<SubtitleCue>) {
        appendLine(label)
        if (cues.isEmpty()) {
            appendLine("(none)")
        } else {
            cues.forEach { cue ->
                append('[')
                append(requireNotNull(cue.stableId))
                append("] ")
                appendLine(cue.text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim())
            }
        }
    }
}

enum class TranslationValidationIssueCode {
    BadLineFormat,
    ExplanatoryOutput,
    MissingId,
    DuplicateId,
    UnknownId,
    ContextId,
    EmptyTranslation,
    ExtremeOutput,
    ProtocolMarker,
}

data class TranslationValidationIssue(
    val code: TranslationValidationIssueCode,
    val cueId: String? = null,
    val repairable: Boolean = false,
)

data class TranslationValidationResult(
    val rows: Map<String, String>,
    val issues: List<TranslationValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

object TranslationResponseValidator {
    private val numberedLine = Regex("^\\[([A-Za-z0-9_.:-]{1,128})]\\s*(.*)$")
    private val alternateNumericLine = Regex("^\\(?\\s*(\\d+)\\s*\\)?\\s*[:：.)、-]\\s*(.*)$")
    private val protocolMarker =
        Regex("(?:<\\|(?:assistant|developer|system|analysis|commentary|final)\\|>|\\b(?:assistant|developer|system)\\s+(?:analysis|commentary|final)\\b)", RegexOption.IGNORE_CASE)

    fun validate(plan: TranslationBatchPlan, rawText: String): TranslationValidationResult {
        val expected = plan.translateOnly.associateBy { requireNotNull(it.stableId) }
        val contextIds = (plan.contextBefore + plan.contextAfter).mapNotNull(SubtitleCue::stableId).toSet()
        val parsed = mutableListOf<Pair<String, String>>()
        val issues = mutableListOf<TranslationValidationIssue>()
        rawText.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val primary = numberedLine.matchEntire(trimmed)
            val alternate = alternateNumericLine.matchEntire(trimmed)
            if (primary != null) {
                parsed += primary.groupValues[1] to primary.groupValues[2].trim()
            } else if (alternate != null && alternate.groupValues[1] in expected) {
                parsed += alternate.groupValues[1] to alternate.groupValues[2].trim()
            } else {
                issues += TranslationValidationIssue(TranslationValidationIssueCode.BadLineFormat)
            }
        }
        if (parsed.isEmpty()) {
            issues += TranslationValidationIssue(TranslationValidationIssueCode.BadLineFormat)
        }
        if (parsed.isNotEmpty() && issues.any { it.code == TranslationValidationIssueCode.BadLineFormat }) {
            issues += TranslationValidationIssue(TranslationValidationIssueCode.ExplanatoryOutput)
        }
        val counts = parsed.groupingBy(Pair<String, String>::first).eachCount()
        val rows = linkedMapOf<String, String>()
        parsed.forEach { (id, text) -> rows.putIfAbsent(id, text) }
        counts.filterValues { it > 1 }.keys.forEach { id ->
            issues += TranslationValidationIssue(TranslationValidationIssueCode.DuplicateId, id)
        }
        parsed.forEach { (id, _) ->
            when {
                id in contextIds && id !in expected ->
                    issues += TranslationValidationIssue(TranslationValidationIssueCode.ContextId, id)
                id !in expected ->
                    issues += TranslationValidationIssue(TranslationValidationIssueCode.UnknownId, id)
            }
        }
        expected.forEach { (id, sourceCue) ->
            val translated = rows[id]
            when {
                translated == null ->
                    issues += TranslationValidationIssue(TranslationValidationIssueCode.MissingId, id, repairable = true)
                translated.isBlank() ->
                    issues += TranslationValidationIssue(TranslationValidationIssueCode.EmptyTranslation, id, repairable = true)
                protocolMarker.containsMatchIn(translated) ->
                    issues += TranslationValidationIssue(TranslationValidationIssueCode.ProtocolMarker, id, repairable = true)
                isExtreme(sourceCue.text, translated) ->
                    issues += TranslationValidationIssue(TranslationValidationIssueCode.ExtremeOutput, id)
            }
        }
        return TranslationValidationResult(rows, issues.distinct())
    }

    private fun isExtreme(source: String, target: String): Boolean {
        val maximum = maxOf(1_000, source.length * 20 + 200)
        return target.length > maximum || target.length > 8_000
    }
}

class TranslationOutputException(
    val issues: List<TranslationValidationIssue>,
) : IllegalStateException("Translation response failed strict validation")

data class TranslationBatchOutput(
    val targetCues: List<SubtitleCue>,
    val usage: UsageRecord,
    val rawResponses: List<ByteArray>,
    val formatRecoveryCount: Int,
    val repairCount: Int,
)

class TranslationBatchExecutor(
    private val request: suspend (systemPrompt: String, userPrompt: String) -> TranslationProviderResponse,
) {
    suspend fun execute(
        plan: TranslationBatchPlan,
        policy: TranslationPolicy,
        sourceLanguage: String?,
        targetLanguage: String,
    ): TranslationBatchOutput {
        val rawResponses = mutableListOf<ByteArray>()
        val usages = mutableListOf<UsageRecord>()
        suspend fun call(mode: TranslationPromptMode, repairIds: Set<String> = emptySet()): TranslationProviderResponse {
            val system = listOfNotNull(policy.systemPrompt, policy.stylePrompt).joinToString("\n\n")
            val prompt = TranslationPromptRenderer.render(plan, sourceLanguage, targetLanguage, mode, repairIds)
            return request(system, prompt).also {
                rawResponses += it.rawResponse
                usages += it.usage
            }
        }

        var formatRecoveryCount = 0
        var response = call(TranslationPromptMode.Translate)
        var validation = TranslationResponseValidator.validate(plan, response.text)
        if (validation.hasFormatFailure()) {
            formatRecoveryCount = 1
            response = call(TranslationPromptMode.FormatRecovery)
            validation = TranslationResponseValidator.validate(plan, response.text)
        }

        var repairCount = 0
        if (!validation.isValid && validation.canRepair(plan.translateOnly.size)) {
            val failedIds = validation.issues.mapNotNull { it.cueId.takeIf { _ -> it.repairable } }.toSet()
            val repairResponse = call(TranslationPromptMode.Repair, failedIds)
            val repairPlan = plan.copy(translateOnly = plan.translateOnly.filter { it.stableId in failedIds })
            val repairValidation = TranslationResponseValidator.validate(repairPlan, repairResponse.text)
            if (!repairValidation.isValid) throw TranslationOutputException(repairValidation.issues)
            repairCount = 1
            val merged = validation.rows.toMutableMap().apply { putAll(repairValidation.rows) }
            val synthetic = plan.translateOnly.joinToString("\n") { cue ->
                "[${cue.stableId}] ${merged[cue.stableId].orEmpty()}"
            }
            validation = TranslationResponseValidator.validate(plan, synthetic)
        }
        if (!validation.isValid) throw TranslationOutputException(validation.issues)

        val targetCues =
            plan.translateOnly.map { source ->
                source.copy(text = requireNotNull(validation.rows[source.stableId]))
            }
        return TranslationBatchOutput(
            targetCues = targetCues,
            usage = usages.sumRealUsage(),
            rawResponses = rawResponses,
            formatRecoveryCount = formatRecoveryCount,
            repairCount = repairCount,
        )
    }

    private fun TranslationValidationResult.hasFormatFailure(): Boolean =
        issues.any {
            it.code == TranslationValidationIssueCode.BadLineFormat ||
                it.code == TranslationValidationIssueCode.ExplanatoryOutput
        }

    private fun TranslationValidationResult.canRepair(batchSize: Int): Boolean {
        if (issues.isEmpty() || issues.any { !it.repairable }) return false
        val failed = issues.mapNotNull(TranslationValidationIssue::cueId).distinct().size
        return failed in 1..minOf(3, maxOf(1, (batchSize + 9) / 10))
    }
}
