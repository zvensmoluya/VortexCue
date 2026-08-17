package io.github.zvensmoluya.vortexcue.ui.synctext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.AsrProfile
import io.github.zvensmoluya.vortexcue.synctext.AudioTrackInfo
import io.github.zvensmoluya.vortexcue.synctext.CredentialMetadata
import io.github.zvensmoluya.vortexcue.synctext.CredentialPurpose
import io.github.zvensmoluya.vortexcue.synctext.GenerationInput
import io.github.zvensmoluya.vortexcue.synctext.LanguageTags
import io.github.zvensmoluya.vortexcue.synctext.SyncTextConfig
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocol
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppBackground
import io.github.zvensmoluya.vortexcue.ui.theme.AppError
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import java.net.URI

@Composable
fun SyncTextUiHost(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
) {
    when {
        state.configEditorVisible ->
            SingleConfigDialog(
                state = state,
                onAction = onAction,
                onDismiss = { onAction(SyncTextAction.CloseConfigEditor) },
            )
        state.generationContent != null ->
            GenerationInputDialog(
                content = state.generationContent,
                state = state,
                onAction = onAction,
                onDismiss = { onAction(SyncTextAction.CloseGeneration) },
            )
        state.message != null ->
            AlertDialog(
                onDismissRequest = { onAction(SyncTextAction.ClearMessage) },
                title = { Text("同步文字") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { onAction(SyncTextAction.ClearMessage) }) {
                        Text("知道了", color = AppAccent)
                    }
                },
                containerColor = AppSurfaceRaised,
            )
    }
}

