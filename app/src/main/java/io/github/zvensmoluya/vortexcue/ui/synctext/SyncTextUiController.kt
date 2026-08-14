package io.github.zvensmoluya.vortexcue.ui.synctext

import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.AsrProfile
import io.github.zvensmoluya.vortexcue.synctext.AudioTrackInfo
import io.github.zvensmoluya.vortexcue.synctext.AudioWindowExtractor
import io.github.zvensmoluya.vortexcue.synctext.CredentialMetadata
import io.github.zvensmoluya.vortexcue.synctext.CredentialPurpose
import io.github.zvensmoluya.vortexcue.synctext.GeneratedTextPresentation
import io.github.zvensmoluya.vortexcue.synctext.GenerationRequest
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.SyncTextRepository
import io.github.zvensmoluya.vortexcue.synctext.SyncTextScheme
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

data class SyncTextSchemeDraft(
    val name: String,
    val asrProfile: AsrProfile,
    val asrCredentialId: String,
    val translationProtocol: TranslationProtocol,
    val translationEndpoint: String,
    val translationModel: String,
    val translationCredentialId: String,
    val defaultTargetLanguage: String,
    val defaultPresentationMode: TextPresentationMode,
    val isDefault: Boolean,
)

data class SyncTextUiState(
    val schemes: List<SyncTextScheme> = emptyList(),
    val credentials: List<CredentialMetadata> = emptyList(),
    val tasks: List<GenerationTaskSummary> = emptyList(),
    val pauseAll: Boolean = false,
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val taskDrawerVisible: Boolean = false,
    val schemeEditorVisible: Boolean = false,
    val resultDetails: GeneratedTextPresentation? = null,
    val loadingResultDetails: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

sealed interface SyncTextAction {
    data class OpenGeneration(val content: ContentDetails) : SyncTextAction

    data object CloseGeneration : SyncTextAction

    data object OpenTaskDrawer : SyncTextAction

    data object CloseTaskDrawer : SyncTextAction

    data object OpenSchemeEditor : SyncTextAction

    data object CloseSchemeEditor : SyncTextAction

    data class OpenResultDetails(val slotId: String) : SyncTextAction

    data object CloseResultDetails : SyncTextAction

    data object ClearMessage : SyncTextAction

    data class SaveCredential(
        val credentialId: String?,
        val name: String,
        val purpose: CredentialPurpose,
        val secret: String,
    ) : SyncTextAction

    data class DeleteCredential(val credentialId: String) : SyncTextAction

    data class SaveScheme(
        val schemeId: String?,
        val draft: SyncTextSchemeDraft,
    ) : SyncTextAction

    data class DeleteScheme(val schemeId: String) : SyncTextAction

    data class Submit(val request: GenerationRequest) : SyncTextAction

    data class PauseTask(val taskId: String) : SyncTextAction

    data class ResumeTask(val taskId: String) : SyncTextAction

    data class DeleteTask(val taskId: String) : SyncTextAction

    data class MoveTask(val taskId: String, val targetPosition: Int) : SyncTextAction

    data class ChangeTaskScheme(val taskId: String, val schemeId: String) : SyncTextAction

    data class SetPauseAll(val paused: Boolean) : SyncTextAction

    data class SetPresentationMode(
        val slotId: String,
        val mode: TextPresentationMode,
    ) : SyncTextAction
}

private data class OverlayState(
    val generationContent: ContentDetails? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val inspectingAudioTracks: Boolean = false,
    val audioInspectionError: String? = null,
    val taskDrawerVisible: Boolean = false,
    val schemeEditorVisible: Boolean = false,
    val resultDetails: GeneratedTextPresentation? = null,
    val loadingResultDetails: Boolean = false,
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
            repository.schemes,
            repository.credentialMetadata,
            repository.tasks,
            repository.pauseAll,
            overlay,
        ) {
                schemes,
                credentials,
                tasks,
                pauseAll,
                local,
            ->
            SyncTextUiState(
                schemes = schemes,
                credentials = credentials,
                tasks = tasks,
                pauseAll = pauseAll,
                generationContent = local.generationContent,
                audioTracks = local.audioTracks,
                inspectingAudioTracks = local.inspectingAudioTracks,
                audioInspectionError = local.audioInspectionError,
                taskDrawerVisible = local.taskDrawerVisible,
                schemeEditorVisible = local.schemeEditorVisible,
                resultDetails = local.resultDetails,
                loadingResultDetails = local.loadingResultDetails,
                busy = local.busy,
                message = local.message,
            )
        }.stateIn(scope, SharingStarted.Eagerly, SyncTextUiState())

    fun dispatch(action: SyncTextAction) {
        when (action) {
            is SyncTextAction.OpenGeneration -> openGeneration(action.content)
            SyncTextAction.CloseGeneration ->
                overlay.update {
                    it.copy(
                        generationContent = null,
                        audioTracks = emptyList(),
                        inspectingAudioTracks = false,
                        audioInspectionError = null,
                        message = null,
                    )
                }
            SyncTextAction.OpenTaskDrawer ->
                overlay.update { it.copy(taskDrawerVisible = true, message = null) }
            SyncTextAction.CloseTaskDrawer ->
                overlay.update { it.copy(taskDrawerVisible = false, message = null) }
            SyncTextAction.OpenSchemeEditor ->
                overlay.update { it.copy(schemeEditorVisible = true, message = null) }
            SyncTextAction.CloseSchemeEditor ->
                overlay.update { it.copy(schemeEditorVisible = false, message = null) }
            is SyncTextAction.OpenResultDetails -> openResultDetails(action.slotId)
            SyncTextAction.CloseResultDetails ->
                overlay.update { it.copy(resultDetails = null, loadingResultDetails = false) }
            SyncTextAction.ClearMessage -> overlay.update { it.copy(message = null) }
            is SyncTextAction.SaveCredential -> runAction {
                repository.saveCredential(
                    credentialId = action.credentialId,
                    name = action.name,
                    purpose = action.purpose,
                    secret = action.secret,
                )
                "凭据已保存"
            }
            is SyncTextAction.DeleteCredential -> runAction {
                repository.deleteCredential(action.credentialId)
                "凭据已删除"
            }
            is SyncTextAction.SaveScheme -> runAction {
                saveScheme(action.schemeId, action.draft)
                "方案已保存"
            }
            is SyncTextAction.DeleteScheme -> runAction {
                check(repository.deleteScheme(action.schemeId)) { "方案不存在或仍被任务引用" }
                "方案已删除"
            }
            is SyncTextAction.Submit -> runAction(onSuccess = {
                overlay.update {
                    it.copy(
                        generationContent = null,
                        taskDrawerVisible = true,
                        message = "任务已进入队列",
                    )
                }
            }) {
                repository.submit(action.request)
                null
            }
            is SyncTextAction.PauseTask -> runAction { repository.pauseTask(action.taskId); null }
            is SyncTextAction.ResumeTask -> runAction { repository.resumeTask(action.taskId); null }
            is SyncTextAction.DeleteTask -> runAction { repository.deleteTask(action.taskId); null }
            is SyncTextAction.MoveTask -> runAction {
                repository.moveTask(action.taskId, action.targetPosition)
                null
            }
            is SyncTextAction.ChangeTaskScheme -> runAction {
                repository.changeScheme(action.taskId, action.schemeId)
                "已创建新尝试，完成的原文会继续复用"
            }
            is SyncTextAction.SetPauseAll -> runAction {
                repository.setPauseAll(action.paused)
                null
            }
            is SyncTextAction.SetPresentationMode -> runAction {
                repository.setPresentationMode(action.slotId, action.mode)
                null
            }
        }
    }

    private fun openGeneration(content: ContentDetails) {
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
                    overlay.update { current ->
                        if (current.generationContent?.contentId != content.contentId) {
                            current
                        } else {
                            current.copy(
                                audioTracks = tracks,
                                inspectingAudioTracks = false,
                                audioInspectionError = "媒体中没有可解码音轨".takeIf { tracks.isEmpty() },
                            )
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

    private fun openResultDetails(slotId: String) {
        overlay.update { it.copy(loadingResultDetails = true, message = null) }
        scope.launch {
            runCatching { repository.loadPresentation(slotId) }
                .onSuccess { presentation ->
                    overlay.update {
                        it.copy(
                            resultDetails = presentation,
                            loadingResultDetails = false,
                            message = if (presentation == null) "生成结果已不可用" else null,
                        )
                    }
                }.onFailure(::handleFailure)
        }
    }

    private suspend fun saveScheme(schemeId: String?, draft: SyncTextSchemeDraft) {
        if (schemeId == null) {
            repository.createScheme(
                name = draft.name,
                asrProfile = draft.asrProfile,
                asrCredentialId = draft.asrCredentialId,
                translationProtocol = draft.translationProtocol,
                translationEndpoint = draft.translationEndpoint,
                translationModel = draft.translationModel,
                translationCredentialId = draft.translationCredentialId,
                defaultTargetLanguage = draft.defaultTargetLanguage,
                defaultPresentationMode = draft.defaultPresentationMode,
                isDefault = draft.isDefault,
            )
            return
        }
        val existing = requireNotNull(repository.schemes.value.firstOrNull { it.schemeId == schemeId }) {
            "方案已不存在"
        }
        repository.saveScheme(
            existing.copy(
                name = draft.name,
                asrProfile = draft.asrProfile,
                asrCredentialId = draft.asrCredentialId,
                translationProtocol = draft.translationProtocol,
                translationEndpoint = draft.translationEndpoint,
                translationModel = draft.translationModel,
                translationCredentialId = draft.translationCredentialId,
                defaultTargetLanguage = draft.defaultTargetLanguage,
                defaultPresentationMode = draft.defaultPresentationMode,
                isDefault = draft.isDefault,
            ),
        )
    }

    private fun runAction(
        onSuccess: (() -> Unit)? = null,
        block: suspend () -> String?,
    ) {
        overlay.update { it.copy(busy = true, message = null) }
        scope.launch {
            runCatching { block() }
                .onSuccess { message ->
                    overlay.update { it.copy(busy = false, message = message) }
                    onSuccess?.invoke()
                }.onFailure(::handleFailure)
        }
    }

    private fun handleFailure(error: Throwable) {
        if (error is CancellationException) throw error
        overlay.update {
            it.copy(
                busy = false,
                loadingResultDetails = false,
                message = error.message?.takeIf(String::isNotBlank) ?: "操作失败",
            )
        }
    }
}
