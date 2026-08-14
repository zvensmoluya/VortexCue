package io.github.zvensmoluya.vortexcue.ui.synctext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.AsrProfile
import io.github.zvensmoluya.vortexcue.synctext.AudioTrackInfo
import io.github.zvensmoluya.vortexcue.synctext.CredentialMetadata
import io.github.zvensmoluya.vortexcue.synctext.CredentialPurpose
import io.github.zvensmoluya.vortexcue.synctext.GeneratedTextPresentation
import io.github.zvensmoluya.vortexcue.synctext.GenerationInput
import io.github.zvensmoluya.vortexcue.synctext.GenerationOutput
import io.github.zvensmoluya.vortexcue.synctext.GenerationRequest
import io.github.zvensmoluya.vortexcue.synctext.GenerationStage
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskState
import io.github.zvensmoluya.vortexcue.synctext.GenerationTaskSummary
import io.github.zvensmoluya.vortexcue.synctext.LanguageTags
import io.github.zvensmoluya.vortexcue.synctext.SyncTextScheme
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocol
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppBackground
import io.github.zvensmoluya.vortexcue.ui.theme.AppError
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import java.util.Locale
import kotlin.math.ceil

@Composable
fun SyncTextUiHost(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
) {
    when {
        state.resultDetails != null ->
            ResultDetailsDialog(
                presentation = state.resultDetails,
                onDismiss = { onAction(SyncTextAction.CloseResultDetails) },
            )
        state.schemeEditorVisible ->
            SchemeEditorDialog(
                state = state,
                onAction = onAction,
                onDismiss = { onAction(SyncTextAction.CloseSchemeEditor) },
            )
        state.generationContent != null ->
            GenerationDialog(
                content = state.generationContent,
                state = state,
                onAction = onAction,
                onDismiss = { onAction(SyncTextAction.CloseGeneration) },
            )
        state.taskDrawerVisible ->
            TaskDrawerDialog(
                state = state,
                onAction = onAction,
                onDismiss = { onAction(SyncTextAction.CloseTaskDrawer) },
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

private data class InputChoice(
    val label: String,
    val detail: String,
    val input: GenerationInput,
)

@Composable
private fun GenerationDialog(
    content: ContentDetails,
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices =
        remember(content, state.audioTracks) {
            inputChoices(content, state.audioTracks)
        }
    val initiallySelectedTrack = content.activeText.primaryTrackId
    var selectedInput by remember(content.contentId) {
        mutableStateOf<GenerationInput?>(
            choices.firstOrNull { it.input.trackId == initiallySelectedTrack }?.input,
        )
    }
    var selectedSchemeId by remember(content.contentId) {
        mutableStateOf(state.schemes.firstOrNull(SyncTextScheme::isDefault)?.schemeId ?: state.schemes.firstOrNull()?.schemeId)
    }
    var sourceOnly by remember(content.contentId) { mutableStateOf(false) }
    var sourceLanguage by remember(content.contentId) { mutableStateOf("") }
    var targetLanguage by remember(content.contentId) {
        mutableStateOf(state.schemes.firstOrNull { it.schemeId == selectedSchemeId }?.defaultTargetLanguage ?: "zh-CN")
    }
    var presentationMode by remember(content.contentId) {
        mutableStateOf(
            state.schemes.firstOrNull { it.schemeId == selectedSchemeId }?.defaultPresentationMode
                ?: TextPresentationMode.TargetOnly,
        )
    }
    var allowMetered by remember(content.contentId) { mutableStateOf(false) }
    val scheme = state.schemes.firstOrNull { it.schemeId == selectedSchemeId }
    val normalizedTarget = normalizedLanguageOrNull(targetLanguage)
    val normalizedSource = normalizedLanguageOrNull(sourceLanguage)

    LaunchedEffect(choices) {
        if (selectedInput != null && choices.any { it.input == selectedInput }) return@LaunchedEffect
        selectedInput =
            choices.firstOrNull { it.input.trackId == initiallySelectedTrack }?.input
                ?: choices.firstOrNull { it.input !is GenerationInput.AudioTrack }?.input
                ?: choices.filter { it.input is GenerationInput.AudioTrack }.singleOrNull()?.input
    }

    LaunchedEffect(state.schemes) {
        if (state.schemes.none { it.schemeId == selectedSchemeId }) {
            val replacement = state.schemes.firstOrNull(SyncTextScheme::isDefault) ?: state.schemes.firstOrNull()
            selectedSchemeId = replacement?.schemeId
            replacement?.let {
                targetLanguage = it.defaultTargetLanguage
                presentationMode = it.defaultPresentationMode
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成同步文字", color = AppPrimaryText) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(content.activeMedia.displayTitle, color = AppPrimaryText, fontWeight = FontWeight.Medium)
                    Text("输入", color = AppMutedText, fontSize = 11.sp)
                }
                items(choices, key = { it.label + it.input.toString() }) { choice ->
                    SelectionRow(
                        selected = selectedInput == choice.input,
                        title = choice.label,
                        detail = choice.detail,
                        onClick = { selectedInput = choice.input },
                    )
                }
                if (state.inspectingAudioTracks) {
                    item { Text("正在读取媒体音轨…", color = AppMutedText, fontSize = 11.sp) }
                }
                state.audioInspectionError?.let { error ->
                    item { Text(error, color = AppError, fontSize = 11.sp) }
                }
                item {
                    SectionDivider("方案")
                    if (state.schemes.isEmpty()) {
                        Text("还没有可用方案。先保存 ASR / 翻译凭据并建立方案。", color = AppError, fontSize = 12.sp)
                    } else {
                        state.schemes.forEach { candidate ->
                            SelectionRow(
                                selected = candidate.schemeId == selectedSchemeId,
                                title = candidate.name + if (candidate.isDefault) " · 默认" else "",
                                detail = "${candidate.translationProtocol.label()} · ${candidate.translationModel}",
                                onClick = {
                                    selectedSchemeId = candidate.schemeId
                                    targetLanguage = candidate.defaultTargetLanguage
                                    presentationMode = candidate.defaultPresentationMode
                                },
                            )
                        }
                    }
                    TextButton(onClick = { onAction(SyncTextAction.OpenSchemeEditor) }) {
                        Text("管理方案与凭据", color = AppAccent)
                    }
                }
                scheme?.let { selected ->
                    item {
                        Text("实际 Endpoint", color = AppMutedText, fontSize = 11.sp)
                        Text(selected.translationEndpoint, color = AppSecondaryText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("模型：${selected.translationModel}", color = AppSecondaryText, fontSize = 12.sp)
                        Text("ASR：${selected.asrProfile.modelId}${if (selected.asrProfile.experimental) " · 实验" else ""}", color = AppSecondaryText, fontSize = 12.sp)
                    }
                }
                item {
                    SectionDivider("输出")
                    ChoiceButtons(
                        options = listOf("翻译" to false, "仅原文" to true),
                        selected = sourceOnly,
                        label = { it.first },
                        value = { it.second },
                        onSelected = { sourceOnly = it },
                    )
                    LanguageTagField(
                        value = sourceLanguage,
                        onValueChange = { sourceLanguage = it },
                        label = "源语言 BCP-47",
                        allowBlank = true,
                    )
                    if (!sourceOnly) {
                        LanguageTagField(
                            value = targetLanguage,
                            onValueChange = { targetLanguage = it },
                            label = "目标语言 BCP-47",
                        )
                        ChoiceButtons(
                            options = TextPresentationMode.entries,
                            selected = presentationMode,
                            label = { if (it == TextPresentationMode.TargetOnly) "纯译文" else "译文 + 原文" },
                            value = { it },
                            onSelected = { presentationMode = it },
                        )
                    }
                }
                item {
                    SectionDivider("网络")
                    Text(
                        selectedInput?.let { networkEstimate(content, it, sourceOnly) }
                            ?: "请选择一个输入。多音轨媒体必须明确选择音轨。",
                        color = AppSecondaryText,
                        fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowMetered, onCheckedChange = { allowMetered = it })
                        Column {
                            Text("允许此任务使用计费网络", color = AppPrimaryText, fontSize = 13.sp)
                            Text("许可只保存到这一个任务。不会推测或展示虚构价格。", color = AppMutedText, fontSize = 10.sp)
                        }
                    }
                    Text("上游返回 usage / cost 时才会显示真实值。", color = AppMutedText, fontSize = 10.sp)
                }
                state.message?.let { message ->
                    item { Text(message, color = AppError, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                    !state.busy &&
                        scheme != null &&
                        selectedInput != null &&
                        (sourceOnly || normalizedTarget != null) &&
                        (sourceLanguage.isBlank() || normalizedSource != null),
                onClick = {
                    val output =
                        if (sourceOnly) {
                            GenerationOutput.SourceOnly
                        } else {
                            GenerationOutput.Translated(
                                targetLanguage = requireNotNull(normalizedTarget),
                                presentationMode = presentationMode,
                            )
                        }
                    onAction(
                        SyncTextAction.Submit(
                            GenerationRequest(
                                contentId = content.contentId,
                                input = requireNotNull(selectedInput),
                                output = output,
                                schemeId = requireNotNull(scheme).schemeId,
                                sourceLanguage = normalizedSource,
                                allowMeteredNetwork = allowMetered,
                            ),
                        ),
                    )
                },
            ) { Text(if (state.busy) "提交中" else "加入队列", color = AppAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = AppSecondaryText) } },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun TaskDrawerDialog(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("同步文字任务", color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAction(SyncTextAction.OpenSchemeEditor) }) { Text("方案", color = AppAccent) }
                TextButton(onClick = onDismiss) { Text("关闭", color = AppSecondaryText) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.pauseAll,
                    onCheckedChange = { onAction(SyncTextAction.SetPauseAll(it)) },
                    enabled = state.tasks.isNotEmpty() && !state.busy,
                )
                Text("暂停全部", color = AppPrimaryText)
            }
            Hairline()
            state.message?.let { Text(it, color = AppError, fontSize = 12.sp) }
            if (state.tasks.isEmpty()) {
                Text("当前没有进行中、等待中或需处理的任务。完成项会自动退出这里。", color = AppSecondaryText)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.tasks, key = GenerationTaskSummary::taskId) { task ->
                        TaskRow(task, state.schemes, state.busy, onAction)
                        Hairline()
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: GenerationTaskSummary,
    schemes: List<SyncTextScheme>,
    busy: Boolean,
    onAction: (SyncTextAction) -> Unit,
) {
    val schemeName = schemes.firstOrNull { it.schemeId == task.schemeId }?.name ?: "方案已删除"
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("队列 ${task.queuePosition + 1} · ${task.stage.label()}", color = AppPrimaryText, fontWeight = FontWeight.Medium)
                Text("$schemeName${task.targetLanguage?.let { " · $it" }.orEmpty()}", color = AppSecondaryText, fontSize = 12.sp)
            }
            Text(task.state.label(), color = if (task.needsAttention) AppError else AppMutedText, fontSize = 11.sp)
        }
        LinearProgressIndicator(
            progress = { task.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = AppAccent,
            trackColor = AppSurfaceRaised,
        )
        val flags =
            listOfNotNull(
                "等待网络".takeIf { task.waitingForNetwork },
                "同方案阻塞".takeIf { task.blockedByScheme },
                "已暂停".takeIf { task.paused },
            )
        if (flags.isNotEmpty()) Text(flags.joinToString(" · "), color = AppMutedText, fontSize = 11.sp)
        task.errorMessage?.let { Text(it, color = AppError, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (task.paused || task.state == GenerationTaskState.NeedsAttention) {
                TextButton(enabled = !busy, onClick = { onAction(SyncTextAction.ResumeTask(task.taskId)) }) {
                    Text("恢复", color = AppAccent)
                }
            } else {
                TextButton(enabled = !busy, onClick = { onAction(SyncTextAction.PauseTask(task.taskId)) }) {
                    Text("暂停", color = AppSecondaryText)
                }
            }
            if (task.state == GenerationTaskState.Waiting) {
                TextButton(enabled = !busy && task.queuePosition > 0, onClick = { onAction(SyncTextAction.MoveTask(task.taskId, task.queuePosition - 1)) }) {
                    Text("上移", color = AppSecondaryText)
                }
                TextButton(enabled = !busy, onClick = { onAction(SyncTextAction.MoveTask(task.taskId, task.queuePosition + 1)) }) {
                    Text("下移", color = AppSecondaryText)
                }
            }
            TextButton(enabled = !busy, onClick = { onAction(SyncTextAction.DeleteTask(task.taskId)) }) {
                Text("删除", color = AppError)
            }
        }
        if (task.needsAttention || task.blockedByScheme) {
            Text("换方案会创建新 attempt；已完成原文保留，翻译重新开始。", color = AppMutedText, fontSize = 10.sp)
            schemes.filterNot { it.schemeId == task.schemeId }.forEach { scheme ->
                TextButton(
                    enabled = !busy,
                    onClick = { onAction(SyncTextAction.ChangeTaskScheme(task.taskId, scheme.schemeId)) },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) { Text("改用 ${scheme.name}", color = AppAccent, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SchemeEditorDialog(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSchemeId by remember { mutableStateOf<String?>(state.schemes.firstOrNull()?.schemeId) }
    var editingCredentialId by remember { mutableStateOf<String?>(null) }
    var credentialFormVisible by remember { mutableStateOf(false) }
    val selectedScheme = state.schemes.firstOrNull { it.schemeId == selectedSchemeId }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("同步文字方案", color = AppPrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("完成", color = AppAccent) }
            }
            state.message?.let { Text(it, color = if (it.endsWith("已保存") || it.endsWith("已删除")) AppSecondaryText else AppError, fontSize = 12.sp) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SectionDivider("方案")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selectedSchemeId = null }) { Text("新建", color = AppAccent) }
                        state.schemes.forEach { scheme ->
                            TextButton(onClick = { selectedSchemeId = scheme.schemeId }) {
                                Text(scheme.name, color = if (scheme.schemeId == selectedSchemeId) AppAccent else AppSecondaryText)
                            }
                        }
                    }
                }
                item {
                    key(selectedScheme?.schemeId ?: "new-scheme", state.credentials) {
                        SchemeForm(
                            existing = selectedScheme,
                            credentials = state.credentials,
                            busy = state.busy,
                            onSave = { onAction(SyncTextAction.SaveScheme(selectedScheme?.schemeId, it)) },
                            onDelete = selectedScheme?.let { { onAction(SyncTextAction.DeleteScheme(it.schemeId)); selectedSchemeId = null } },
                        )
                    }
                }
                item {
                    SectionDivider("凭据")
                    Text("密钥由 Android Keystore 主密钥加密；页面只显示掩码。更新同一凭据会修复引用它的旧任务。", color = AppMutedText, fontSize = 10.sp)
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
                    TextButton(onClick = { editingCredentialId = null; credentialFormVisible = true }) {
                        Text("新增凭据", color = AppAccent)
                    }
                }
                if (credentialFormVisible) {
                    item {
                        key(editingCredentialId ?: "new-credential") {
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
}

@Composable
private fun SchemeForm(
    existing: SyncTextScheme?,
    credentials: List<CredentialMetadata>,
    busy: Boolean,
    onSave: (SyncTextSchemeDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var asrProfile by remember { mutableStateOf(existing?.asrProfile ?: AsrProfile.OpenRouterWhisperV1) }
    var asrCredentialId by remember { mutableStateOf(existing?.asrCredentialId ?: credentials.firstEligible(CredentialPurpose.Asr)?.credentialId) }
    var protocol by remember { mutableStateOf(existing?.translationProtocol ?: TranslationProtocol.Responses) }
    var endpoint by remember { mutableStateOf(existing?.translationEndpoint ?: protocol.defaultEndpoint()) }
    var model by remember { mutableStateOf(existing?.translationModel.orEmpty()) }
    var translationCredentialId by remember { mutableStateOf(existing?.translationCredentialId ?: credentials.firstEligible(CredentialPurpose.Translation)?.credentialId) }
    var targetLanguage by remember { mutableStateOf(existing?.defaultTargetLanguage ?: "zh-CN") }
    var presentationMode by remember { mutableStateOf(existing?.defaultPresentationMode ?: TextPresentationMode.TargetOnly) }
    var isDefault by remember { mutableStateOf(existing?.isDefault ?: false) }
    val normalizedTarget = normalizedLanguageOrNull(targetLanguage)
    val asrCredentials = credentials.filter { it.purpose == CredentialPurpose.Asr || it.purpose == CredentialPurpose.Shared }
    val translationCredentials = credentials.filter { it.purpose == CredentialPurpose.Translation || it.purpose == CredentialPurpose.Shared }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("方案名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("ASR profile", color = AppMutedText, fontSize = 11.sp)
        ChoiceButtons(
            options = AsrProfile.entries,
            selected = asrProfile,
            label = { if (it.experimental) "Grok（实验）" else "Whisper" },
            value = { it },
            onSelected = { asrProfile = it },
        )
        CredentialChoices("ASR 凭据", asrCredentials, asrCredentialId) { asrCredentialId = it }
        Text("翻译协议", color = AppMutedText, fontSize = 11.sp)
        TranslationProtocol.entries.forEach { candidate ->
            SelectionRow(
                selected = protocol == candidate,
                title = candidate.label(),
                detail = candidate.defaultEndpoint(),
                onClick = {
                    val wasTemplate = endpoint == protocol.defaultEndpoint()
                    protocol = candidate
                    if (wasTemplate) endpoint = candidate.defaultEndpoint()
                },
            )
        }
        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("HTTPS Endpoint / 模板") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model（手填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        CredentialChoices("翻译凭据", translationCredentials, translationCredentialId) { translationCredentialId = it }
        LanguageTagField(
            value = targetLanguage,
            onValueChange = { targetLanguage = it },
            label = "默认目标语言 BCP-47",
        )
        ChoiceButtons(
            options = TextPresentationMode.entries,
            selected = presentationMode,
            label = { if (it == TextPresentationMode.TargetOnly) "纯译文" else "译文 + 原文" },
            value = { it },
            onSelected = { presentationMode = it },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
            Text("设为默认方案", color = AppPrimaryText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                enabled =
                    !busy && name.isNotBlank() && asrCredentialId != null &&
                        translationCredentialId != null && endpoint.isNotBlank() &&
                        model.isNotBlank() && normalizedTarget != null,
                onClick = {
                    onSave(
                        SyncTextSchemeDraft(
                            name = name,
                            asrProfile = asrProfile,
                            asrCredentialId = requireNotNull(asrCredentialId),
                            translationProtocol = protocol,
                            translationEndpoint = endpoint,
                            translationModel = model,
                            translationCredentialId = requireNotNull(translationCredentialId),
                            defaultTargetLanguage = requireNotNull(normalizedTarget),
                            defaultPresentationMode = presentationMode,
                            isDefault = isDefault,
                        ),
                    )
                },
            ) { Text("保存方案") }
            onDelete?.let { delete ->
                TextButton(enabled = !busy, onClick = delete) { Text("删除", color = AppError) }
            }
        }
    }
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
        Text("没有用途匹配的凭据", color = AppError, fontSize = 11.sp)
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
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var purpose by remember { mutableStateOf(existing?.purpose ?: CredentialPurpose.Shared) }
    var secret by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (existing == null) "新增凭据" else "更新凭据", color = AppPrimaryText, fontWeight = FontWeight.Medium)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChoiceButtons(
            options = CredentialPurpose.entries,
            selected = purpose,
            label = CredentialPurpose::label,
            value = { it },
            onSelected = { purpose = it },
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(if (existing == null) "API key" else "新的 API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            OutlinedButton(enabled = !busy && name.isNotBlank() && secret.isNotBlank(), onClick = { onSave(existing?.credentialId, name, purpose, secret) }) {
                Text("保存")
            }
            TextButton(onClick = onCancel) { Text("取消", color = AppSecondaryText) }
        }
    }
}

@Composable
private fun ResultDetailsDialog(
    presentation: GeneratedTextPresentation,
    onDismiss: () -> Unit,
) {
    val result = presentation.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成结果详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                DetailLine("语言", listOfNotNull(result.sourceLanguage ?: "自动检测", result.targetLanguage).joinToString(" → "))
                DetailLine("Cue", result.cueCount.toString())
                DetailLine("模型", result.model ?: "未记录")
                DetailLine("Policy", result.policyId?.let { "$it v${result.policyVersion}" } ?: "无")
                result.policyRevision?.let { DetailLine("Policy revision", it) }
                result.systemPromptSha256?.let { DetailLine("System prompt SHA-256", it) }
                result.stylePromptSha256?.let { DetailLine("Style prompt SHA-256", it) }
                DetailLine("方案", result.schemeId)
                result.attemptId?.let { DetailLine("Attempt", it) }
                DetailLine("输入 token", result.usage.inputTokens?.toString() ?: "上游未返回")
                DetailLine("输出 token", result.usage.outputTokens?.toString() ?: "上游未返回")
                DetailLine("总 token", result.usage.totalTokens?.toString() ?: "上游未返回")
                DetailLine("费用", result.usage.costUsd?.let { "USD $it" } ?: "上游未返回")
                DetailLine("Source SHA-256", result.sourceSha256)
                result.targetSha256?.let { DetailLine("Target SHA-256", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = AppAccent) } },
        containerColor = AppSurfaceRaised,
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text(label, color = AppMutedText, fontSize = 10.sp)
    Text(value, color = AppPrimaryText, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
            Text(title, color = AppPrimaryText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = AppMutedText, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun <T, V> ChoiceButtons(
    options: List<T>,
    selected: V,
    label: (T) -> String,
    value: (T) -> V,
    onSelected: (V) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            OutlinedButton(
                onClick = { onSelected(value(option)) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(label(option), color = if (selected == value(option)) AppAccent else AppSecondaryText, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun SectionDivider(label: String) {
    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppHairline))
    Text(label, color = AppMutedText, fontSize = 11.sp)
}

@Composable
private fun Hairline() {
    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppHairline))
}

private data class LanguageOption(
    val tag: String,
    val name: String,
)

@Composable
private fun LanguageTagField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    allowBlank: Boolean = false,
) {
    val normalized = normalizedLanguageOrNull(value)
    val query = value.trim().lowercase(Locale.ROOT)
    val suggestions =
        remember(query) {
            CommonLanguageOptions.filter { option ->
                query.isBlank() ||
                    option.tag.lowercase(Locale.ROOT).contains(query) ||
                    option.name.lowercase(Locale.ROOT).contains(query)
            }.take(2)
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            supportingText = {
                Text(
                    when {
                        allowBlank && value.isBlank() -> "自动检测"
                        normalized != null -> "将使用 $normalized"
                        else -> "请输入有效语言 tag"
                    },
                )
            },
            isError = !(allowBlank && value.isBlank()) && normalized == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestions.forEach { option ->
                    TextButton(
                        onClick = { onValueChange(option.tag) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "${option.name} · ${option.tag}",
                            color = if (normalized == option.tag) AppAccent else AppSecondaryText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun inputChoices(
    content: ContentDetails,
    audioTracks: List<AudioTrackInfo>,
): List<InputChoice> =
    buildList {
        content.timedTextTracks
            .filter { it.origin != TimedTextOrigin.Embedded }
            .forEach { track ->
                add(
                    InputChoice(
                        label = track.displayLabel(),
                        detail = if (track.origin == TimedTextOrigin.Generated) "已生成原文 / 结果" else "现有文字轨，不上传媒体",
                        input = GenerationInput.ExistingTrack(track.stableId),
                    ),
                )
            }
        content.timedTextTracks
            .filter { it.origin == TimedTextOrigin.Embedded }
            .forEach { track ->
                add(
                    InputChoice(
                        label = track.displayLabel(),
                        detail = "内嵌文字轨；格式无法物化时任务会明确提供 ASR 路径",
                        input = GenerationInput.EmbeddedTrack(track.stableId, requireNotNull(track.locator.embeddedTrackId)),
                    ),
                )
            }
        audioTracks.forEachIndexed { index, track ->
            val identity =
                track.label?.takeIf(String::isNotBlank)
                    ?: track.language?.takeIf(String::isNotBlank)
                    ?: "音轨 ${index + 1}"
            val format =
                listOfNotNull(
                    track.mimeType,
                    track.channelCount?.let { "$it 声道" },
                    track.sampleRate?.let { "$it Hz" },
                ).joinToString(" · ")
            add(
                InputChoice(
                    label = "媒体音频 · $identity",
                    detail = "$format；使用 OpenRouter ASR",
                    input = GenerationInput.AudioTrack(track.selectionId),
                ),
            )
        }
    }

private fun TimedTextTrack.displayLabel(): String =
    label?.takeIf(String::isNotBlank)
        ?: language?.takeIf(String::isNotBlank)
        ?: locator.displayName

private fun networkEstimate(
    content: ContentDetails,
    input: GenerationInput,
    sourceOnly: Boolean,
): String {
    val translationSuffix = if (sourceOnly) "不会发送翻译请求。" else "翻译正文会在 cue 物化后按批次计算。"
    if (input !is GenerationInput.AudioTrack) {
        return "预计媒体上传 0 B；$translationSuffix"
    }
    val durationMs = content.activeMedia.durationMs
    if (durationMs <= 0L) return "音频线上字节将在解码器读出时长后确定；$translationSuffix"
    val seconds = durationMs / 1_000.0
    val windowCount = ceil(seconds / 300.0).toLong().coerceAtLeast(1L)
    val overlapSeconds = (windowCount - 1L) * 6L
    val pcmBytes = ((seconds + overlapSeconds) * 16_000.0 * 2.0).toLong() + windowCount * 44L
    val base64Bytes = ((pcmBytes + 2L) / 3L) * 4L
    return "预计 ASR 上传约 ${formatBytes(base64Bytes)}（16 kHz mono PCM16 + base64，含窗口 overlap）；$translationSuffix"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.ROOT, "%.1f KiB", kib)
    return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0)
}

private fun normalizedLanguageOrNull(value: String): String? =
    value.takeIf(String::isNotBlank)?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() }

private val CommonLanguageOptions =
    listOf(
        LanguageOption("zh-CN", "简体中文"),
        LanguageOption("zh-Hant", "繁体中文"),
        LanguageOption("en", "English"),
        LanguageOption("ja", "日本語"),
        LanguageOption("ko", "한국어"),
        LanguageOption("es", "Español"),
        LanguageOption("fr", "Français"),
        LanguageOption("de", "Deutsch"),
        LanguageOption("pt-BR", "Português"),
        LanguageOption("ru", "Русский"),
    )

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

private fun List<CredentialMetadata>.firstEligible(purpose: CredentialPurpose): CredentialMetadata? =
    firstOrNull { it.purpose == purpose || it.purpose == CredentialPurpose.Shared }

private fun GenerationStage.label(): String =
    when (this) {
        GenerationStage.Queued -> "排队"
        GenerationStage.MaterializingText -> "物化文字"
        GenerationStage.DecodingAudio -> "解码音频"
        GenerationStage.RecognizingSpeech -> "语音识别"
        GenerationStage.SourcePublished -> "原文已发布"
        GenerationStage.Translating -> "翻译"
        GenerationStage.Publishing -> "发布结果"
        GenerationStage.Completed -> "完成"
    }

private fun GenerationTaskState.label(): String =
    when (this) {
        GenerationTaskState.Waiting -> "等待"
        GenerationTaskState.Running -> "运行中"
        GenerationTaskState.Paused -> "已暂停"
        GenerationTaskState.NeedsAttention -> "需处理"
        GenerationTaskState.Completed -> "已完成"
        GenerationTaskState.Deleted -> "已删除"
    }
