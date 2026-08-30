package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.AsrProfile
import io.github.zvensmoluya.vortexcue.synctext.AudioTrackInfo
import io.github.zvensmoluya.vortexcue.synctext.AudioWindowExtractor
import io.github.zvensmoluya.vortexcue.synctext.CredentialMetadata
import io.github.zvensmoluya.vortexcue.synctext.CredentialPurpose
import io.github.zvensmoluya.vortexcue.synctext.DiscoveredTranslationModel
import io.github.zvensmoluya.vortexcue.synctext.GenerationInput
import io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory
import io.github.zvensmoluya.vortexcue.synctext.GenerationOutput
import io.github.zvensmoluya.vortexcue.synctext.GenerationRequest
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.LanguageTags
import io.github.zvensmoluya.vortexcue.synctext.AsrException
import io.github.zvensmoluya.vortexcue.synctext.CredentialUnavailableException
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocolException
import io.github.zvensmoluya.vortexcue.synctext.SyncTextConfig
import io.github.zvensmoluya.vortexcue.synctext.NoOpSyncTextDiagnosticRecorder
import io.github.zvensmoluya.vortexcue.synctext.SyncTextDiagnosticEventType
import io.github.zvensmoluya.vortexcue.synctext.SyncTextDiagnosticRecorder
import io.github.zvensmoluya.vortexcue.synctext.SyncTextRepository
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocol
import io.github.zvensmoluya.vortexcue.synctext.TranslationReasoningLevel
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val ConnectionTestMinimumVisibleMillis = 500L

data class SyncTextConfigDraft(
    val asrProfile: AsrProfile,
    val asrEndpoint: String,
    val asrCredentialId: String?,
    val asrSecret: String?,
    val translationProtocol: TranslationProtocol,
    val translationEndpoint: String,
    val translationUserAgent: String?,
    val translationModel: String,
    val translationReasoningLevel: TranslationReasoningLevel,
    val translationCredentialId: String?,
    val translationSecret: String?,
    val targetLanguage: String,
    val presentationMode: TextPresentationMode,
)

data class ConnectionTestUiState(
    val running: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null,
    val testedFingerprint: String? = null,
    val failureCategory: GenerationErrorCategory? = null,
    val failureStatus: Int? = null,
)

data class CredentialSaveUiState(
    val requestId: Long,
    val purpose: CredentialPurpose,
    val credentialId: String?,
    val success: Boolean,
    val message: String? = null,
)

enum class SyncTextLaunchKind {
    Generation,
    Translation,
}

data class SyncTextLaunchUiState(
    val contentId: String,
    val kind: SyncTextLaunchKind,
)

