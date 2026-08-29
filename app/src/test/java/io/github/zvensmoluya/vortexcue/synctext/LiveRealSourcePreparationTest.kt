package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleParser
import io.github.zvensmoluya.vortexcue.text.CanonicalTextNormalizer
import io.github.zvensmoluya.vortexcue.text.SourcePatchOperation
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in paid probe that applies the production preparation contract to one exported ASR SRT. */
class LiveRealSourcePreparationTest {
    @Test
    fun `live model prepares an exported ASR timeline`() = runBlocking {
        val config = LiveProviderProbeConfig.fromEnvironment()
        assumeTrue("Set VORTEX_LIVE_TEST=1 to run live provider tests", config.enabled)
        val sourceFile = config.sourcePreparationFile
        assumeTrue("Set VORTEX_LIVE_SOURCE_SRT to an exported ASR SRT", sourceFile != null)
        requireNotNull(sourceFile)
        require(sourceFile.isFile) { "Exported ASR SRT is unavailable: ${sourceFile.absolutePath}" }
        val apiKey = requireNotNull(config.translationApiKey) {
            "VORTEX_LIVE_TRANSLATION_API_KEY is required"
        }
        val endpoint = requireNotNull(config.translationEndpoint) {
            "VORTEX_LIVE_TRANSLATION_ENDPOINT is required"
        }
        val model = requireNotNull(config.translationModel) {
            "VORTEX_LIVE_TRANSLATION_MODEL is required"
        }
        val source =
            CanonicalTextNormalizer.normalizeGenerated(
                SubtitleParser.parse(sourceFile.name, sourceFile.readBytes()),
            ).toSubtitleCues()
        val signals = SourcePreparationSignalDetector.detect(source)
        val policy = SourcePreparationPolicyRegistry.forTesting(::loadLiveSourcePreparationPrompt).resolve()
        val httpClientWrapper = SyncTextHttpClient("live-real-source-preparation")
        val httpClient = httpClientWrapper.client
        try {
            val gateway =
                TranslationNetworkGateway(
                    TranslationAdapterRegistry(
                        defaultTranslationAdapters(httpClientWrapper, TranslationEndpointResolver()),
                    ),
                    streamResponses = true,
                )
            val response =
                gateway.translate(
                    protocol = config.translationProtocol,
                    request =
                        TranslationProviderRequest(
                            endpoint = endpoint,
                            model = model,
                            apiKey = apiKey,
                            systemPrompt = policy.systemPrompt,
                            userPrompt =
                                SourcePreparationPromptRenderer.render(
                                    source,
                                    sourceLanguage = config.mediaSourceLanguage,
                                    flags = signals.flags,
                                ),
                            reasoningLevel = config.sourcePreparationReasoningLevel,
                            userAgent = config.userAgent,
                        ),
                )
            val modelOutput =
                SourcePreparationResponseParser.parse(
                    response = response.text,
                    sourceCues = source,
                    policyDropIds = signals.policyPatches.mapTo(mutableSetOf()) { it.cueId },
                )
            val combinedPatches = signals.policyPatches + modelOutput.patches
            val published = SourcePatchApplier.apply(source, combinedPatches)
            val report =
                reportFile(
                    sourceFile,
                    policy.version,
                    policy.promptSha256,
                    config.sourcePreparationReasoningLevel,
                    model,
                )
            writeReport(
                report = report,
                sourceFile = sourceFile,
                source = source,
                flags = signals.flags,
                deterministicPatches = signals.policyPatches,
                modelPatches = modelOutput.patches,
                published = published,
                context = SourceContextPromptRenderer.render(modelOutput.sourceContext),
                usage = response.usage,
                reasoningLevel = config.sourcePreparationReasoningLevel,
                model = model,
            )

            assertTrue(published.isNotEmpty())
            assertTrue(combinedPatches.map { it.cueId }.distinct().size == combinedPatches.size)
            println("Live real source preparation report: ${report.absolutePath}")
        } finally {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun reportFile(
        source: File,
        policyVersion: Int,
        promptSha256: String,
        reasoningLevel: TranslationReasoningLevel,
        model: String,
    ): File {
        val appDirectory = requireNotNull(requireNotNull(findRepositoryFile("app/build.gradle.kts")).canonicalFile.parentFile)
        val directory = appDirectory.resolve("build/reports/live-provider")
        require(directory.isDirectory || directory.mkdirs()) { "Could not create live provider report directory" }
        val safeName = source.nameWithoutExtension.replace(UnsafeFileName, "_").take(70)
        val safeModel = model.replace(UnsafeFileName, "_").take(70)
        return directory.resolve(
            "${safeName}_${safeModel}_source_preparation_v${policyVersion}_${promptSha256.take(8)}_" +
                "${reasoningLevel.name.lowercase(Locale.ROOT)}.md",
        )
    }

    private fun writeReport(
        report: File,
        sourceFile: File,
        source: List<SubtitleCue>,
        flags: List<SourcePreparationFlag>,
        deterministicPatches: List<io.github.zvensmoluya.vortexcue.text.SourceTextPatch>,
        modelPatches: List<io.github.zvensmoluya.vortexcue.text.SourceTextPatch>,
        published: List<SubtitleCue>,
        context: String,
        usage: UsageRecord,
        reasoningLevel: TranslationReasoningLevel,
        model: String,
    ) {
        val sourceById = source.associateBy { requireNotNull(it.stableId) }
        val ordinalById = source.mapIndexed { index, cue -> requireNotNull(cue.stableId) to index + 1 }.toMap()
        val flagById = flags.groupBy(SourcePreparationFlag::cueId)
        val modelPatchIds = modelPatches.mapTo(mutableSetOf()) { it.cueId }
        val unresolvedSuspicious =
            flags.filter { it.kind == SourcePreparationFlagKind.SuspiciousPattern && it.cueId !in modelPatchIds }
        report.writeText(
            buildString {
                appendLine("# Live real ASR source preparation report")
                appendLine()
                appendLine("- Source: ${sourceFile.name}")
                appendLine("- Model: $model")
                appendLine("- Reasoning: ${reasoningLevel.name}")
                appendLine("- Input cues: ${source.size}")
                appendLine("- Duration: ${timestamp(source.maxOf(SubtitleCue::endMs))}")
                appendLine("- Deterministic flags: ${flags.size}")
                appendLine("- Deterministic patches: ${deterministicPatches.size}")
                appendLine("- Model patches: ${modelPatches.size}")
                appendLine("- Published cues: ${published.size}")
                appendLine("- Input tokens: ${usage.inputTokens ?: "unreported"}")
                appendLine("- Output tokens: ${usage.outputTokens ?: "unreported"}")
                appendLine("- Total tokens: ${usage.totalTokens ?: "unreported"}")
                appendLine("- Cost USD: ${usage.costUsd ?: "unreported"}")
                appendLine()
                appendLine("## Applied patches")
                appendLine()
                appendLine("| Cue | Time | Origin | Flags | Operation | Before | After |")
                appendLine("| ---: | --- | --- | --- | --- | --- | --- |")
                (deterministicPatches.map { "code" to it } + modelPatches.map { "model" to it }).forEach { (origin, patch) ->
                    val cue = requireNotNull(sourceById[patch.cueId])
                    val flagText = flagById[patch.cueId].orEmpty().joinToString(",") { it.patternId }
                    val after =
                        when (patch.operation) {
                            SourcePatchOperation.Drop -> "(dropped)"
                            SourcePatchOperation.Replace -> patch.replacementText.orEmpty()
                        }
                    appendLine(
                        "| ${ordinalById.getValue(patch.cueId)} | ${timestamp(cue.startMs)}–${timestamp(cue.endMs)} | " +
                            "$origin | ${markdownCell(flagText)} | ${patch.operation} | " +
                            "${markdownCell(cue.text)} | ${markdownCell(after)} |",
                    )
                }
                if (deterministicPatches.isEmpty() && modelPatches.isEmpty()) appendLine("| — | — | — | — | — | — | — |")
                appendLine()
                appendLine("## Suspicious flags left unchanged")
                appendLine()
                if (unresolvedSuspicious.isEmpty()) {
                    appendLine("(none)")
                } else {
                    unresolvedSuspicious.forEach { flag ->
                        val cue = requireNotNull(sourceById[flag.cueId])
                        appendLine("- Cue ${ordinalById.getValue(flag.cueId)} `${flag.patternId}`: ${cue.text}")
                    }
                }
                appendLine()
                appendLine("## SourceContext")
                appendLine()
                appendLine("```text")
                appendLine(context)
                appendLine("```")
            },
        )
    }

    private fun timestamp(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = milliseconds % 1_000L
        return "%02d:%02d.%03d".format(Locale.ROOT, minutes, seconds, millis)
    }

    private fun markdownCell(value: String): String =
        value.replace("|", "\\|").replace("\r\n", "<br>").replace('\r', '\n').replace("\n", "<br>")

    companion object {
        private val UnsafeFileName = Regex("[^A-Za-z0-9._-]")
    }
}
