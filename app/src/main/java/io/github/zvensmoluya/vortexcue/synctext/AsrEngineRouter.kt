package io.github.zvensmoluya.vortexcue.synctext

/** Dispatches to a provider-specific transport so model IDs cannot select an incompatible API shape. */
class AsrEngineRouter(
    private val engines: Map<AsrProvider, AsrEngine>,
) : AsrEngine {
    override suspend fun transcribe(request: AsrRequest): AsrWindowResult =
        requireNotNull(engines[request.profile.provider]) {
            "ASR provider ${request.profile.provider} is not configured"
        }.transcribe(request)
}
