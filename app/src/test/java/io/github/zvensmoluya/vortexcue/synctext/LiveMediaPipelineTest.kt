package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in host pipeline probe using a local media excerpt and real ASR/translation providers. */
class LiveMediaPipelineTest {
    @Test
    fun `live media pipeline`() = runBlocking {
        val config = LiveProviderProbeConfig.fromEnvironment()
        assumeTrue("Set VORTEX_LIVE_TEST=1 to run live provider tests", config.enabled)
        assumeTrue("Set VORTEX_LIVE_MEDIA_TEST=1 to run the live media pipeline", config.mediaEnabled)
        val sourceFile = config.mediaFile
        assumeTrue("Set VORTEX_LIVE_MEDIA_FILE to run the live media pipeline", sourceFile != null)
        requireNotNull(sourceFile)
        require(sourceFile.isFile) { "Live media file is unavailable: ${sourceFile.absolutePath}" }
        require(config.mediaStartSeconds >= 0.0) { "Media start must not be negative" }
        require(config.mediaDurationSeconds in 0.001..900.0) {
            "Media duration must be positive and no longer than 15 minutes"
        }
        val asrApiKey = requireNotNull(config.asrApiKey) {
            "VORTEX_LIVE_ASR_API_KEY is required for the live media pipeline"
        }

        val httpClientWrapper = SyncTextHttpClient("live-media-pipeline")
        val httpClient = httpClientWrapper.client
        val normalizedWavs = mutableListOf<File>()
        try {
            val executor = BoundedHttpExecutor(httpClient)
            val asr = OpenRouterAsrEngine(executor, EncodedAudioBudget())
            val durationMs = (config.mediaDurationSeconds * 1_000.0).toLong()
            val windows = FixedAsrWindowPlanner().plan(durationMs)
            val asrResults = windows.map { window ->
                val normalizedWav =
                    normalizeWindow(
                        source = sourceFile,
                        config = config,
                        startSeconds = config.mediaStartSeconds + window.uploadStartMs / 1_000.0,
                        durationSeconds = window.uploadDurationMs / 1_000.0,
                    ).also(normalizedWavs::add)
                asr.transcribe(
                    AsrRequest(
                        profile = config.asrProfile,
                        apiKey = asrApiKey,
                        wavFile = normalizedWav,
                        window = window,
                        sourceLanguage = config.mediaSourceLanguage,
                        endpoint = config.asrEndpoint,
                    ),
                )
            }
            val sourceCues = mergeAsrWindows(config.asrProfile, asrResults)
            require(sourceCues.isNotEmpty()) {
                "Live media ASR returned no usable timestamped speech across ${windows.size} windows"
            }

            val report = liveReportFile(sourceFile, config.asrProfile)
            writeReport(report, config, asrResults, sourceCues, emptyList(), emptyList(), windows.size)
            val outputs = translate(config, httpClientWrapper, sourceCues)
            val targetCues = outputs.flatMap(TranslationBatchOutput::targetCues)
            require(targetCues.size == sourceCues.size) { "Translated cue count does not match ASR output" }
            writeReport(report, config, asrResults, sourceCues, targetCues, outputs, windows.size)
            println("Live media report: ${report.absolutePath}")
        } finally {
            normalizedWavs.forEach(File::delete)
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
        Unit
    }

    private fun mergeAsrWindows(
        profile: AsrProfile,
        results: List<AsrWindowResult>,
    ): List<SubtitleCue> {
        val windowCues =
            results.flatMap { result ->
                when (profile) {
                    AsrProfile.OpenRouterWhisperV1 -> AsrTimelineMerger.mergeSegments(result.segments)
                    AsrProfile.OpenRouterGrokV1 -> AsrTimelineMerger.wordsToCues(result.segments)
                }
            }
        return AsrTimelineMerger.mergeSegments(
            windowCues.map { cue -> AsrTimestampedToken(cue.startMs, cue.endMs, cue.text) },
        )
    }

    private fun normalizeWindow(
        source: File,
        config: LiveProviderProbeConfig,
        startSeconds: Double,
        durationSeconds: Double,
    ): File {
        val output = File.createTempFile("vortexcue-live-media-", ".wav")
        val command =
            listOf(
                config.ffmpegCommand,
                "-hide_banner",
                "-loglevel",
                "error",
                "-nostdin",
                "-ss",
                decimal(startSeconds),
                "-t",
                decimal(durationSeconds),
                "-i",
                source.absolutePath,
                "-vn",
                "-ac",
                "1",
                "-ar",
                "16000",
                "-c:a",
                "pcm_s16le",
                "-y",
                output.absolutePath,
            )
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val processOutput = process.inputStream.bufferedReader().use { it.readText() }
            val status = process.waitFor()
            require(status == 0 && output.length() > 44L) {
                "ffmpeg could not prepare the live media excerpt (exit $status): ${processOutput.takeLast(1_000)}"
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private suspend fun translate(
        config: LiveProviderProbeConfig,
        httpClientWrapper: SyncTextHttpClient,
        sourceCues: List<SubtitleCue>,
    ): List<TranslationBatchOutput> {
        val apiKey = requireNotNull(config.translationApiKey) {
            "VORTEX_LIVE_TRANSLATION_API_KEY is required for the live media pipeline"
        }
        val endpoint = requireNotNull(config.translationEndpoint) {
            "VORTEX_LIVE_TRANSLATION_ENDPOINT is required for the live media pipeline"
        }
        val model = requireNotNull(config.translationModel) {
            "VORTEX_LIVE_TRANSLATION_MODEL is required for the live media pipeline"
        }
        val translation =
            TranslationNetworkGateway(
                registry =
                    TranslationAdapterRegistry(
                        defaultTranslationAdapters(httpClientWrapper, TranslationEndpointResolver()),
                    ),
                streamResponses = true,
            )
        val policy = TranslationPolicyRegistry.forTesting(::loadLivePromptAsset).resolve(config.targetLanguage)
        val plans = TranslationBatchPlanner().plan(sourceCues, policy.systemPrompt + policy.stylePrompt.orEmpty())
        return plans.map { plan ->
            TranslationBatchExecutor { systemPrompt, userPrompt ->
                translation.translate(
                    protocol = config.translationProtocol,
                    request =
                        TranslationProviderRequest(
                            endpoint = endpoint,
                            model = model,
                            apiKey = apiKey,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            userAgent = config.userAgent,
                        ),
                )
            }.execute(
                plan = plan,
                policy = policy,
                sourceLanguage = config.mediaSourceLanguage,
                targetLanguage = config.targetLanguage,
            )
        }
    }

    private fun liveReportFile(source: File, profile: AsrProfile): File {
        val appDirectory = requireNotNull(requireNotNull(findRepositoryFile("app/build.gradle.kts")).canonicalFile.parentFile)
        val directory = appDirectory.resolve("build/reports/live-provider")
        require(directory.isDirectory || directory.mkdirs()) { "Could not create live provider report directory" }
        val safeName = source.nameWithoutExtension.replace(UnsafeFileName, "_").take(70)
        return directory.resolve("${safeName}_${profile.name}.md")
    }

    private fun writeReport(
        report: File,
        config: LiveProviderProbeConfig,
        asrResults: List<AsrWindowResult>,
        sourceCues: List<SubtitleCue>,
        targetCues: List<SubtitleCue>,
        translationOutputs: List<TranslationBatchOutput>,
        windowCount: Int,
    ) {
        val targetById = targetCues.associateBy(SubtitleCue::stableId)
        val asrUsage = asrResults.map(AsrWindowResult::usage).sumRealUsage()
        val usage = translationOutputs.map(TranslationBatchOutput::usage).sumRealUsage()
        report.writeText(
            buildString {
                appendLine("# Live media pipeline report")
                appendLine()
                appendLine("- Excerpt: ${decimal(config.mediaStartSeconds)}s + ${decimal(config.mediaDurationSeconds)}s")
                appendLine("- ASR profile: ${config.asrProfile}")
                appendLine("- ASR windows: $windowCount")
                appendLine("- Detected language: ${asrResults.mapNotNull(AsrWindowResult::detectedLanguage).distinct().joinToString().ifEmpty { "unknown" }}")
                appendLine("- Source cues: ${sourceCues.size}")
                appendLine("- Translation batches: ${translationOutputs.size}")
                appendLine("- Format recoveries: ${translationOutputs.sumOf(TranslationBatchOutput::formatRecoveryCount)}")
                appendLine("- Repair calls: ${translationOutputs.sumOf(TranslationBatchOutput::repairCount)}")
                appendLine("- ASR cost USD: ${asrUsage.costUsd ?: "unreported"}")
                appendLine("- Translation cost USD: ${usage.costUsd ?: "unreported"}")
                appendLine()
                appendLine("| Time | ASR (${config.mediaSourceLanguage}) | Translation (${config.targetLanguage}) |")
                appendLine("| --- | --- | --- |")
                sourceCues.forEach { source ->
                    val target = targetById[source.stableId]
                    appendLine(
                        "| ${timestamp(source.startMs)} - ${timestamp(source.endMs)} | " +
                            "${markdownCell(source.text)} | ${markdownCell(target?.text.orEmpty())} |",
                    )
                }
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

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    companion object {
        private val UnsafeFileName = Regex("[^A-Za-z0-9._-]")
    }
}
