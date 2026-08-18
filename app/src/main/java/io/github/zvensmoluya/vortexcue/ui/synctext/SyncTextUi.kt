package io.github.zvensmoluya.vortexcue.ui.synctext

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceSelected
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.launch

private val ConfigControlHeight = 58.dp
private val ConfigControlShape = RoundedCornerShape(8.dp)

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

private data class TranslationPageDraft(
    val protocol: TranslationProtocol,
    val endpoint: String,
    val model: String,
    val credentialId: String?,
    val secret: String,
)

private data class AsrPageDraft(
    val profile: AsrProfile,
    val endpoint: String,
    val credentialId: String?,
    val secret: String,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SyncTextConfigPage(
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
) {
    val existing = state.config
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    var selectedPage by rememberSaveable { mutableStateOf(0) }
    val translationListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val asrListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var translationDraft by remember(existing?.configId) {
        mutableStateOf(
            TranslationPageDraft(
                protocol = existing?.translationProtocol ?: TranslationProtocol.Responses,
                endpoint = existing?.translationEndpoint?.toBaseUrl()
                    ?: TranslationProtocol.Responses.defaultBaseUrl(),
                model = existing?.translationModel.orEmpty(),
                credentialId = existing?.translationCredentialId,
                secret = "",
            ),
        )
    }
    var asrDraft by remember(existing?.configId) {
        mutableStateOf(
            AsrPageDraft(
                profile = existing?.asrProfile ?: AsrProfile.OpenRouterWhisperV1,
                endpoint = existing?.asrEndpoint ?: DefaultAsrEndpoint,
                credentialId = existing?.asrCredentialId,
                secret = "",
            ),
        )
    }
    val asrCredentials = credentialsFor(state.credentials, CredentialPurpose.Asr)
    val translationCredentials = credentialsFor(state.credentials, CredentialPurpose.Translation)
    var asrCredentialInitialized by remember(existing?.configId) { mutableStateOf(false) }
    var translationCredentialInitialized by remember(existing?.configId) { mutableStateOf(false) }
    LaunchedEffect(asrCredentials) {
        if (asrCredentials.isEmpty()) return@LaunchedEffect
        if (!asrCredentialInitialized) {
            asrCredentialInitialized = true
            if (asrDraft.credentialId == null) {
                asrDraft = asrDraft.copy(credentialId = asrCredentials.first().credentialId)
            }
        } else if (asrDraft.credentialId != null &&
            asrDraft.credentialId !in asrCredentials.map { it.credentialId }
        ) {
            asrDraft = asrDraft.copy(credentialId = null)
        }
    }
    LaunchedEffect(translationCredentials) {
        if (translationCredentials.isEmpty()) return@LaunchedEffect
        if (!translationCredentialInitialized) {
            translationCredentialInitialized = true
            if (translationDraft.credentialId == null) {
                translationDraft = translationDraft.copy(credentialId = translationCredentials.first().credentialId)
            }
        } else if (translationDraft.credentialId != null &&
            translationDraft.credentialId !in translationCredentials.map { it.credentialId }
        ) {
            translationDraft = translationDraft.copy(credentialId = null)
        }
    }
    val asrSavedCredential = state.credentials.firstOrNull { it.credentialId == asrDraft.credentialId }
    val translationSavedCredential = state.credentials.firstOrNull { it.credentialId == translationDraft.credentialId }
    val focusManager = LocalFocusManager.current
    fun fullDraft(
        translation: TranslationPageDraft = translationDraft,
        asr: AsrPageDraft = asrDraft,
    ): SyncTextConfigDraft =
        SyncTextConfigDraft(
            asrProfile = asr.profile,
            asrEndpoint = asr.endpoint,
            asrCredentialId = asr.credentialId,
            // Secrets are persisted only through SaveCredential. A page change must never
            // turn an uncommitted replacement into an implicit credential update.
            asrSecret = null,
            translationProtocol = translation.protocol,
            translationEndpoint = translation.endpoint,
            translationUserAgent = existing?.translationUserAgent,
            translationModel = translation.model,
            translationCredentialId = translation.credentialId,
            translationSecret = null,
            targetLanguage = existing?.targetLanguage ?: "zh-CN",
            presentationMode = existing?.presentationMode ?: TextPresentationMode.TargetOnly,
        )
    fun commitDraft(
        translation: TranslationPageDraft = translationDraft,
        asr: AsrPageDraft = asrDraft,
    ) {
        val draft = fullDraft(translation, asr)
        if (!draft.isComplete()) return
        onAction(SyncTextAction.SaveConfig(existing?.configId, draft))
    }
    fun saveCredential(
        purpose: CredentialPurpose,
        credentialId: String?,
        secret: String,
    ) {
        onAction(
            SyncTextAction.SaveCredential(
                credentialId = credentialId,
                name = if (purpose == CredentialPurpose.Asr) "OpenRouter" else "翻译服务",
                purpose = purpose,
                secret = secret,
            ),
        )
    }
    fun discoverModels(translation: TranslationPageDraft = translationDraft) {
        if (isHttpsEndpoint(translation.endpoint) &&
            (translation.credentialId != null || translation.secret.isNotBlank())
        ) {
            onAction(
                SyncTextAction.DiscoverTranslationModels(
                    translation.protocol,
                    translation.endpoint,
                    translation.credentialId,
                    translation.secret.takeIf(String::isNotBlank),
                ),
            )
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            selectedPage = page
            focusManager.clearFocus(force = true)
            commitDraft()
        }
    }
    LaunchedEffect(state.credentialSave?.requestId) {
        val result = state.credentialSave ?: return@LaunchedEffect
        if (!result.success || result.credentialId == null) return@LaunchedEffect
        when (result.purpose) {
            CredentialPurpose.Asr -> {
                val next = asrDraft.copy(credentialId = result.credentialId, secret = "")
                asrDraft = next
                commitDraft(asr = next)
            }
            CredentialPurpose.Translation -> {
                val next = translationDraft.copy(credentialId = result.credentialId, secret = "")
                translationDraft = next
                commitDraft(translation = next)
            }
            CredentialPurpose.Shared -> Unit
        }
    }
    DisposableEffect(Unit) {
        onDispose { commitDraft() }
    }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        PagerTabs(
            selectedPage = selectedPage,
            onSelect = { page ->
                selectedPage = page
                focusManager.clearFocus(force = true)
                commitDraft()
                scope.launch {
                    pagerState.animateScrollToPage(page)
                }
            },
        )
        if (state.configSaving) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = AppAccent,
                trackColor = Color.Transparent,
            )
        }
        state.configError?.let { error ->
            Text(
                error,
                color = AppError,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = 0.35f,
            ),
        ) { page ->
            when (page) {
                0 ->
                    TranslationPage(
                        draft = translationDraft,
                        availableModels = state.availableTranslationModels,
                        loadingModels = state.loadingTranslationModels,
                        connectionTest = state.translationConnectionTest,
                        busy = state.busy,
                        hasSavedKey = translationSavedCredential != null,
                        credentialSaveError = state.credentialSave
                            ?.takeIf { it.purpose == CredentialPurpose.Translation && !it.success }
                            ?.message,
                        saveCompletedRequestId = state.credentialSave
                            ?.takeIf { it.purpose == CredentialPurpose.Translation && it.success }
                            ?.requestId,
                        listState = translationListState,
                        onDraftChanged = { translationDraft = it },
                        onCommit = { next -> commitDraft(translation = next) },
                        onDiscoverModels = { next -> discoverModels(next) },
                        onSaveCredential = { value ->
                            val next = translationDraft.copy(secret = value)
                            translationDraft = next
                            saveCredential(CredentialPurpose.Translation, next.credentialId, value)
                        },
                        onTest = { fingerprint ->
                            onAction(
                                SyncTextAction.TestTranslationConnection(
                                    translationDraft.protocol,
                                    translationDraft.endpoint,
                                    translationDraft.model,
                                    translationDraft.credentialId,
                                    translationDraft.secret.takeIf(String::isNotBlank),
                                    fingerprint,
                                ),
                            )
                        },
                    )
                else ->
                    AsrPage(
                        draft = asrDraft,
                        connectionTest = state.asrConnectionTest,
                        busy = state.busy,
                        hasSavedKey = asrSavedCredential != null,
                        credentialSaveError = state.credentialSave
                            ?.takeIf { it.purpose == CredentialPurpose.Asr && !it.success }
                            ?.message,
                        saveCompletedRequestId = state.credentialSave
                            ?.takeIf { it.purpose == CredentialPurpose.Asr && it.success }
                            ?.requestId,
                        listState = asrListState,
                        onDraftChanged = { asrDraft = it },
                        onCommit = { next -> commitDraft(asr = next) },
                        onSaveCredential = { value ->
                            val next = asrDraft.copy(secret = value)
                            asrDraft = next
                            saveCredential(CredentialPurpose.Asr, next.credentialId, value)
                        },
                        onTest = { fingerprint ->
                            onAction(
                                SyncTextAction.TestAsrConnection(
                                    asrDraft.profile,
                                    asrDraft.endpoint,
                                    asrDraft.credentialId,
                                    asrDraft.secret.takeIf(String::isNotBlank),
                                    fingerprint,
                                ),
                            )
                        },
                    )
            }
        }
    }
}

