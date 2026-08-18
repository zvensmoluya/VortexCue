package io.github.zvensmoluya.vortexcue.synctext

class SyncTextConnectionTester(
    private val translation: TranslationNetworkGateway,
    private val asr: OpenRouterAsrEngine,
) {
    suspend fun testTranslation(
        protocol: TranslationProtocol,
        endpoint: String,
        model: String,
        apiKey: String,
    ) {
        translation.test(
            protocol = protocol,
            request =
                TranslationProviderRequest(
                    endpoint = endpoint,
                    model = model,
                    apiKey = apiKey,
                    systemPrompt = "Reply with exactly OK.",
                    userPrompt = "Reply with exactly OK.",
                ),
        )
    }

    suspend fun testAsr(
        profile: AsrProfile,
        endpoint: String,
        apiKey: String,
    ) {
        asr.testConnection(profile = profile, endpoint = endpoint, apiKey = apiKey)
    }
}