data class SyncTextUiState(
    val config: SyncTextConfig? = null,
    val credentials: List<CredentialMetadata> = emptyList(),
    val tasks: List<GenerationTaskSummary> = emptyList(),
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val availableTranslationModels: List<DiscoveredTranslationModel> = emptyList(),
    val loadingTranslationModels: Boolean = false,
    val translationModelsError: String? = null,
    val translationConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val asrConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val configSaving: Boolean = false,
    val configError: String? = null,
    val credentialSave: CredentialSaveUiState? = null,
    val taskLaunch: SyncTextLaunchUiState? = null,
    val generationOutput: GenerationOutput = GenerationOutput.SourceOnly,
    val generationAudioOnly: Boolean = false,
    val generationActivationTrackId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

sealed interface SyncTextAction {
    data class OpenGeneration(val content: ContentDetails) : SyncTextAction

    data object CloseGeneration : SyncTextAction

    data class SubmitSelectedInput(
        val input: GenerationInput,
        val sourceLanguage: String?,
    ) : SyncTextAction

    data object ClearMessage : SyncTextAction

    data class DiscoverTranslationModels(
        val protocol: TranslationProtocol,
        val endpoint: String,
        val credentialId: String?,
        val apiKey: String?,
    ) : SyncTextAction

    data class TestTranslationConnection(
        val protocol: TranslationProtocol,
        val endpoint: String,
        val model: String,
        val reasoningLevel: TranslationReasoningLevel,
        val credentialId: String?,
        val apiKey: String?,
        val configurationFingerprint: String,
    ) : SyncTextAction

    data class TestAsrConnection(
        val profile: AsrProfile,
        val endpoint: String,
        val credentialId: String?,
        val apiKey: String?,
        val configurationFingerprint: String,
    ) : SyncTextAction

    data class SaveCredential(
        val credentialId: String?,
        val name: String,
        val purpose: CredentialPurpose,
        val secret: String,
    ) : SyncTextAction

    data class DeleteCredential(val credentialId: String) : SyncTextAction

    data class SaveConfig(
        val configId: String?,
        val draft: SyncTextConfigDraft,
    ) : SyncTextAction

    data class SetPresentationMode(
        val slotId: String,
        val mode: TextPresentationMode,
    ) : SyncTextAction

    data class CancelForContent(val contentId: String) : SyncTextAction

    data class AbortForContent(val contentId: String) : SyncTextAction

    data class RetryForContent(val contentId: String) : SyncTextAction

    data class AllowMeteredNetworkForContent(val contentId: String) : SyncTextAction

    data class TranslateForContent(
        val contentId: String,
        val sourceSlotId: String,
        val activationTrackId: String?,
    ) : SyncTextAction

    data class RegenerateFromAudio(
        val content: ContentDetails,
        val translateAfterRecognition: Boolean,
    ) : SyncTextAction
}

private data class OverlayState(
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val availableTranslationModels: List<DiscoveredTranslationModel> = emptyList(),
    val loadingTranslationModels: Boolean = false,
    val translationModelsError: String? = null,
    val translationConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val asrConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val configSaving: Boolean = false,
    val configError: String? = null,
    val credentialSave: CredentialSaveUiState? = null,
    val taskLaunch: SyncTextLaunchUiState? = null,
    val generationOutput: GenerationOutput = GenerationOutput.SourceOnly,
    val generationAudioOnly: Boolean = false,
    val generationActivationTrackId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

private data class ModelDiscoveryKey(
    val protocol: TranslationProtocol,
    val endpoint: String,
    val credentialFingerprint: String,
)

class SyncTextUiController(
    private val repository: SyncTextRepository,
    private val audio: AudioWindowExtractor,
    private val materializeCandidate: suspend (String) -> String,
    private val scope: CoroutineScope,
    private val diagnostics: SyncTextDiagnosticRecorder = NoOpSyncTextDiagnosticRecorder,
) {
    private val overlay = MutableStateFlow(OverlayState())
    private val configSaveMutex = Mutex()
    private val configSaveCounterLock = Any()
    private var configSaveCounter = 0
    private var credentialSaveRequestId = 0L
    private var modelDiscoveryJob: Job? = null
    private var modelDiscoveryToken = 0L
    private var modelDiscoveryCacheKey: ModelDiscoveryKey? = null
    private var modelDiscoveryInFlightKey: ModelDiscoveryKey? = null

    val state: StateFlow<SyncTextUiState> =
        combine(
            repository.config,
            repository.credentialMetadata,
            repository.tasks,
            overlay,
        ) { config, credentials, tasks, local ->
            SyncTextUiState(
                config = config,
                credentials = credentials,
                tasks = tasks,
                generationContent = local.generationContent,
                audioTracks = local.audioTracks,
                inspectingAudioTracks = local.inspectingAudioTracks,
                audioInspectionError = local.audioInspectionError,
                availableTranslationModels = local.availableTranslationModels,
                loadingTranslationModels = local.loadingTranslationModels,
                translationModelsError = local.translationModelsError,
                translationConnectionTest = local.translationConnectionTest,
                asrConnectionTest = local.asrConnectionTest,
                configSaving = local.configSaving,
                configError = local.configError,
                credentialSave = local.credentialSave,
                taskLaunch = local.taskLaunch,
                generationOutput = local.generationOutput,
                generationAudioOnly = local.generationAudioOnly,
                generationActivationTrackId = local.generationActivationTrackId,
                busy = local.busy,
                message = local.message,
            )
        }.stateIn(scope, SharingStarted.Eagerly, SyncTextUiState())

    fun dispatch(action: SyncTextAction) {
        when (action) {
            is SyncTextAction.OpenGeneration -> openGeneration(action.content)
            SyncTextAction.CloseGeneration -> closeGeneration()
            is SyncTextAction.SubmitSelectedInput -> {
                val content = overlay.value.generationContent ?: return
                val config = repository.config.value ?: return showConfigurationRequired()
                submitGeneration(
                    content = content,
                    input = action.input,
                    config = config,
                    allowAsrFallback = false,
                    output = overlay.value.generationOutput,
                    activationTrackId = overlay.value.generationActivationTrackId,
                    sourceLanguage = action.sourceLanguage,
                )
            }
            SyncTextAction.ClearMessage -> overlay.update { it.copy(message = null) }
            is SyncTextAction.DiscoverTranslationModels ->
                discoverTranslationModels(
                    protocol = action.protocol,
                    endpoint = action.endpoint,
                    credentialId = action.credentialId,
                    apiKey = action.apiKey,
                )
            is SyncTextAction.TestTranslationConnection ->
                testTranslationConnection(
                    protocol = action.protocol,
                    endpoint = action.endpoint,
                    model = action.model,
                    reasoningLevel = action.reasoningLevel,
                    credentialId = action.credentialId,
                    apiKey = action.apiKey,
                    configurationFingerprint = action.configurationFingerprint,
                )
            is SyncTextAction.TestAsrConnection ->
                testAsrConnection(
                    profile = action.profile,
                    endpoint = action.endpoint,
                    credentialId = action.credentialId,
                    apiKey = action.apiKey,
                    configurationFingerprint = action.configurationFingerprint,
                )
            is SyncTextAction.SaveCredential -> saveCredential(action)
            is SyncTextAction.DeleteCredential -> runAction {
                repository.deleteCredential(action.credentialId)
                "密钥已删除"
            }
            is SyncTextAction.SaveConfig -> saveConfig(action)
            is SyncTextAction.SetPresentationMode -> runAction {
                repository.setPresentationMode(action.slotId, action.mode)
                null
            }
            is SyncTextAction.CancelForContent -> runAction {
                repository.cancelForContent(action.contentId)
                "同步文字已暂停"
            }
            is SyncTextAction.AbortForContent -> runAction {
                repository.abortForContent(action.contentId)
                "同步文字生成已停止"
            }
            is SyncTextAction.RetryForContent -> runAction {
                repository.retryForContent(action.contentId)
                "同步文字已重试"
            }
            is SyncTextAction.AllowMeteredNetworkForContent -> runAction {
                repository.allowMeteredNetworkForContent(action.contentId)
                "已允许本次任务使用计费网络"
            }
            is SyncTextAction.TranslateForContent -> runAction(
                taskLaunch = SyncTextLaunchUiState(action.contentId, SyncTextLaunchKind.Translation),
            ) {
                val target = repository.config.value?.targetLanguage?.let(LanguageTags::normalizeOrNull)
                if (target == null) {
                    "源语言与目标语言相同，无需翻译"
                } else {
                    repository.submitTranslationFromSlot(
                        slotId = action.sourceSlotId,
                        targetLanguage = target,
                        activationTrackId = action.activationTrackId,
                    )
                    null
                }
            }
            is SyncTextAction.RegenerateFromAudio -> {
                val config = repository.config.value ?: return showConfigurationRequired()
                val output =
                    if (action.translateAfterRecognition) {
                        GenerationOutput.Translated(config.targetLanguage, config.presentationMode)
                    } else {
                        GenerationOutput.SourceOnly
                    }
                inspectAudio(
                    content = action.content,
                    output = output,
                    activationTrackId = action.content.activeText.primaryTrackId,
                    audioOnly = true,
                )
            }
        }
    }

    private fun saveCredential(action: SyncTextAction.SaveCredential) {
        diagnostics.recordAsync(
            SyncTextDiagnosticEventType.UiAction,
            stage = "Credential",
            outcome = "SaveStarted",
            reason = action.purpose.name,
        )
        val requestId = ++credentialSaveRequestId
        overlay.update {
            it.copy(
                busy = true,
                credentialSave = null,
                configError = null,
                message = null,
            )
        }
        scope.launch {
            try {
                val saved =
                    repository.saveCredential(
                        credentialId = action.credentialId,
                        name = action.name,
                        purpose = action.purpose,
                        secret = action.secret,
                    )
                overlay.update {
                    it.copy(
                        busy = false,
                        credentialSave =
                            CredentialSaveUiState(
                                requestId = requestId,
                                purpose = action.purpose,
                                credentialId = saved.credentialId,
                                success = true,
                            ),
                    )
                }
                if (action.purpose == CredentialPurpose.Translation || action.purpose == CredentialPurpose.Shared) {
                    modelDiscoveryCacheKey = null
                }
                diagnostics.record(
                    SyncTextDiagnosticEventType.UiAction,
                    stage = "Credential",
                    outcome = "SaveCompleted",
                    reason = action.purpose.name,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                diagnostics.record(
                    SyncTextDiagnosticEventType.UiAction,
                    stage = "Credential",
                    outcome = "SaveFailed",
                    reason = error::class.simpleName,
                )
                overlay.update {
                    it.copy(
                        busy = false,
                        credentialSave =
                            CredentialSaveUiState(
                                requestId = requestId,
                                purpose = action.purpose,
                                credentialId = action.credentialId,
                                success = false,
                                message = error.toCredentialSaveMessage(),
                            ),
                    )
                }
            }
        }
    }

    private fun discoverTranslationModels(
        protocol: TranslationProtocol,
        endpoint: String,
        credentialId: String?,
        apiKey: String?,
    ) {
        if (endpoint.isBlank() || credentialId.isNullOrBlank() && apiKey.isNullOrBlank()) {
            modelDiscoveryJob?.cancel()
            modelDiscoveryCacheKey = null
            modelDiscoveryInFlightKey = null
            overlay.update {
                it.copy(
                    availableTranslationModels = emptyList(),
                    loadingTranslationModels = false,
                    translationModelsError = null,
                )
            }
            return
        }
        val discoveryKey =
            ModelDiscoveryKey(
                protocol = protocol,
                endpoint = endpoint.trim().removeSuffix("/"),
                credentialFingerprint = modelCredentialFingerprint(credentialId, apiKey),
            )
        if (discoveryKey == modelDiscoveryCacheKey) {
            overlay.update { it.copy(loadingTranslationModels = false, translationModelsError = null) }
            return
        }
        if (discoveryKey == modelDiscoveryInFlightKey) return

        modelDiscoveryJob?.cancel()
        modelDiscoveryInFlightKey = discoveryKey
        val token = ++modelDiscoveryToken
        overlay.update {
            it.copy(
                availableTranslationModels = emptyList(),
                loadingTranslationModels = true,
                translationModelsError = null,
            )
        }
        modelDiscoveryJob =
            scope.launch {
                delay(450L)
                runCatching {
                    repository.discoverTranslationModels(protocol, endpoint, credentialId, apiKey)
                }.onSuccess { models ->
                    if (token != modelDiscoveryToken) return@onSuccess
                    modelDiscoveryCacheKey = discoveryKey
                    modelDiscoveryInFlightKey = null
                    overlay.update {
                        it.copy(
                            availableTranslationModels = models,
                            loadingTranslationModels = false,
                            translationModelsError = null,
                        )
                    }
                }.onFailure { error ->
                    if (token != modelDiscoveryToken || error is CancellationException) return@onFailure
                    modelDiscoveryInFlightKey = null
                    overlay.update {
                        it.copy(
                            loadingTranslationModels = false,
                            translationModelsError = "无法获取模型目录，可以直接输入模型 ID",
                        )
                    }
                }
            }
    }

    private fun testTranslationConnection(
        protocol: TranslationProtocol,
        endpoint: String,
        model: String,
        reasoningLevel: TranslationReasoningLevel,
        credentialId: String?,
        apiKey: String?,
        configurationFingerprint: String,
    ) {
        overlay.update {
            it.copy(
                translationConnectionTest = ConnectionTestUiState(running = true),
                configError = null,
            )
        }
        scope.launch {
            runCatching {
                withMinimumConnectionTestDuration {
                    repository.testTranslationConnection(protocol, endpoint, model, reasoningLevel, credentialId, apiKey)
                }
            }.onSuccess {
                overlay.update {
                    it.copy(
                        translationConnectionTest =
                            ConnectionTestUiState(
                                success = true,
                                message = "连接成功",
                                testedFingerprint = configurationFingerprint,
                            ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                overlay.update {
                    it.copy(
                        translationConnectionTest =
                            error.toConnectionTestUiState(configurationFingerprint),
                    )
                }
            }
        }
    }

    private fun testAsrConnection(
        profile: AsrProfile,
        endpoint: String,
        credentialId: String?,
        apiKey: String?,
        configurationFingerprint: String,
    ) {
        overlay.update {
            it.copy(
                asrConnectionTest = ConnectionTestUiState(running = true),
                configError = null,
            )
        }
        scope.launch {
            runCatching {
                withMinimumConnectionTestDuration {
                    repository.testAsrConnection(profile, endpoint, credentialId, apiKey)
                }
            }.onSuccess {
                overlay.update {
                    it.copy(
                        asrConnectionTest =
                            ConnectionTestUiState(
                                success = true,
                                message = "连接成功",
                                testedFingerprint = configurationFingerprint,
                            ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                overlay.update {
                    it.copy(
                        asrConnectionTest =
                            error.toConnectionTestUiState(configurationFingerprint),
                    )
                }
            }
        }
    }

    private suspend fun <T> withMinimumConnectionTestDuration(block: suspend () -> T): T {
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            block()
        } finally {
            val remainingMillis =
                ConnectionTestMinimumVisibleMillis - startedAt.elapsedNow().inWholeMilliseconds
            if (remainingMillis > 0L) delay(remainingMillis)
        }
    }

    private fun openGeneration(content: ContentDetails) {
        diagnostics.recordAsync(
            SyncTextDiagnosticEventType.UiAction,
            workUnitKind = "Content",
            workUnitId = content.contentId,
            stage = "Generation",
            outcome = "Opened",
        )
        val config = repository.config.value ?: return showConfigurationRequired()
        when (val plan = planAutomaticGeneration(content)) {
            is GenerationLaunchPlan.Submit ->
                submitGeneration(
                    content,
                    plan.input,
                    config,
                    allowAsrFallback = plan.allowAsrFallback,
                    output = GenerationOutput.SourceOnly,
                    activationTrackId = null,
                )
            is GenerationLaunchPlan.Choose ->
                overlay.update {
                    it.copy(
                        generationContent = content,
                        audioTracks = emptyList(),
                        inspectingAudioTracks = false,
                        audioInspectionError = null,
                        generationOutput = GenerationOutput.SourceOnly,
                        generationAudioOnly = false,
                        generationActivationTrackId = null,
                        message = null,
                    )
                }
            is GenerationLaunchPlan.NeedsConfirmation ->
                overlay.update { it.copy(message = plan.message) }
            GenerationLaunchPlan.InspectAudio ->
                inspectAudio(
                    content = content,
                    output = GenerationOutput.SourceOnly,
                    activationTrackId = null,
                    audioOnly = false,
                )
        }
    }

    private fun inspectAudio(
        content: ContentDetails,
        output: GenerationOutput,
        activationTrackId: String?,
        audioOnly: Boolean,
    ) {
        diagnostics.recordAsync(
            SyncTextDiagnosticEventType.UiAction,
            workUnitKind = "Content",
            workUnitId = content.contentId,
            stage = "AudioInspection",
            outcome = "Started",
        )
        overlay.update {
            it.copy(
                generationContent = content,
                audioTracks = emptyList(),
                inspectingAudioTracks = true,
                audioInspectionError = null,
                generationOutput = output,
                generationAudioOnly = audioOnly,
                generationActivationTrackId = activationTrackId,
                message = null,
            )
        }
        scope.launch {
            runCatching { audio.inspect(content.activeMedia.uri) }
                .onSuccess { tracks ->
                    diagnostics.recordAsync(
                        SyncTextDiagnosticEventType.UiAction,
                        workUnitKind = "Content",
                        workUnitId = content.contentId,
                        stage = "AudioInspection",
                        outcome = "Completed",
                        reason = "trackCount=${tracks.size}",
                    )
                    if (overlay.value.generationContent?.contentId != content.contentId) return@onSuccess
                    if (tracks.isEmpty()) {
                        overlay.update {
                            it.copy(
                                inspectingAudioTracks = false,
                                audioInspectionError = "媒体中没有可用于识别的音轨",
                            )
                        }
                    } else {
                        overlay.update {
                            it.copy(audioTracks = tracks, inspectingAudioTracks = false)
                        }
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    diagnostics.recordAsync(
                        SyncTextDiagnosticEventType.UiAction,
                        workUnitKind = "Content",
                        workUnitId = content.contentId,
                        stage = "AudioInspection",
                        outcome = "Failed",
                        reason = error::class.simpleName,
                    )
                    overlay.update { current ->
                        if (current.generationContent?.contentId != content.contentId) {
                            current
                        } else {
                            current.copy(
                                inspectingAudioTracks = false,
                                audioInspectionError =
                                    error.message?.takeIf(String::isNotBlank) ?: "无法读取媒体音轨",
                            )
                        }
                    }
                }
        }
    }

    private fun submitGeneration(
        content: ContentDetails,
        input: GenerationInput,
        config: SyncTextConfig,
        allowAsrFallback: Boolean,
        output: GenerationOutput,
        activationTrackId: String?,
        sourceLanguage: String? = null,
    ) {
        diagnostics.recordAsync(
            SyncTextDiagnosticEventType.UiAction,
            workUnitKind = "Content",
            workUnitId = content.contentId,
            stage = "Generation",
            outcome = "SubmitStarted",
        )
        overlay.update {
            it.copy(
                taskLaunch = SyncTextLaunchUiState(content.contentId, SyncTextLaunchKind.Generation),
                busy = true,
                message = null,
            )
        }
        scope.launch {
            runCatching {
                val ownedInput =
                    when (input) {
                        is GenerationInput.DiscoveredCandidate ->
                            GenerationInput.ExistingTrack(materializeCandidate(input.candidateId))
                        else -> input
                    }
                repository.submit(
                    GenerationRequest(
                        contentId = content.contentId,
                        input = ownedInput,
                        output = output,
                        schemeId = config.configId,
                        allowAsrFallback = allowAsrFallback,
                        mediaLocatorId = content.activeMediaLocatorId,
                        activationTrackId = activationTrackId,
                        sourceLanguage = sourceLanguage.takeIf { ownedInput is GenerationInput.AudioTrack },
                    ),
                )
            }.onSuccess { task ->
                diagnostics.recordAsync(
                    SyncTextDiagnosticEventType.UiAction,
                    taskId = task.taskId,
                    stage = "Generation",
                    outcome = "SubmitCompleted",
                )
                overlay.update {
                    it.copy(
                        generationContent = null,
                        audioTracks = emptyList(),
                        inspectingAudioTracks = false,
                        audioInspectionError = null,
                        generationOutput = GenerationOutput.SourceOnly,
                        generationAudioOnly = false,
                        generationActivationTrackId = null,
                        taskLaunch = null,
                        busy = false,
                        message = null,
                    )
                }
            }.onFailure { error ->
                diagnostics.recordAsync(
                    SyncTextDiagnosticEventType.UiAction,
                    workUnitKind = "Content",
                    workUnitId = content.contentId,
                    stage = "Generation",
                    outcome = "SubmitFailed",
                    reason = error::class.simpleName,
                )
                handleFailure(error)
            }
        }
    }

    private fun closeGeneration() {
        overlay.update {
            it.copy(
                generationContent = null,
                audioTracks = emptyList(),
                inspectingAudioTracks = false,
                audioInspectionError = null,
                generationOutput = GenerationOutput.SourceOnly,
                generationAudioOnly = false,
                generationActivationTrackId = null,
                taskLaunch = null,
                busy = false,
                message = null,
            )
        }
    }

    private fun showConfigurationRequired() {
        overlay.update {
            it.copy(
                generationContent = null,
                inspectingAudioTracks = false,
                generationOutput = GenerationOutput.SourceOnly,
                generationAudioOnly = false,
                generationActivationTrackId = null,
                message = "请先在同步文字设置中填写语音识别配置和密钥",
            )
        }
    }

    private fun runAction(
        taskLaunch: SyncTextLaunchUiState? = null,
        block: suspend () -> String?,
    ) {
        overlay.update { it.copy(taskLaunch = taskLaunch, busy = true, message = null) }
        scope.launch {
            runCatching { block() }
                .onSuccess { message ->
                    overlay.update { it.copy(taskLaunch = null, busy = false, message = message) }
                }
                .onFailure(::handleFailure)
        }
    }

    private fun saveConfig(action: SyncTextAction.SaveConfig) {
        diagnostics.recordAsync(
            SyncTextDiagnosticEventType.UiAction,
            stage = "Configuration",
            outcome = "SaveStarted",
        )
        synchronized(configSaveCounterLock) { configSaveCounter++ }
        overlay.update { it.copy(configSaving = true, configError = null, message = null) }
        scope.launch {
            try {
                configSaveMutex.withLock {
                    repository.saveConfigWithSecrets(
                        action.draft.toConfig(action.configId),
                        asrSecret = action.draft.asrSecret,
                        translationSecret = action.draft.translationSecret,
                    )
                }
                diagnostics.record(
                    SyncTextDiagnosticEventType.UiAction,
                    stage = "Configuration",
                    outcome = "SaveCompleted",
                )
                overlay.update { it.copy(configError = null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                diagnostics.record(
                    SyncTextDiagnosticEventType.UiAction,
                    stage = "Configuration",
                    outcome = "SaveFailed",
                    reason = error::class.simpleName,
                )
                overlay.update {
                    it.copy(configError = "配置未保存，请检查必填项")
                }
            } finally {
                val remaining =
                    synchronized(configSaveCounterLock) {
                        configSaveCounter = (configSaveCounter - 1).coerceAtLeast(0)
                        configSaveCounter
                    }
                overlay.update { it.copy(configSaving = remaining > 0) }
            }
        }
    }

    private fun handleFailure(error: Throwable) {
        if (error is CancellationException) throw error
        overlay.update {
            it.copy(
                busy = false,
                inspectingAudioTracks = false,
                taskLaunch = null,
                message = error.message?.takeIf(String::isNotBlank) ?: "操作失败",
            )
        }
    }
}

private fun Throwable.toCredentialSaveMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("不能为空") -> "请输入 API Key"
        raw.contains("遮罩") || raw.contains("不可发送") -> "请输入完整的 API Key"
        else -> "API Key 保存失败"
    }
}

private fun modelCredentialFingerprint(
    credentialId: String?,
    apiKey: String?,
): String {
    val inlineKey = apiKey?.takeIf(String::isNotBlank)
    if (inlineKey == null) return "saved:${credentialId.orEmpty()}"
    val digest = MessageDigest.getInstance("SHA-256").digest(inlineKey.toByteArray(Charsets.UTF_8))
    return "inline:${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
}

private fun Throwable.toConnectionTestUiState(fingerprint: String): ConnectionTestUiState {
    val category =
        when (this) {
            is TranslationProtocolException -> category
            is AsrException -> category
            is CredentialUnavailableException -> GenerationErrorCategory.Authentication
            else -> null
        }
    val status =
        when (this) {
            is TranslationProtocolException -> httpStatus
            is AsrException -> httpStatus
            else -> null
        }
    return ConnectionTestUiState(
        success = false,
        message = message?.takeIf(String::isNotBlank) ?: "连接失败",
        testedFingerprint = fingerprint,
        failureCategory = category,
        failureStatus = status,
    )
}

private fun SyncTextConfigDraft.toConfig(configId: String?): SyncTextConfig =
    let { draft ->
        val translationConfigured =
            draft.translationEndpoint.isNotBlank() &&
                draft.translationModel.isNotBlank() &&
                (draft.translationCredentialId != null || draft.translationSecret?.isNotBlank() == true)
        SyncTextConfig(
        configId = configId.orEmpty(),
        asrEndpoint = asrEndpoint,
        asrProfile = asrProfile,
        asrCredentialId = asrCredentialId.orEmpty(),
        translationProtocol = translationProtocol,
        translationEndpoint = translationEndpoint.takeIf { translationConfigured },
        translationModel = translationModel.takeIf { translationConfigured },
        translationReasoningLevel = translationReasoningLevel,
        translationCredentialId = translationCredentialId.takeIf { translationConfigured },
        targetLanguage = targetLanguage,
        presentationMode = presentationMode,
        translationUserAgent = translationUserAgent,
        )
    }