@Composable
private fun PagerTabs(
    selectedPage: Int,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().background(Color.Black),
    ) {
        val tabWidth = maxWidth / 2
        val indicatorInset = (tabWidth - 36.dp) / 2
        val indicatorOffset by animateDpAsState(
            targetValue = indicatorInset + tabWidth * selectedPage,
            animationSpec = tween(220),
            label = "config-tab-indicator",
        )
        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("翻译", "语音识别").forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable {
                            onSelect(index)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (selectedPage == index) AppPrimaryText else AppSecondaryText,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(36.dp)
                        .height(3.dp)
                        .background(AppAccent, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun TranslationPage(
    draft: TranslationPageDraft,
    availableModels: List<String>,
    loadingModels: Boolean,
    connectionTest: ConnectionTestUiState,
    busy: Boolean,
    hasSavedKey: Boolean,
    credentialSaveError: String?,
    saveCompletedRequestId: Long?,
    listState: LazyListState,
    onDraftChanged: (TranslationPageDraft) -> Unit,
    onCommit: (TranslationPageDraft) -> Unit,
    onDiscoverModels: (TranslationPageDraft) -> Unit,
    onSaveCredential: (String) -> Unit,
    onTest: (String) -> Unit,
) {
    val fingerprint = draft.translationFingerprint()
    val stale = connectionTest.testedFingerprint != null && connectionTest.testedFingerprint != fingerprint
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("sync-text-translation-page"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            ProtocolPicker(
                value = draft.protocol,
                onValueChange = { candidate ->
                    val wasDefault = draft.endpoint == draft.protocol.defaultBaseUrl() || draft.endpoint == draft.protocol.defaultEndpoint()
                    val next = draft.copy(
                        protocol = candidate,
                        endpoint = if (wasDefault) candidate.defaultBaseUrl() else draft.endpoint,
                    )
                    onDraftChanged(next)
                    onCommit(next)
                    onDiscoverModels(next)
                },
            )
        }
        item {
            BaseUrlField(
                value = draft.endpoint,
                placeholder = draft.protocol.defaultBaseUrl(),
                onValueChange = { onDraftChanged(draft.copy(endpoint = it)) },
                onCommit = { value ->
                    val next = draft.copy(endpoint = value)
                    onCommit(next)
                    onDiscoverModels(next)
                },
            )
        }
        item {
            ModelPicker(
                value = draft.model,
                options = availableModels,
                loading = loadingModels,
                onOpen = { onDiscoverModels(draft) },
                onValueChange = {
                    val next = draft.copy(model = it)
                    onDraftChanged(next)
                    onCommit(next)
                },
            )
        }
        item {
            ApiKeyField(
                value = draft.secret,
                hasSavedKey = hasSavedKey,
                saveError = credentialSaveError,
                saveCompletedRequestId = saveCompletedRequestId,
                onValueChange = { onDraftChanged(draft.copy(secret = it)) },
                onSave = onSaveCredential,
            )
        }
        item {
            ConnectionSection(
                modifier = Modifier.padding(top = 6.dp),
            ) {
                ConnectionStatusRow(
                    state = connectionTest,
                    stale = stale,
                    enabled = !busy && isHttpsEndpoint(draft.endpoint) && draft.model.isNotBlank() &&
                        (draft.credentialId != null || draft.secret.isNotBlank()),
                    onClick = { onTest(fingerprint) },
                )
            }
        }
    }
}

@Composable
private fun AsrPage(
    draft: AsrPageDraft,
    connectionTest: ConnectionTestUiState,
    busy: Boolean,
    hasSavedKey: Boolean,
    credentialSaveError: String?,
    saveCompletedRequestId: Long?,
    listState: LazyListState,
    onDraftChanged: (AsrPageDraft) -> Unit,
    onCommit: (AsrPageDraft) -> Unit,
    onSaveCredential: (String) -> Unit,
    onTest: (String) -> Unit,
) {
    val fingerprint = draft.asrFingerprint()
    val stale = connectionTest.testedFingerprint != null && connectionTest.testedFingerprint != fingerprint
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("sync-text-asr-page"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            AsrModelPicker(
                value = draft.profile,
                onValueChange = {
                    val next = draft.copy(profile = it)
                    onDraftChanged(next)
                    onCommit(next)
                },
            )
        }
        item {
            BaseUrlField(
                value = draft.endpoint,
                placeholder = DefaultAsrEndpoint,
                onValueChange = { onDraftChanged(draft.copy(endpoint = it)) },
                onCommit = { value -> onCommit(draft.copy(endpoint = value)) },
            )
        }
        item {
            ApiKeyField(
                value = draft.secret,
                hasSavedKey = hasSavedKey,
                saveError = credentialSaveError,
                saveCompletedRequestId = saveCompletedRequestId,
                onValueChange = { onDraftChanged(draft.copy(secret = it)) },
                onSave = onSaveCredential,
            )
        }
        item {
            ConnectionSection(
                modifier = Modifier.padding(top = 6.dp),
            ) {
                ConnectionStatusRow(
                    state = connectionTest,
                    stale = stale,
                    enabled = !busy && isHttpsEndpoint(draft.endpoint) &&
                        (draft.credentialId != null || draft.secret.isNotBlank()),
                    onClick = { onTest(fingerprint) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseUrlField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue(value)) }
    var wasFocused by remember { mutableStateOf(false) }
    var selectAllOnFocus by remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value) {
        if (!wasFocused && input.text != value) input = TextFieldValue(value)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigFieldLabel("服务地址")
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                onValueChange(it.text)
            },
            placeholder = { Text(placeholder, color = AppMutedText) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onCommit(input.text)
                focusManager.clearFocus()
            }),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AppSurfaceRaised,
                unfocusedContainerColor = AppSurfaceRaised,
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppHairline,
                errorContainerColor = AppSurfaceRaised,
            ),
            shape = ConfigControlShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = ConfigControlHeight).onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    wasFocused = true
                    if (selectAllOnFocus) {
                        input = input.copy(selection = TextRange(0, input.text.length))
                        selectAllOnFocus = false
                    }
                } else if (wasFocused) {
                    wasFocused = false
                    selectAllOnFocus = true
                    onCommit(input.text)
                }
            },
        )
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    hasSavedKey: Boolean,
    saveError: String?,
    saveCompletedRequestId: Long?,
    onValueChange: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    var editing by rememberSaveable(hasSavedKey) { mutableStateOf(!hasSavedKey) }
    var localError by rememberSaveable(hasSavedKey, saveError) { mutableStateOf<String?>(null) }
    var externalErrorVisible by remember(saveError) { mutableStateOf(true) }
    val error = localError ?: saveError.takeIf { externalErrorVisible }

    LaunchedEffect(saveCompletedRequestId) {
        if (saveCompletedRequestId != null) {
            editing = false
            localError = null
            externalErrorVisible = false
        }
    }

    fun save() {
        val normalized = value.trim()
        when {
            normalized.isBlank() -> localError = "请输入 API Key"
            !normalized.all { it.code in 0x21..0x7E } -> localError = "请输入完整的 API Key"
            else -> {
                localError = null
                externalErrorVisible = false
                onSave(normalized)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigFieldLabel("API Key")
        if (hasSavedKey && !editing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ConfigControlHeight)
                    .background(AppSurfaceRaised, ConfigControlShape)
                    .border(1.dp, AppHairline, ConfigControlShape)
                    .padding(start = 16.dp, end = 8.dp)
                    .testTag("sync-text-api-key-field")
                    .semantics { contentDescription = "API Key 已配置" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已配置",
                    color = AppPrimaryText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = {
                        editing = true
                        localError = null
                        externalErrorVisible = false
                        onValueChange("")
                    },
                ) { Text("更换", color = AppAccent) }
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    localError = null
                    externalErrorVisible = false
                    onValueChange(it)
                },
                placeholder = { Text("API Key", color = AppMutedText) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasSavedKey) {
                            TextButton(
                                onClick = {
                                    editing = false
                                    localError = null
                                    externalErrorVisible = false
                                    onValueChange("")
                                },
                            ) { Text("取消", color = AppSecondaryText) }
                        }
                        TextButton(
                            enabled = value.isNotBlank(),
                            onClick = ::save,
                        ) {
                            Text(
                                "保存",
                                color = if (value.isNotBlank()) AppAccent else AppSecondaryText,
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AppSurfaceRaised,
                    unfocusedContainerColor = AppSurfaceRaised,
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppHairline,
                    errorContainerColor = AppSurfaceRaised,
                ),
                shape = ConfigControlShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ConfigControlHeight)
                    .testTag("sync-text-api-key-field"),
            )
        }
        error?.let { Text(it, color = AppError, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolPicker(
    value: TranslationProtocol,
    onValueChange: (TranslationProtocol) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    ConfigChoiceRow(label = "服务协议", value = value.label(), onClick = { visible = true })
    if (visible) {
        ModalBottomSheet(
            onDismissRequest = { visible = false },
            containerColor = AppSurfaceRaised,
        ) {
            SheetTitle("服务协议")
            TranslationProtocol.entries.forEach { candidate ->
                SheetChoiceRow(
                    title = candidate.label(),
                    selected = candidate == value,
                    onClick = {
                        onValueChange(candidate)
                        visible = false
                    },
                )
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    value: String,
    options: List<String>,
    loading: Boolean,
    onOpen: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var manualValue by remember(value) { mutableStateOf(value) }
    Column {
        ConfigChoiceRow(label = "模型", value = value.ifBlank { "选择模型" }, onClick = {
            onOpen()
            manualEntry = false
            manualValue = value
            visible = true
        })
    }
    if (visible) {
        ModalBottomSheet(
            onDismissRequest = { visible = false },
            containerColor = AppSurfaceRaised,
        ) {
            SheetTitle("模型")
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AppAccent)
            options.forEach { option ->
                SheetChoiceRow(
                    title = option,
                    selected = option == value,
                    onClick = {
                        onValueChange(option)
                        visible = false
                    },
                )
            }
            TextButton(onClick = { manualEntry = true; manualValue = value }) {
                Text("手动输入模型 ID", color = AppAccent)
            }
            if (manualEntry) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("模型 ID", color = AppSecondaryText, fontSize = 12.sp)
                    OutlinedTextField(
                        value = manualValue,
                        onValueChange = { manualValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            manualValue.trim().takeIf(String::isNotBlank)?.let(onValueChange)
                            visible = false
                        }),
                    )
                    TextButton(
                        enabled = manualValue.isNotBlank(),
                        onClick = {
                            onValueChange(manualValue.trim())
                            visible = false
                        },
                    ) { Text("完成", color = AppAccent) }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ConfigChoiceRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigFieldLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConfigControlHeight)
                .background(AppSurfaceRaised, ConfigControlShape)
                .border(1.dp, AppHairline, ConfigControlShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, color = AppPrimaryText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("›", color = AppSecondaryText, fontSize = 22.sp)
        }
    }
}

@Composable
private fun ConfigFieldLabel(label: String) {
    Text(label, color = AppSecondaryText, fontSize = 12.sp)
}

@Composable
private fun AsrModelPicker(
    value: AsrProfile,
    onValueChange: (AsrProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigFieldLabel("识别模型")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConfigControlHeight)
                .background(AppSurfaceRaised, ConfigControlShape)
                .border(1.dp, AppHairline, ConfigControlShape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AsrProfile.entries.forEach { profile ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(ConfigControlHeight - 8.dp)
                        .background(
                            if (profile == value) AppSurfaceSelected else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onValueChange(profile) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        profile.displayLabel(),
                        color = if (profile == value) AppPrimaryText else AppSecondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String) {
    Text(title, color = AppPrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun SheetChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = AppPrimaryText, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Outlined.Check, contentDescription = "已选择", tint = AppAccent)
    }
}

@Composable
private fun ConnectionStatusRow(
    state: ConnectionTestUiState,
    stale: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val failure = state.success == false && !stale && !state.running
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                state.running -> "正在测试…"
                stale -> "需要测试"
                state.success == true -> "服务可用"
                failure -> "连接失败"
                else -> "未测试"
            },
            color = if (failure) AppError else AppPrimaryText,
            modifier = Modifier.weight(1f),
        )
        when {
            state.running -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AppAccent,
            )
            else -> TextButton(enabled = enabled, onClick = onClick) {
                Text(
                    when {
                        state.success == true -> "重新测试"
                        failure -> "重试"
                        else -> "测试连接"
                    },
                    color = if (enabled) AppAccent else AppSecondaryText,
                )
            }
        }
    }
}

@Composable
private fun ConnectionSection(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 1.dp).background(AppHairline))
        content()
    }
}

private fun credentialsFor(
    credentials: List<CredentialMetadata>,
    purpose: CredentialPurpose,
): List<CredentialMetadata> =
    credentials.filter { it.purpose == purpose || it.purpose == CredentialPurpose.Shared }

private fun SyncTextConfigDraft.isComplete(): Boolean =
    isHttpsEndpoint(asrEndpoint) &&
        isHttpsEndpoint(translationEndpoint) &&
        translationModel.isNotBlank() &&
        asrCredentialId != null &&
        translationCredentialId != null

private fun TranslationPageDraft.translationFingerprint(): String =
    fingerprintOf(protocol.name, endpoint.trim(), model.trim(), credentialId.orEmpty(), secret)

private fun AsrPageDraft.asrFingerprint(): String =
    fingerprintOf(profile.name, endpoint.trim(), credentialId.orEmpty(), secret)

private fun fingerprintOf(vararg values: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
