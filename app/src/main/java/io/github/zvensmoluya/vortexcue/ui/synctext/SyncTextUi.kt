package io.github.zvensmoluya.vortexcue.ui.synctext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.AsrProfile
import io.github.zvensmoluya.vortexcue.synctext.AudioTrackInfo
import io.github.zvensmoluya.vortexcue.synctext.CredentialMetadata
import io.github.zvensmoluya.vortexcue.synctext.CredentialPurpose
import io.github.zvensmoluya.vortexcue.synctext.GenerationInput
import io.github.zvensmoluya.vortexcue.synctext.LanguageTags
import io.github.zvensmoluya.vortexcue.synctext.OpenRouterAsrEngine
import io.github.zvensmoluya.vortexcue.synctext.SyncTextConfig
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocol
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppError
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceSelected
import java.net.URI

@Composable
fun SyncTextUiHost(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
) {
    when {
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

private enum class ConfigSection {
    Translation,
    Asr,
}

@Composable
private fun ConfigForm(
    existing: SyncTextConfig?,
    credentials: List<CredentialMetadata>,
    section: ConfigSection,
    availableModels: List<String>,
    loadingModels: Boolean,
    modelDiscoveryMessage: String?,
    translationConnectionTest: ConnectionTestUiState,
    asrConnectionTest: ConnectionTestUiState,
    busy: Boolean,
    onSectionChanged: (ConfigSection) -> Unit,
    onDiscoverModels: (TranslationProtocol, String, String?, String?) -> Unit,
    onTestTranslation: (TranslationProtocol, String, String, String?, String?) -> Unit,
    onTestAsr: (AsrProfile, String, String?, String?) -> Unit,
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
    var asrSecret by remember(existing?.configId) { mutableStateOf("") }
    var protocol by remember(existing?.configId) {
        mutableStateOf(existing?.translationProtocol ?: TranslationProtocol.Responses)
    }
    var endpoint by remember(existing?.configId) {
        mutableStateOf(existing?.translationEndpoint?.toBaseUrl() ?: TranslationProtocol.Responses.defaultBaseUrl())
    }
    var model by remember(existing?.configId) { mutableStateOf(existing?.translationModel.orEmpty()) }
    var translationCredentialId by remember(existing?.configId) {
        mutableStateOf(existing?.translationCredentialId)
    }
    var translationSecret by remember(existing?.configId) { mutableStateOf("") }
    var targetLanguage by remember(existing?.configId) {
        mutableStateOf(existing?.targetLanguage ?: "zh-CN")
    }
    var presentationMode by remember(existing?.configId) {
        mutableStateOf(existing?.presentationMode ?: TextPresentationMode.TargetOnly)
    }
    var dirty by remember(existing?.configId) { mutableStateOf(false) }
    LaunchedEffect(existing) { dirty = false }
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
    LaunchedEffect(protocol, endpoint, translationCredentialId, translationSecret) {
        if (translationEndpointValid && (translationCredentialId != null || translationSecret.isNotBlank())) {
            onDiscoverModels(protocol, endpoint, translationCredentialId, translationSecret.takeIf(String::isNotBlank))
        }
    }

    val draft = SyncTextConfigDraft(
        asrProfile = asrProfile,
        asrEndpoint = asrEndpoint,
        asrCredentialId = asrCredentialId,
        asrSecret = asrSecret.takeIf(String::isNotBlank),
        translationProtocol = protocol,
        translationEndpoint = endpoint,
        translationUserAgent = existing?.translationUserAgent,
        translationModel = model,
        translationCredentialId = translationCredentialId,
        translationSecret = translationSecret.takeIf(String::isNotBlank),
        targetLanguage = requireNotNull(normalizedTarget ?: "zh-CN"),
        presentationMode = presentationMode,
    )
    val canSave = !busy && asrEndpointValid && translationEndpointValid &&
        (asrCredentialId != null || asrSecret.isNotBlank()) &&
        (translationCredentialId != null || translationSecret.isNotBlank()) &&
        model.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised, RoundedCornerShape(14.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(ConfigSection.Translation to "翻译", ConfigSection.Asr to "语音识别").forEach { (candidate, label) ->
                TextButton(
                    onClick = { onSectionChanged(candidate) },
                    modifier = Modifier.weight(1f).background(
                        if (section == candidate) AppSurfaceSelected else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    ),
                ) {
                    Text(label, color = if (section == candidate) AppPrimaryText else AppSecondaryText)
                }
            }
        }
        if (dirty) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("有未保存修改", color = AppMutedText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(enabled = canSave, onClick = { onSave(draft) }) {
                    Text(if (busy) "保存中…" else "保存", color = AppAccent)
                }
            }
        }
        if (section == ConfigSection.Translation) {
            Column(
                modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("翻译服务", color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
                ProtocolPicker(
                    value = protocol,
                    onValueChange = { candidate ->
                        val wasDefault = endpoint == protocol.defaultBaseUrl() || endpoint == protocol.defaultEndpoint()
                        protocol = candidate
                        if (wasDefault) endpoint = candidate.defaultBaseUrl()
                        dirty = true
                    },
                )
                BaseUrlField(
                    value = endpoint,
                    placeholder = protocol.defaultBaseUrl(),
                    supportingText = when {
                        !translationEndpointValid -> "请输入有效的 HTTPS 地址"
                        loadingModels -> "正在查找可用模型…"
                        else -> modelDiscoveryMessage
                    },
                    isError = !translationEndpointValid,
                    onValueChange = { endpoint = it; dirty = true },
                )
                ModelPicker(
                    value = model,
                    options = availableModels,
                    loading = loadingModels,
                    onValueChange = { model = it; dirty = true },
                )
                ApiKeyField(
                    value = translationSecret,
                    hasSavedKey = translationCredentialId != null,
                    onValueChange = { translationSecret = it; dirty = true },
                )
                ConnectionTestButton(
                    state = translationConnectionTest,
                    enabled = translationEndpointValid && model.isNotBlank() &&
                        (translationCredentialId != null || translationSecret.isNotBlank()),
                    onClick = {
                        onTestTranslation(
                            protocol,
                            endpoint,
                            model,
                            translationCredentialId,
                            translationSecret.takeIf(String::isNotBlank),
                        )
                    },
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().background(AppSurfaceRaised, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("语音识别", color = AppPrimaryText, fontWeight = FontWeight.SemiBold)
                ChoiceButtons(
                    options = AsrProfile.entries,
                    selected = asrProfile,
                    label = { it.displayLabel() },
                    onSelected = { asrProfile = it; dirty = true },
                )
                Text("当前模型：${asrProfile.displayModelLabel()}", color = AppMutedText, fontSize = 11.sp)
                BaseUrlField(
                    value = asrEndpoint,
                    placeholder = "https://openrouter.ai/api/v1",
                    supportingText = if (!asrEndpointValid) "请输入有效的 HTTPS 地址" else null,
                    isError = !asrEndpointValid,
                    onValueChange = { asrEndpoint = it; dirty = true },
                )
                ApiKeyField(
                    value = asrSecret,
                    hasSavedKey = asrCredentialId != null,
                    onValueChange = { asrSecret = it; dirty = true },
                )
                ConnectionTestButton(
                    state = asrConnectionTest,
                    enabled = asrEndpointValid && (asrCredentialId != null || asrSecret.isNotBlank()),
                    onClick = {
                        onTestAsr(
                            asrProfile,
                            asrEndpoint,
                            asrCredentialId,
                            asrSecret.takeIf(String::isNotBlank),
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun SyncTextConfigPage(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
    listState: LazyListState,
) {
    var section by rememberSaveable { mutableStateOf(ConfigSection.Translation) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ConfigForm(
                existing = state.config,
                credentials = state.credentials,
                section = section,
                availableModels = state.availableTranslationModels,
                loadingModels = state.loadingTranslationModels,
                modelDiscoveryMessage = state.modelDiscoveryMessage,
                translationConnectionTest = state.translationConnectionTest,
                asrConnectionTest = state.asrConnectionTest,
                busy = state.busy,
                onSectionChanged = { section = it },
                onDiscoverModels = { protocol, endpoint, credentialId, apiKey ->
                    onAction(SyncTextAction.DiscoverTranslationModels(protocol, endpoint, credentialId, apiKey))
                },
                onTestTranslation = { protocol, endpoint, model, credentialId, apiKey ->
                    onAction(SyncTextAction.TestTranslationConnection(protocol, endpoint, model, credentialId, apiKey))
                },
                onTestAsr = { profile, endpoint, credentialId, apiKey ->
                    onAction(SyncTextAction.TestAsrConnection(profile, endpoint, credentialId, apiKey))
                },
                onSave = { draft ->
                    onAction(SyncTextAction.SaveConfig(state.config?.configId, draft))
                },
            )
        }
    }
}

@Composable
private fun BaseUrlField(
    value: String,
    placeholder: String,
    supportingText: String?,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue(value)) }
    var selectAllOnFocus by remember { mutableStateOf(true) }
    LaunchedEffect(value) {
        if (input.text != value) input = TextFieldValue(value)
    }
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it
            onValueChange(it.text)
        },
        label = { Text("服务地址") },
        placeholder = { Text(placeholder) },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
            if (focusState.isFocused && selectAllOnFocus) {
                input = input.copy(selection = TextRange(0, input.text.length))
                selectAllOnFocus = false
            } else if (!focusState.isFocused) {
                selectAllOnFocus = true
            }
        },
    )
}

@Composable
private fun ApiKeyField(
    value: String,
    hasSavedKey: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("API Key") },
        placeholder = { Text(if (hasSavedKey) "已保存，留空保持不变" else "填写 API Key") },
        supportingText = { if (hasSavedKey && value.isBlank()) Text("已有密钥，留空即可") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProtocolPicker(
    value: TranslationProtocol,
    onValueChange: (TranslationProtocol) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("服务协议：${value.label()}", modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            TranslationProtocol.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.label()) },
                    onClick = {
                        onValueChange(candidate)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionTestButton(
    state: ConnectionTestUiState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled && !state.running,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (state.running) "测试中…" else "测试连接")
        }
        state.message?.let {
            Text(
                text = it,
                color = if (state.success == true) AppSecondaryText else AppError,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModelPicker(
    value: String,
    options: List<String>,
    loading: Boolean,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("模型") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                when {
                    loading -> Text("正在自动查找模型…")
                    options.isNotEmpty() -> Text("已找到 ${options.size} 个可用模型")
                }
            },
        )
        if (options.isNotEmpty()) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            ) { Text("选择", color = AppAccent) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                label = {
                    Text(
                        label(option),
                        color = if (selected == option) AppPrimaryText else AppSecondaryText,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppSurfaceRaised,
                    containerColor = Color.Transparent,
                    selectedLabelColor = AppPrimaryText,
                    labelColor = AppSecondaryText,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
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
        TranslationProtocol.Responses -> "OpenAI Responses"
        TranslationProtocol.AnthropicMessages -> "Anthropic Messages"
        TranslationProtocol.ChatCompletions -> "OpenAI Chat Completions"
        TranslationProtocol.GoogleGenerateContent -> "Google Gemini"
    }

private fun TranslationProtocol.defaultEndpoint(): String =
    when (this) {
        TranslationProtocol.Responses -> "https://api.openai.com/v1/responses"
        TranslationProtocol.AnthropicMessages -> "https://api.anthropic.com/v1/messages"
        TranslationProtocol.ChatCompletions -> "https://api.openai.com/v1/chat/completions"
        TranslationProtocol.GoogleGenerateContent ->
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    }

private fun AsrProfile.displayLabel(): String =
    when (this) {
        AsrProfile.OpenRouterWhisperV1 -> "Whisper large-v3"
        AsrProfile.OpenRouterGrokV1 -> "Grok STT"
    }

private fun AsrProfile.displayModelLabel(): String =
    when (this) {
        AsrProfile.OpenRouterWhisperV1 -> "OpenAI Whisper large-v3"
        AsrProfile.OpenRouterGrokV1 -> "xAI Grok STT 1.0"
    }

private fun TranslationProtocol.defaultBaseUrl(): String =
    when (this) {
        TranslationProtocol.Responses,
        TranslationProtocol.ChatCompletions,
        -> "https://api.openai.com/v1"
        TranslationProtocol.AnthropicMessages -> "https://api.anthropic.com/v1"
        TranslationProtocol.GoogleGenerateContent -> "https://generativelanguage.googleapis.com/v1beta"
    }

private fun String.toBaseUrl(): String {
    val value = trim().removeSuffix("/")
    return when {
        value.endsWith("/responses") -> value.removeSuffix("/responses")
        value.endsWith("/messages") -> value.removeSuffix("/messages")
        value.endsWith("/chat/completions") -> value.removeSuffix("/chat/completions")
        "/models/" in value -> value.substringBefore("/models/")
        else -> value
    }
}

private const val DefaultAsrEndpoint = OpenRouterAsrEngine.OpenRouterBaseUrl
