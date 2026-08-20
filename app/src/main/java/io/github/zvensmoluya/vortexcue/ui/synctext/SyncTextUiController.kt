package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.AsrProfile
import io.github.zvensmoluya.vortexcue.synctext.AudioTrackInfo
import io.github.zvensmoluya.vortexcue.synctext.AudioWindowExtractor
import io.github.zvensmoluya.vortexcue.synctext.CredentialMetadata
import io.github.zvensmoluya.vortexcue.synctext.CredentialPurpose
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
import io.github.zvensmoluya.vortexcue.synctext.SyncTextRepository
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocol
import java.util.concurrent.CancellationException
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

data class SyncTextConfigDraft(
    val asrProfile: AsrProfile,
    val asrEndpoint: String,
    val asrCredentialId: String?,
    val asrSecret: String?,
    val translationProtocol: TranslationProtocol,
    val translationEndpoint: String,
    val translationUserAgent: String?,
    val translationModel: String,
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

data class SyncTextUiState(
    val config: SyncTextConfig? = null,
    val credentials: List<CredentialMetadata> = emptyList(),
    val tasks: List<GenerationTaskSummary> = emptyList(),
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val availableTranslationModels: List<String> = emptyList(),
    val loadingTranslationModels: Boolean = false,
    val translationConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val asrConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val configSaving: Boolean = false,
    val configError: String? = null,
    val credentialSave: CredentialSaveUiState? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

sealed interface SyncTextAction {
    data class OpenGeneration(val content: ContentDetails) : SyncTextAction

    data object CloseGeneration : SyncTextAction

    data class SubmitSelectedInput(val input: GenerationInput) : SyncTextAction

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

    data class RetryForContent(val contentId: String) : SyncTextAction

    data class TranslateForContent(val contentId: String) : SyncTextAction
}

private data class OverlayState(
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val availableTranslationModels: List<String> = emptyList(),
    val loadingTranslationModels: Boolean = false,
    val translationConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val asrConnectionTest: ConnectionTestUiState = ConnectionTestUiState(),
    val configSaving: Boolean = false,
    val configError: String? = null,
    val credentialSave: CredentialSaveUiState? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class SyncTextUiController(
    private val repository: SyncTextRepository,
    private val audio: AudioWindowExtractor,
    private val materializeCandidate: suspend (String) -> String,
    private val scope: CoroutineScope,
) {
    private val overlay = MutableStateFlow(OverlayState())
    private val configSaveMutex = Mutex()
    private val configSaveCounterLock = Any()
    private var configSaveCounter = 0
    private var credentialSaveRequestId = 0L
    private var modelDiscoveryJob: Job? = null
    private var modelDiscoveryToken = 0L

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
                translationConnectionTest = local.translationConnectionTest,
                asrConnectionTest = local.asrConnectionTest,
                configSaving = local.configSaving,
                configError = local.configError,
                credentialSave = local.credentialSave,
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
                submitGeneration(content, action.input, config, allowAsrFallback = false)
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
            is SyncTextAction.RetryForContent -> runAction {
                repository.retryForContent(action.contentId)
                "同步文字已重试"
            }
            is SyncTextAction.TranslateForContent -> runAction {
                val source = requireNotNull(repository.currentSourceResult(action.contentId)) { "没有可翻译的源文字" }
                val target = repository.config.value?.targetLanguage?.let(LanguageTags::normalizeOrNull)
                if (target == null || LanguageTags.normalizeOrNull(source.sourceLanguage) == target) {
                    "源语言与目标语言相同，无需翻译"
                } else {
                    repository.submitTranslation(source.resultId, targetLanguage = target)
                    "同步翻译已开始"
                }
            }
        }
    }

    private fun saveCredential(action: SyncTextAction.SaveCredential) {
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
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
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
        modelDiscoveryJob?.cancel()
        val token = ++modelDiscoveryToken
        if (endpoint.isBlank() || credentialId.isNullOrBlank() && apiKey.isNullOrBlank()) {
            overlay.update {
                it.copy(
                    availableTranslationModels = emptyList(),
                    loadingTranslationModels = false,
                )
            }
            return
        }
        overlay.update {
            it.copy(
                availableTranslationModels = emptyList(),
                loadingTranslationModels = true,
            )
        }
        modelDiscoveryJob =
            scope.launch {
                delay(450L)
                runCatching {
                    repository.discoverTranslationModels(protocol, endpoint, credentialId, apiKey)
                }.onSuccess { models ->
                    if (token != modelDiscoveryToken) return@onSuccess
                    overlay.update {
                        it.copy(
                            availableTranslationModels = models,
                            loadingTranslationModels = false,
                        )
                    }
                }.onFailure { error ->
                    if (token != modelDiscoveryToken || error is CancellationException) return@onFailure
                    overlay.update {
                        it.copy(
                            availableTranslationModels = emptyList(),
                            loadingTranslationModels = false,
                        )
                    }
                }
            }
    }

    private fun testTranslationConnection(
        protocol: TranslationProtocol,
        endpoint: String,
        model: String,
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
                repository.testTranslationConnection(protocol, endpoint, model, credentialId, apiKey)
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
                repository.testAsrConnection(profile, endpoint, credentialId, apiKey)
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

    private fun openGeneration(content: ContentDetails) {
        val config = repository.config.value ?: return showConfigurationRequired()
        when (val plan = planAutomaticGeneration(content)) {
            is GenerationLaunchPlan.Submit ->
                submitGeneration(content, plan.input, config, allowAsrFallback = plan.allowAsrFallback)
            is GenerationLaunchPlan.Choose ->
                overlay.update {
                    it.copy(
                        generationContent = content,
                        audioTracks = emptyList(),
                        inspectingAudioTracks = false,
                        audioInspectionError = null,
                        message = null,
                    )
                }
            is GenerationLaunchPlan.NeedsConfirmation ->
                overlay.update { it.copy(message = plan.message) }
            GenerationLaunchPlan.InspectAudio -> inspectAudio(content, config)
        }
    }

    private fun inspectAudio(content: ContentDetails, config: SyncTextConfig) {
        overlay.update {
            it.copy(
                generationContent = content,
                audioTracks = emptyList(),
                inspectingAudioTracks = true,
                audioInspectionError = null,
                message = null,
            )
        }
        scope.launch {
            runCatching { audio.inspect(content.activeMedia.uri) }
                .onSuccess { tracks ->
                    if (overlay.value.generationContent?.contentId != content.contentId) return@onSuccess
                    when (tracks.size) {
                        0 ->
                            overlay.update {
                                it.copy(
                                    inspectingAudioTracks = false,
                                    audioInspectionError = "媒体中没有可用于识别的音轨",
                                )
                            }
                        1 ->
                            submitGeneration(
                                content,
                                GenerationInput.AudioTrack(tracks.single().selectionId),
                                config,
                                allowAsrFallback = false,
                            )
                        else ->
                            overlay.update {
                                it.copy(audioTracks = tracks, inspectingAudioTracks = false)
                            }
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
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
    ) {
        overlay.update { it.copy(busy = true, message = null) }
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
                        output = GenerationOutput.SourceOnly,
                        schemeId = config.configId,
                        allowAsrFallback = allowAsrFallback,
                    ),
                )
            }.onSuccess {
                overlay.update {
                    it.copy(
                        generationContent = null,
                        audioTracks = emptyList(),
                        inspectingAudioTracks = false,
                        audioInspectionError = null,
                        busy = false,
                        message = "同步文字已开始",
                    )
                }
            }.onFailure(::handleFailure)
        }
    }

    private fun closeGeneration() {
        overlay.update {
            it.copy(
                generationContent = null,
                audioTracks = emptyList(),
                inspectingAudioTracks = false,
                audioInspectionError = null,
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
                message = "请先在同步文字设置中填写语音识别配置和密钥",
            )
        }
    }

    private fun runAction(block: suspend () -> String?) {
        overlay.update { it.copy(busy = true, message = null) }
        scope.launch {
            runCatching { block() }
                .onSuccess { message -> overlay.update { it.copy(busy = false, message = message) } }
                .onFailure(::handleFailure)
        }
    }

    private fun saveConfig(action: SyncTextAction.SaveConfig) {
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
                overlay.update { it.copy(configError = null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
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
        translationCredentialId = translationCredentialId.takeIf { translationConfigured },
        targetLanguage = targetLanguage,
        presentationMode = presentationMode,
        translationUserAgent = translationUserAgent,
        )
    }