@Composable
private fun SingleConfigDialog(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingCredentialId by remember { mutableStateOf<String?>(null) }
    var credentialFormVisible by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "同步文字配置",
                    color = AppPrimaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("完成", color = AppAccent) }
            }
            state.message?.let {
                Text(it, color = if (it.contains("保存")) AppSecondaryText else AppError, fontSize = 12.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    ConfigForm(
                        existing = state.config,
                        credentials = state.credentials,
                        busy = state.busy,
                        onSave = { draft ->
                            onAction(SyncTextAction.SaveConfig(state.config?.configId, draft))
                        },
                    )
                }
                item {
                    Text("密钥", color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
                    state.credentials.forEach { credential ->
                        CredentialRow(
                            credential = credential,
                            onEdit = {
                                editingCredentialId = credential.credentialId
                                credentialFormVisible = true
                            },
                            onDelete = { onAction(SyncTextAction.DeleteCredential(credential.credentialId)) },
                        )
                    }
                    TextButton(
                        onClick = {
                            editingCredentialId = null
                            credentialFormVisible = true
                        },
                    ) { Text("新增密钥", color = AppAccent) }
                    if (credentialFormVisible) {
                        CredentialForm(
                            existing = state.credentials.firstOrNull { it.credentialId == editingCredentialId },
                            busy = state.busy,
                            onSave = { id, name, purpose, secret ->
                                onAction(SyncTextAction.SaveCredential(id, name, purpose, secret))
                                credentialFormVisible = false
                            },
                            onCancel = { credentialFormVisible = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigForm(
    existing: SyncTextConfig?,
    credentials: List<CredentialMetadata>,
    busy: Boolean,
    onSave: (SyncTextConfigDraft) -> Unit,
) {
    var asrProfile by remember(existing?.configId) {
        mutableStateOf(existing?.asrProfile ?: AsrProfile.OpenRouterWhisperV1)
    }
    var asrEndpoint by remember(existing?.configId) {
        mutableStateOf(existing?.asrEndpoint ?: DefaultAsrEndpoint)
    }
    var asrCredentialId by remember(existing?.configId) {
        mutableStateOf(existing?.asrCredentialId)
    }
    var protocol by remember(existing?.configId) {
        mutableStateOf(existing?.translationProtocol ?: TranslationProtocol.Responses)
    }
    var endpoint by remember(existing?.configId) {
        mutableStateOf(existing?.translationEndpoint ?: TranslationProtocol.Responses.defaultEndpoint())
    }
    var model by remember(existing?.configId) { mutableStateOf(existing?.translationModel.orEmpty()) }
    var translationCredentialId by remember(existing?.configId) {
        mutableStateOf(existing?.translationCredentialId)
    }
    var targetLanguage by remember(existing?.configId) {
        mutableStateOf(existing?.targetLanguage ?: "zh-CN")
    }
    var presentationMode by remember(existing?.configId) {
        mutableStateOf(existing?.presentationMode ?: TextPresentationMode.TargetOnly)
    }
    var customUserAgentEnabled by remember(existing?.configId) {
        mutableStateOf(existing?.translationUserAgent != null)
    }
    var userAgent by remember(existing?.configId) {
        mutableStateOf(existing?.translationUserAgent.orEmpty())
    }

    val asrCredentials = credentials.filter { it.purpose == CredentialPurpose.Asr || it.purpose == CredentialPurpose.Shared }
    val translationCredentials = credentials.filter { it.purpose == CredentialPurpose.Translation || it.purpose == CredentialPurpose.Shared }
    LaunchedEffect(asrCredentials, asrCredentialId) {
        if (asrCredentialId !in asrCredentials.map { it.credentialId }) {
            asrCredentialId = asrCredentials.firstOrNull()?.credentialId
        }
    }
    LaunchedEffect(translationCredentials, translationCredentialId) {
        if (translationCredentialId !in translationCredentials.map { it.credentialId }) {
            translationCredentialId = translationCredentials.firstOrNull()?.credentialId
        }
    }

    val normalizedTarget = normalizedLanguageOrNull(targetLanguage)
    val asrEndpointValid = isHttpsEndpoint(asrEndpoint)
    val translationEndpointValid = isHttpsEndpoint(endpoint)
    val userAgentValid = !customUserAgentEnabled || userAgent.length <= 256 && userAgent.all { it.code in 0x20..0x7e }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("语音识别", color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
        ChoiceButtons(
            options = AsrProfile.entries,
            selected = asrProfile,
            label = { if (it.experimental) "Grok（实验）" else "Whisper" },
            onSelected = { asrProfile = it },
        )
        OutlinedTextField(
            value = asrEndpoint,
            onValueChange = { asrEndpoint = it },
            label = { Text("OpenRouter Endpoint") },
            supportingText = { if (!asrEndpointValid) Text("请输入有效的 HTTPS 地址") },
            isError = !asrEndpointValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialChoices("ASR Key", asrCredentials, asrCredentialId) { asrCredentialId = it }

        Text("翻译", color = AppPrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        TranslationProtocol.entries.forEach { candidate ->
            SelectionRow(
                selected = protocol == candidate,
                title = candidate.label(),
                detail = candidate.defaultEndpoint(),
                onClick = {
                    val wasDefault = endpoint == protocol.defaultEndpoint()
                    protocol = candidate
                    if (wasDefault) endpoint = candidate.defaultEndpoint()
                },
            )
        }
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("Endpoint") },
            supportingText = { if (!translationEndpointValid) Text("请输入有效的 HTTPS 地址") },
            isError = !translationEndpointValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialChoices("翻译 Key", translationCredentials, translationCredentialId) {
            translationCredentialId = it
        }
        LanguageTagField(targetLanguage, { targetLanguage = it })
        Text("展示模式", color = AppMutedText, fontSize = 11.sp)
        ChoiceButtons(
            options = TextPresentationMode.entries,
            selected = presentationMode,
            label = { if (it == TextPresentationMode.TargetOnly) "纯译文" else "译文 + 原文" },
            onSelected = { presentationMode = it },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = customUserAgentEnabled, onCheckedChange = { customUserAgentEnabled = it })
            Text("自定义 User-Agent", color = AppPrimaryText)
        }
        if (customUserAgentEnabled) {
            OutlinedTextField(
                value = userAgent,
                onValueChange = { userAgent = it },
                label = { Text("User-Agent") },
                supportingText = { if (!userAgentValid) Text("最多 256 个可打印 ASCII 字符") },
                isError = !userAgentValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            enabled =
                !busy && asrEndpointValid && translationEndpointValid && userAgentValid &&
                    asrCredentialId != null && translationCredentialId != null &&
                    model.isNotBlank() && normalizedTarget != null,
            onClick = {
                onSave(
                    SyncTextConfigDraft(
                        asrProfile = asrProfile,
                        asrEndpoint = asrEndpoint,
                        asrCredentialId = requireNotNull(asrCredentialId),
                        translationProtocol = protocol,
                        translationEndpoint = endpoint,
                        translationUserAgent = userAgent.takeIf { customUserAgentEnabled },
                        translationModel = model,
                        translationCredentialId = requireNotNull(translationCredentialId),
                        targetLanguage = requireNotNull(normalizedTarget),
                        presentationMode = presentationMode,
                    ),
                )
            },
        ) { Text(if (busy) "保存中" else "保存配置") }
    }
}

private data class InputChoice(
    val label: String,
    val detail: String,
    val input: GenerationInput,
)

@Composable
private fun GenerationInputDialog(
    content: ContentDetails,
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = remember(content, state.audioTracks) { inputChoices(content, state.audioTracks) }
    var selectedInput by remember(content.contentId, choices) {
        mutableStateOf(choices.singleOrNull()?.input)
    }

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("选择生成输入", color = AppPrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    content.activeMedia.title ?: content.activeMedia.displayName,
                    color = AppPrimaryText,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.inspectingAudioTracks || state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AppAccent)
                    Text(
                        if (state.inspectingAudioTracks) "正在读取音轨" else "正在开始生成",
                        color = AppSecondaryText,
                        fontSize = 12.sp,
                    )
                }
                state.audioInspectionError?.let { Text(it, color = AppError, fontSize = 12.sp) }
                if (!state.inspectingAudioTracks) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(choices, key = { it.label + it.input.toString() }) { choice ->
                            SelectionRow(
                                selected = selectedInput == choice.input,
                                title = choice.label,
                                detail = choice.detail,
                                onClick = { selectedInput = choice.input },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy && !state.inspectingAudioTracks && selectedInput != null,
                onClick = { onAction(SyncTextAction.SubmitSelectedInput(requireNotNull(selectedInput))) },
            ) { Text("开始生成", color = AppAccent) }
        },
        dismissButton = {
            TextButton(enabled = !state.busy, onClick = onDismiss) {
                Text("取消", color = AppSecondaryText)
            }
        },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun CredentialChoices(
    title: String,
    credentials: List<CredentialMetadata>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Text(title, color = AppMutedText, fontSize = 11.sp)
    if (credentials.isEmpty()) {
        Text("请先在下方新增匹配用途的密钥", color = AppError, fontSize = 11.sp)
    } else {
        credentials.forEach { credential ->
            SelectionRow(
                selected = credential.credentialId == selectedId,
                title = credential.name,
                detail = credential.maskedValue,
                onClick = { onSelect(credential.credentialId) },
            )
        }
    }
}

@Composable
private fun CredentialRow(
    credential: CredentialMetadata,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(credential.name, color = AppPrimaryText)
            Text("${credential.purpose.label()} · ${credential.maskedValue}", color = AppSecondaryText, fontSize = 11.sp)
        }
        TextButton(onClick = onEdit) { Text("更新", color = AppAccent) }
        TextButton(onClick = onDelete) { Text("删除", color = AppError) }
    }
}

@Composable
private fun CredentialForm(
    existing: CredentialMetadata?,
    busy: Boolean,
    onSave: (String?, String, CredentialPurpose, String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(existing?.credentialId) { mutableStateOf(existing?.name.orEmpty()) }
    var purpose by remember(existing?.credentialId) { mutableStateOf(existing?.purpose ?: CredentialPurpose.Shared) }
    var secret by remember(existing?.credentialId) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (existing == null) "新增密钥" else "更新密钥", color = AppPrimaryText, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceButtons(
            options = CredentialPurpose.entries,
            selected = purpose,
            label = CredentialPurpose::label,
            onSelected = { purpose = it },
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(if (existing == null) "API Key" else "新的 API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Button(
                enabled = !busy && name.isNotBlank() && secret.isNotBlank(),
                onClick = { onSave(existing?.credentialId, name, purpose, secret) },
            ) { Text("保存") }
            TextButton(onClick = onCancel) { Text("取消", color = AppSecondaryText) }
        }
    }
}

@Composable
private fun SelectionRow(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AppPrimaryText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, color = AppMutedText, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun <T> ChoiceButtons(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            TextButton(onClick = { onSelected(option) }, modifier = Modifier.weight(1f)) {
                Text(
                    label(option),
                    color = if (selected == option) AppAccent else AppSecondaryText,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun LanguageTagField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val normalized = normalizedLanguageOrNull(value)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("目标语言") },
        supportingText = {
            Text(if (normalized == null) "请输入有效的 BCP-47 语言标签" else "将使用 $normalized")
        },
        isError = normalized == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun inputChoices(
    content: ContentDetails,
    audioTracks: List<AudioTrackInfo>,
): List<InputChoice> =
    buildList {
        content.timedTextTracks
            .filter { it.readiness !in setOf(TimedTextReadiness.Unreadable, TimedTextReadiness.ParseFailed) }
            .forEach { track ->
                val input =
                    when (track.origin) {
                        TimedTextOrigin.Embedded ->
                            track.locator.embeddedTrackId?.let {
                                GenerationInput.EmbeddedTrack(track.stableId, it)
                            }
                        else -> GenerationInput.ExistingTrack(track.stableId)
                    } ?: return@forEach
                add(
                    InputChoice(
                        label = track.label ?: track.language ?: track.locator.displayName,
                        detail = "使用现有文字生成译文",
                        input = input,
                    ),
                )
            }
        audioTracks.forEachIndexed { index, track ->
            val identity = track.label ?: track.language ?: "音轨 ${index + 1}"
            val detail =
                listOfNotNull(
                    track.language,
                    track.channelCount?.let { "$it 声道" },
                ).distinct().joinToString(" · ").ifBlank { "使用语音识别生成文字" }
            add(InputChoice(identity, detail, GenerationInput.AudioTrack(track.selectionId)))
        }
    }

private fun normalizedLanguageOrNull(value: String): String? =
    value.takeIf(String::isNotBlank)?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() }

internal fun isHttpsEndpoint(value: String): Boolean {
    val endpoint = value.trim().replace("{model}", "model")
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
}

private fun TranslationProtocol.label(): String =
    when (this) {
        TranslationProtocol.Responses -> "Responses"
        TranslationProtocol.AnthropicMessages -> "Anthropic Messages"
        TranslationProtocol.ChatCompletions -> "Chat Completions"
        TranslationProtocol.GoogleGenerateContent -> "Google generateContent"
    }

private fun TranslationProtocol.defaultEndpoint(): String =
    when (this) {
        TranslationProtocol.Responses -> "https://api.openai.com/v1/responses"
        TranslationProtocol.AnthropicMessages -> "https://api.anthropic.com/v1/messages"
        TranslationProtocol.ChatCompletions -> "https://api.openai.com/v1/chat/completions"
        TranslationProtocol.GoogleGenerateContent ->
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    }

private fun CredentialPurpose.label(): String =
    when (this) {
        CredentialPurpose.Asr -> "ASR"
        CredentialPurpose.Translation -> "翻译"
        CredentialPurpose.Shared -> "共用"
    }

private const val DefaultAsrEndpoint = "https://openrouter.ai/api/v1/audio/transcriptions"
