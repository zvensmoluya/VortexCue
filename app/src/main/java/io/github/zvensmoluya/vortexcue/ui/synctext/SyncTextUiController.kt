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
import io.github.zvensmoluya.vortexcue.synctext.GenerationOutput
import io.github.zvensmoluya.vortexcue.synctext.GenerationRequest
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.SyncTextConfig
import io.github.zvensmoluya.vortexcue.synctext.SyncTextRepository
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocol
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncTextConfigDraft(
    val asrProfile: AsrProfile,
    val asrEndpoint: String,
    val asrCredentialId: String,
    val translationProtocol: TranslationProtocol,
    val translationEndpoint: String,
    val translationUserAgent: String?,
    val translationModel: String,
    val translationCredentialId: String,
    val targetLanguage: String,
    val presentationMode: TextPresentationMode,
)

data class SyncTextUiState(
    val config: SyncTextConfig? = null,
    val credentials: List<CredentialMetadata> = emptyList(),
    val tasks: List<GenerationTaskSummary> = emptyList(),
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val configEditorVisible: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

sealed interface SyncTextAction {
    data class OpenGeneration(val content: ContentDetails) : SyncTextAction

    data object CloseGeneration : SyncTextAction

    data class SubmitSelectedInput(val input: GenerationInput) : SyncTextAction

    data object OpenConfigEditor : SyncTextAction

    data object CloseConfigEditor : SyncTextAction

    data object ClearMessage : SyncTextAction

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
}

private data class OverlayState(
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val configEditorVisible: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class SyncTextUiController(
    private val repository: SyncTextRepository,
    private val audio: AudioWindowExtractor,
    private val scope: CoroutineScope,
) {
    private val overlay = MutableStateFlow(OverlayState())

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
                configEditorVisible = local.configEditorVisible,
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
                submitGeneration(content, action.input, config)
            }
            SyncTextAction.OpenConfigEditor ->
                overlay.update { it.copy(configEditorVisible = true, message = null) }
            SyncTextAction.CloseConfigEditor ->
                overlay.update { it.copy(configEditorVisible = false, message = null) }
            SyncTextAction.ClearMessage -> overlay.update { it.copy(message = null) }
            is SyncTextAction.SaveCredential -> runAction {
                repository.saveCredential(
                    credentialId = action.credentialId,
                    name = action.name,
                    purpose = action.purpose,
                    secret = action.secret,
                )
                "密钥已保存"
            }
            is SyncTextAction.DeleteCredential -> runAction {
                repository.deleteCredential(action.credentialId)
                "密钥已删除"
            }
            is SyncTextAction.SaveConfig -> runAction {
                repository.saveConfig(action.draft.toConfig(action.configId))
                "配置已保存"
            }
            is SyncTextAction.SetPresentationMode -> runAction {
                repository.setPresentationMode(action.slotId, action.mode)
                null
            }
            is SyncTextAction.CancelForContent -> runAction {
                repository.cancelForContent(action.contentId)
                "同步文字已取消"
            }
            is SyncTextAction.RetryForContent -> runAction {
                repository.retryForContent(action.contentId)
                "同步文字已重试"
            }
        }
    }

    private fun openGeneration(content: ContentDetails) {
        val config = repository.config.value ?: return showConfigurationRequired()
        when (val plan = planAutomaticGeneration(content)) {
            is GenerationLaunchPlan.Submit -> submitGeneration(content, plan.input, config)
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
                        1 -> submitGeneration(content, GenerationInput.AudioTrack(tracks.single().selectionId), config)
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
    ) {
        overlay.update { it.copy(busy = true, message = null) }
        scope.launch {
            runCatching {
                repository.submit(
                    GenerationRequest(
                        contentId = content.contentId,
                        input = input,
                        output =
                            GenerationOutput.Translated(
                                targetLanguage = config.targetLanguage,
                                presentationMode = config.presentationMode,
                            ),
                        schemeId = config.configId,
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
                message = "请先在配置页填写 ASR、翻译和密钥",
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

internal sealed interface GenerationLaunchPlan {
    data class Submit(val input: GenerationInput) : GenerationLaunchPlan

    data class Choose(val inputs: List<GenerationInput>) : GenerationLaunchPlan

    data object InspectAudio : GenerationLaunchPlan
}

internal fun planAutomaticGeneration(content: ContentDetails): GenerationLaunchPlan {
    val inputs = content.availableTextInputs()
    val selected = inputs.firstOrNull { it.trackId == content.activeText.primaryTrackId }
    return when {
        selected != null -> GenerationLaunchPlan.Submit(selected)
        inputs.size == 1 -> GenerationLaunchPlan.Submit(inputs.single())
        inputs.size > 1 -> GenerationLaunchPlan.Choose(inputs)
        else -> GenerationLaunchPlan.InspectAudio
    }
}

private fun ContentDetails.availableTextInputs(): List<GenerationInput> =
    timedTextTracks
        .filter { it.readiness !in setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed) }
        .mapNotNull { track ->
            when (track.origin) {
                TimedTextOrigin.Embedded ->
                    track.locator.embeddedTrackId?.let {
                        GenerationInput.EmbeddedTrack(track.stableId, it)
                    }
                else -> GenerationInput.ExistingTrack(track.stableId)
            }
        }

private fun SyncTextConfigDraft.toConfig(configId: String?): SyncTextConfig =
    SyncTextConfig(
        configId = configId.orEmpty(),
        asrEndpoint = asrEndpoint,
        asrProfile = asrProfile,
        asrCredentialId = asrCredentialId,
        translationProtocol = translationProtocol,
        translationEndpoint = translationEndpoint,
        translationModel = translationModel,
        translationCredentialId = translationCredentialId,
        targetLanguage = targetLanguage,
        presentationMode = presentationMode,
        translationUserAgent = translationUserAgent,
    )
