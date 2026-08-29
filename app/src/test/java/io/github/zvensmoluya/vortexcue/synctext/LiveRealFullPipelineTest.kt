package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleParser
import io.github.zvensmoluya.vortexcue.text.CanonicalTextNormalizer
import io.github.zvensmoluya.vortexcue.text.SourcePatchOperation
import io.github.zvensmoluya.vortexcue.text.SourceTextPatch
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in paid probe of the complete preparation and translation path for one exported ASR SRT. */
class LiveRealFullPipelineTest {
    @Test
    fun `live model prepares and translates an exported ASR timeline`() = runBlocking {
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
        val rawSource =
            CanonicalTextNormalizer.normalizeGenerated(
                SubtitleParser.parse(sourceFile.name, sourceFile.readBytes()),
            ).toSubtitleCues()
        val preparationPolicy =
            SourcePreparationPolicyRegistry.forTesting(::loadLiveSourcePreparationPrompt).resolve()
        val translationPolicy =
            TranslationPolicyRegistry.forTesting(::loadLivePromptAsset).resolve(config.targetLanguage)
        val signals = SourcePreparationSignalDetector.detect(rawSource)
        val httpClientWrapper = SyncTextHttpClient("live-real-full-pipeline")
        val httpClient = httpClientWrapper.client
        try {
            val gateway =
                TranslationNetworkGateway(
                    TranslationAdapterRegistry(
                        defaultTranslationAdapters(httpClientWrapper, TranslationEndpointResolver()),
                    ),
                    streamResponses = true,
                )
            val preparationResponse =
                gateway.translate(
                    protocol = config.translationProtocol,
                    request =
                        providerRequest(
                            config = config,
                            endpoint = endpoint,
                            model = model,
                            apiKey = apiKey,
                            systemPrompt = preparationPolicy.systemPrompt,
                            userPrompt =
                                SourcePreparationPromptRenderer.render(
                                    rawSource,
                                    sourceLanguage = config.mediaSourceLanguage,
                                    flags = signals.flags,
                                ),
                        ),
                )
            val preparation =
                SourcePreparationResponseParser.parse(
                    response = preparationResponse.text,
                    sourceCues = rawSource,
                    policyDropIds = signals.policyPatches.mapTo(mutableSetOf(), SourceTextPatch::cueId),
                )
            val patches = signals.policyPatches + preparation.patches
            val publishedSource = SourcePatchApplier.apply(rawSource, patches)
            val promptOverhead =
                listOfNotNull(translationPolicy.systemPrompt, translationPolicy.stylePrompt).joinToString("\n\n")
            val plans = TranslationBatchPlanner().plan(publishedSource, promptOverhead)
            val executor =
                TranslationBatchExecutor { systemPrompt, userPrompt ->
                    gateway.translate(
                        protocol = config.translationProtocol,
                        request =
                            providerRequest(
                                config = config,
                                endpoint = endpoint,
                                model = model,
                                apiKey = apiKey,
                                systemPrompt = systemPrompt,
                                userPrompt = userPrompt,
                            ),
                    )
                }
            val batchOutputs =
                plans.map { plan ->
                    executor.execute(
                        plan = plan,
                        policy = translationPolicy,
                        sourceLanguage = config.mediaSourceLanguage,
                        targetLanguage = config.targetLanguage,
                        sourceContext = preparation.sourceContext,
                    )
                }
            val translated = batchOutputs.flatMap(TranslationBatchOutput::targetCues)
            val aligned =
                CanonicalTextNormalizer.alignTranslation(
                    CanonicalTextNormalizer.normalizeGenerated(publishedSource),
                    translated,
                ).toSubtitleCues()
            val outputBase = outputBase(sourceFile, model, preparationPolicy, config.sourcePreparationReasoningLevel)
            val report = File("${outputBase.absolutePath}.md")
            val bilingual = File("${outputBase.absolutePath}.bilingual.srt")
            writeReport(
                report = report,
                sourceFile = sourceFile,
                model = model,
                reasoningLevel = config.sourcePreparationReasoningLevel,
                rawSource = rawSource,
                publishedSource = publishedSource,
                translated = aligned,
                patches = patches,
                context = SourceContextPromptRenderer.render(preparation.sourceContext),
                preparationUsage = preparationResponse.usage,
                translationUsage = batchOutputs.map(TranslationBatchOutput::usage).sumRealUsage(),
                batchCount = plans.size,
                formatRecoveryCount = batchOutputs.sumOf(TranslationBatchOutput::formatRecoveryCount),
                repairCount = batchOutputs.sumOf(TranslationBatchOutput::repairCount),
            )
            writeBilingualSrt(bilingual, publishedSource, aligned)

            assertTrue(publishedSource.isNotEmpty())
            assertEquals(publishedSource.map(SubtitleCue::stableId), aligned.map(SubtitleCue::stableId))
            assertEquals(publishedSource.size, aligned.size)
            println("Live real full pipeline report: ${report.absolutePath}")
            println("Live real bilingual SRT: ${bilingual.absolutePath}")
        } finally {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun providerRequest(
        config: LiveProviderProbeConfig,
        endpoint: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
    ) =
        TranslationProviderRequest(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            reasoningLevel = config.sourcePreparationReasoningLevel,
            userAgent = config.userAgent,
        )

    private fun outputBase(
        source: File,
        model: String,
        policy: SourcePreparationPolicy,
        reasoningLevel: TranslationReasoningLevel,
    ): File {
        val appDirectory = requireNotNull(requireNotNull(findRepositoryFile("app/build.gradle.kts")).canonicalFile.parentFile)
        val directory = appDirectory.resolve("build/reports/live-provider")
        require(directory.isDirectory || directory.mkdirs()) { "Could not create live provider report directory" }
        val safeSource = source.nameWithoutExtension.replace(UnsafeFileName, "_").take(50)
        val safeModel = model.replace(UnsafeFileName, "_").take(70)
        val name =
            "${safeSource}_${safeModel}_full_pipeline_v${policy.version}_${policy.promptSha256.take(8)}_" +
                reasoningLevel.name.lowercase(Locale.ROOT)
        return directory.resolve(name)
    }

    private fun writeReport(
        report: File,
        sourceFile: File,
        model: String,
        reasoningLevel: TranslationReasoningLevel,
        rawSource: List<SubtitleCue>,
        publishedSource: List<SubtitleCue>,
        translated: List<SubtitleCue>,
        patches: List<SourceTextPatch>,
        context: String,
        preparationUsage: UsageRecord,
        translationUsage: UsageRecord,
        batchCount: Int,
        formatRecoveryCount: Int,
        repairCount: Int,
    ) {
        val rawById = rawSource.associateBy { requireNotNull(it.stableId) }
        val publishedById = publishedSource.associateBy { requireNotNull(it.stableId) }
        val translatedById = translated.associateBy { requireNotNull(it.stableId) }
        val totalUsage = listOf(preparationUsage, translationUsage).sumRealUsage()
        report.writeText(
            buildString {
                appendLine("# Live real ASR preparation and translation report")
                appendLine()
                appendLine("- Source: ${sourceFile.name}")
                appendLine("- Model: $model")
                appendLine("- Reasoning: ${reasoningLevel.name}")
                appendLine("- Target language: zh-CN")
                appendLine("- Raw cues: ${rawSource.size}")
                appendLine("- Published cues: ${publishedSource.size}")
                appendLine("- Source patches: ${patches.size}")
                appendLine("- Translation batches: $batchCount")
                appendLine("- Format recoveries: $formatRecoveryCount")
                appendLine("- Repairs: $repairCount")
                appendLine("- Preparation usage: ${usageText(preparationUsage)}")
                appendLine("- Translation usage: ${usageText(translationUsage)}")
                appendLine("- Total usage: ${usageText(totalUsage)}")
                appendLine()
                appendLine("## Source patches")
                appendLine()
                appendLine("| Cue | Operation | Before | After |")
                appendLine("| --- | --- | --- | --- |")
                patches.forEach { patch ->
                    val before = requireNotNull(rawById[patch.cueId]).text
                    val after =
                        when (patch.operation) {
                            SourcePatchOperation.Drop -> "(dropped)"
                            SourcePatchOperation.Replace -> patch.replacementText.orEmpty()
                        }
                    appendLine("| ${patch.cueId} | ${patch.operation} | ${cell(before)} | ${cell(after)} |")
                }
                appendLine()
                appendLine("## SourceContext")
                appendLine()
                appendLine("```text")
                appendLine(context)
                appendLine("```")
                appendLine()
                appendLine("## Published source and translation")
                appendLine()
                appendLine("| Time | Source | zh-CN |")
                appendLine("| --- | --- | --- |")
                publishedSource.forEach { source ->
                    val id = requireNotNull(source.stableId)
                    val target = requireNotNull(translatedById[id])
                    requireNotNull(publishedById[id])
                    appendLine("| ${srtTimestamp(source.startMs)} | ${cell(source.text)} | ${cell(target.text)} |")
                }
            },
        )
    }

    private fun writeBilingualSrt(
        file: File,
        source: List<SubtitleCue>,
        target: List<SubtitleCue>,
    ) {
        val targetById = target.associateBy { requireNotNull(it.stableId) }
        file.writeText(
            buildString {
                source.forEachIndexed { index, cue ->
                    val translated = requireNotNull(targetById[cue.stableId])
                    appendLine(index + 1)
                    appendLine("${srtTimestamp(cue.startMs)} --> ${srtTimestamp(cue.endMs)}")
                    appendLine(cue.text)
                    appendLine(translated.text)
                    appendLine()
                }
            },
        )
    }

    private fun usageText(usage: UsageRecord): String =
        "input=${usage.inputTokens ?: "?"}, output=${usage.outputTokens ?: "?"}, " +
            "total=${usage.totalTokens ?: "?"}, cost_usd=${usage.costUsd ?: "?"}"

    private fun srtTimestamp(milliseconds: Long): String {
        val hours = milliseconds / 3_600_000L
        val minutes = milliseconds / 60_000L % 60L
        val seconds = milliseconds / 1_000L % 60L
        val millis = milliseconds % 1_000L
        return "%02d:%02d:%02d,%03d".format(Locale.ROOT, hours, minutes, seconds, millis)
    }

    private fun cell(value: String): String =
        value.replace("|", "\\|").replace("\r\n", "<br>").replace('\r', '\n').replace("\n", "<br>")

    companion object {
        private val UnsafeFileName = Regex("[^A-Za-z0-9._-]")
    }
}
