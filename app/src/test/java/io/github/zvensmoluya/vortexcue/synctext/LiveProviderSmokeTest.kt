package io.github.zvensmoluya.vortexcue.synctext

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in host-side smoke test for real provider contracts. It intentionally does not run as part
 * of the normal unit-test path unless VORTEX_LIVE_TEST=1 is present.
 */
class LiveProviderSmokeTest {
    @Test
    fun `live provider probe`() = runBlocking {
        val config = LiveProviderProbeConfig.fromEnvironment()
        assumeTrue(
            "Set VORTEX_LIVE_TEST=1 to run the paid provider smoke test",
            config.enabled,
        )
        require(config.asrApiKey != null || config.translationApiKey != null) {
            "Configure at least one live provider API key"
        }

        val httpClientWrapper = SyncTextHttpClient("live-probe")
        val httpClient = httpClientWrapper.client
        try {
            val executor = BoundedHttpExecutor(httpClient)
            val translation =
                TranslationNetworkGateway(
                    registry =
                        TranslationAdapterRegistry(
                            defaultTranslationAdapters(httpClientWrapper, TranslationEndpointResolver()),
                        ),
                    streamResponses = true,
                )
            val asr = OpenRouterAsrEngine(executor, EncodedAudioBudget())
            val tester =
                SyncTextConnectionTester(
                    translation = translation,
                    asr = asr,
                    policies = TranslationPolicyRegistry.forTesting(::loadPromptAsset),
                    createAsrProbeFile = ::createAsrProbeFile,
                )

            config.asrApiKey?.let { apiKey ->
                tester.testAsr(
                    profile = config.asrProfile,
                    endpoint = config.asrEndpoint,
                    apiKey = apiKey,
                )
            }
            config.translationApiKey?.let { apiKey ->
                val endpoint = requireNotNull(config.translationEndpoint) {
                    "VORTEX_LIVE_TRANSLATION_ENDPOINT is required for translation"
                }
                val model = requireNotNull(config.translationModel) {
                    "VORTEX_LIVE_TRANSLATION_MODEL is required for translation"
                }
                tester.testTranslation(
                    protocol = config.translationProtocol,
                    endpoint = endpoint,
                    model = model,
                    apiKey = apiKey,
                    targetLanguage = config.targetLanguage,
                    userAgent = config.userAgent,
                )
            }
        } finally {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
        Unit
    }

    private fun createAsrProbeFile(): File {
        val source = requireNotNull(findRepositoryFile("app/src/main/res/raw/asr_probe_en_us.wav")) {
            "ASR probe fixture is missing"
        }
        return File.createTempFile("vortexcue-live-asr-", ".wav").also { output ->
            try {
                source.copyTo(output, overwrite = true)
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
        }
    }

    private fun loadPromptAsset(id: String): ByteArray {
        val fileName =
            when (id) {
                "system.v1" -> "translation_system_v1.md"
                "style.zh-CN.v1" -> "translation_style_zh_cn_v1.md"
                else -> error("Unexpected prompt asset: $id")
            }
        return requireNotNull(findRepositoryFile("app/src/main/res/raw/$fileName")) {
            "Prompt asset is missing: $fileName"
        }.readBytes()
    }

    private fun findRepositoryFile(relativePath: String): File? =
        sequenceOf(
            File(relativePath),
            File("../$relativePath"),
        ).firstOrNull(File::isFile)
}

private data class LiveProviderProbeConfig(
    val enabled: Boolean,
    val asrApiKey: String?,
    val asrEndpoint: String,
    val asrProfile: AsrProfile,
    val translationApiKey: String?,
    val translationEndpoint: String?,
    val translationModel: String?,
    val translationProtocol: TranslationProtocol,
    val targetLanguage: String,
    val userAgent: String?,
) {
    companion object {
        fun fromEnvironment(): LiveProviderProbeConfig {
            val localValues = loadLocalDotEnv()
            fun value(name: String): String? =
                (System.getenv(name) ?: localValues[name])?.trim()?.takeIf(String::isNotEmpty)

            return LiveProviderProbeConfig(
                enabled = value("VORTEX_LIVE_TEST") == "1",
                asrApiKey = value("VORTEX_LIVE_ASR_API_KEY"),
                asrEndpoint =
                    value("VORTEX_LIVE_ASR_ENDPOINT")
                        ?: OpenRouterAsrEngine.OpenRouterTranscriptionUrl,
                asrProfile =
                    value("VORTEX_LIVE_ASR_PROFILE")?.let { enumValueOrNull<AsrProfile>(it) }
                        ?: AsrProfile.OpenRouterWhisperV1,
                translationApiKey = value("VORTEX_LIVE_TRANSLATION_API_KEY"),
                translationEndpoint = value("VORTEX_LIVE_TRANSLATION_ENDPOINT"),
                translationModel = value("VORTEX_LIVE_TRANSLATION_MODEL"),
                translationProtocol =
                    value("VORTEX_LIVE_TRANSLATION_PROTOCOL")?.let { enumValueOrNull<TranslationProtocol>(it) }
                        ?: TranslationProtocol.Responses,
                targetLanguage = value("VORTEX_LIVE_TARGET_LANGUAGE") ?: "zh-CN",
                userAgent = value("VORTEX_LIVE_USER_AGENT"),
            )
        }

        private fun loadLocalDotEnv(): Map<String, String> {
            val file =
                sequenceOf(File(".env.local"), File(".env"), File("../.env.local"), File("../.env"))
                    .firstOrNull(File::isFile)
                    ?: return emptyMap()
            return file.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = trimmed.substring(0, separator).trim()
                if (!key.matches(EnvKey)) return@mapNotNull null
                val value = trimmed.substring(separator + 1).trim().removeOptionalQuotes()
                key to value
            }.toMap()
        }

        private val EnvKey = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

private fun String.removeOptionalQuotes(): String =
    if (length >= 2 && first() == last() && (first() == '"' || first() == '\'')) substring(1, lastIndex) else this

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
