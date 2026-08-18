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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
    LaunchedEffect(asrCredentials, asrDraft.credentialId) {
        if (asrDraft.credentialId !in asrCredentials.map { it.credentialId }) {
            asrDraft = asrDraft.copy(credentialId = asrCredentials.firstOrNull()?.credentialId)
        }
    }
    LaunchedEffect(translationCredentials, translationDraft.credentialId) {
        if (translationDraft.credentialId !in translationCredentials.map { it.credentialId }) {
            translationDraft = translationDraft.copy(credentialId = translationCredentials.firstOrNull()?.credentialId)
        }
    }
    val focusManager = LocalFocusManager.current
    fun fullDraft(
        translation: TranslationPageDraft = translationDraft,
        asr: AsrPageDraft = asrDraft,
    ): SyncTextConfigDraft =
        SyncTextConfigDraft(
            asrProfile = asr.profile,
            asrEndpoint = asr.endpoint,
            asrCredentialId = asr.credentialId,
            asrSecret = asr.secret.takeIf(String::isNotBlank),
            translationProtocol = translation.protocol,
            translationEndpoint = translation.endpoint,
            translationUserAgent = existing?.translationUserAgent,
            translationModel = translation.model,
            translationCredentialId = translation.credentialId,
            translationSecret = translation.secret.takeIf(String::isNotBlank),
            targetLanguage = existing?.targetLanguage ?: "zh-CN",
            presentationMode = existing?.presentationMode ?: TextPresentationMode.TargetOnly,
        )
    fun commitDraft(
        translation: TranslationPageDraft = translationDraft,
        asr: AsrPageDraft = asrDraft,
    ) {
        val draft = fullDraft(translation, asr)
        if (!state.busy && draft.isComplete()) onAction(SyncTextAction.SaveConfig(existing?.configId, draft))
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
    LaunchedEffect(pagerState.currentPage) {
        focusManager.clearFocus(force = true)
        commitDraft()
    }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        PagerTabs(
            pagerState = pagerState,
            onSelect = { page ->
                focusManager.clearFocus(force = true)
                commitDraft()
                // A two-page pager has no intermediate navigation target.
                scope.launch {
                    pagerState.animateScrollToPage(page)
                }
            },
        )
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
                        modelDiscoveryMessage = state.modelDiscoveryMessage,
                        connectionTest = state.translationConnectionTest,
                        busy = state.busy,
                        listState = translationListState,
                        onDraftChanged = { translationDraft = it },
                        onCommit = { next -> commitDraft(translation = next) },
                        onDiscoverModels = { next -> discoverModels(next) },
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
                        listState = asrListState,
                        onDraftChanged = { asrDraft = it },
                        onCommit = { next -> commitDraft(asr = next) },
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
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
) {
    val progress by remember(pagerState) {
        derivedStateOf {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
        }
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().background(Color.Black),
    ) {
        val tabWidth = maxWidth / 2
        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("翻译", "语音识别").forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (pagerState.currentPage == index) AppPrimaryText else AppSecondaryText,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 2.dp)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = (tabWidth - 36.dp) / 2)
                        .width(36.dp)
                        .heightIn(min = 2.dp)
                        .background(AppAccent, RoundedCornerShape(2.dp))
                        .offset { IntOffset((tabWidth * progress).roundToPx(), 0) },
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
    modelDiscoveryMessage: String?,
    connectionTest: ConnectionTestUiState,
    busy: Boolean,
    listState: LazyListState,
    onDraftChanged: (TranslationPageDraft) -> Unit,
    onCommit: (TranslationPageDraft) -> Unit,
    onDiscoverModels: (TranslationPageDraft) -> Unit,
    onTest: (String) -> Unit,
) {
    val fingerprint = draft.translationFingerprint()
    val stale = connectionTest.testedFingerprint != null && connectionTest.testedFingerprint != fingerprint
    val authFailure = !stale && connectionTest.failureCategory == io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory.Authentication
    var detailsVisible by remember { mutableStateOf(false) }
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
                supportingText = when {
                    !isHttpsEndpoint(draft.endpoint) -> "请输入有效的 HTTPS 地址"
                    loadingModels -> "正在查找可用模型…"
                    else -> modelDiscoveryMessage
                },
                isError = !isHttpsEndpoint(draft.endpoint),
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
                message = modelDiscoveryMessage,
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
                hasSavedKey = draft.credentialId != null,
                authenticationFailed = authFailure,
                onValueChange = { onDraftChanged(draft.copy(secret = it)) },
                onCommit = { value ->
                    val next = draft.copy(secret = value)
                    onCommit(next)
                    onDiscoverModels(next)
                },
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
                    onDetails = { detailsVisible = true },
                )
            }
        }
    }
    if (detailsVisible) {
        ConnectionErrorDetailsSheet(
            state = connectionTest,
            onDismiss = { detailsVisible = false },
        )
    }
}

@Composable
private fun AsrPage(
    draft: AsrPageDraft,
    connectionTest: ConnectionTestUiState,
    busy: Boolean,
    listState: LazyListState,
    onDraftChanged: (AsrPageDraft) -> Unit,
    onCommit: (AsrPageDraft) -> Unit,
    onTest: (String) -> Unit,
) {
    val fingerprint = draft.asrFingerprint()
    val stale = connectionTest.testedFingerprint != null && connectionTest.testedFingerprint != fingerprint
    val authFailure = !stale && connectionTest.failureCategory == io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory.Authentication
    var detailsVisible by remember { mutableStateOf(false) }
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
                supportingText = if (!isHttpsEndpoint(draft.endpoint)) "请输入有效的 HTTPS 地址" else null,
                isError = !isHttpsEndpoint(draft.endpoint),
                onValueChange = { onDraftChanged(draft.copy(endpoint = it)) },
                onCommit = { value -> onCommit(draft.copy(endpoint = value)) },
            )
        }
        item {
            ApiKeyField(
                value = draft.secret,
                hasSavedKey = draft.credentialId != null,
                authenticationFailed = authFailure,
                onValueChange = { onDraftChanged(draft.copy(secret = it)) },
                onCommit = { value -> onCommit(draft.copy(secret = value)) },
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
                    onDetails = { detailsVisible = true },
                )
            }
        }
    }
    if (detailsVisible) {
        ConnectionErrorDetailsSheet(
            state = connectionTest,
            onDismiss = { detailsVisible = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseUrlField(
    value: String,
    placeholder: String,
    supportingText: String?,
    isError: Boolean,
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
            isError = isError,
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
        supportingText?.let {
            Text(it, color = if (isError) AppError else AppMutedText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    hasSavedKey: Boolean,
    authenticationFailed: Boolean,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var wasFocused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigFieldLabel("API Key")
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(if (hasSavedKey) "已配置，留空保持不变" else "输入 API Key", color = AppMutedText) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            isError = authenticationFailed,
            trailingIcon = {
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.getClipEntry()
                                ?.clipData
                                ?.getItemAt(0)
                                ?.text
                                ?.toString()
                                ?.takeIf(String::isNotBlank)
                                ?.let {
                                    onValueChange(it)
                                    onCommit(it)
                                }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "粘贴 API Key" },
                ) { Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = AppSecondaryText) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onCommit(value)
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
                if (focusState.isFocused) wasFocused = true
                else if (wasFocused) {
                    wasFocused = false
                    onCommit(value)
                }
            },
        )
        when {
            authenticationFailed -> Text("认证失败，请检查密钥", color = AppError, fontSize = 12.sp)
            hasSavedKey && value.isBlank() -> Text("已配置，留空保持不变", color = AppMutedText, fontSize = 12.sp)
        }
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
    message: String?,
    onOpen: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var manualValue by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigChoiceRow(label = "模型", value = value.ifBlank { "选择模型" }, onClick = {
            onOpen()
            manualEntry = false
            manualValue = value
            visible = true
        })
        when {
            loading -> Text("正在自动查找模型…", color = AppMutedText, fontSize = 12.sp)
            message != null -> Text(message, color = AppMutedText, fontSize = 12.sp)
            options.isNotEmpty() -> Text("已找到 ${options.size} 个可用模型", color = AppMutedText, fontSize = 12.sp)
        }
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
    onDetails: () -> Unit,
) {
    val failure = state.success == false && !stale && !state.running
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                state.running -> "正在连接…"
                stale -> "配置已修改"
                state.success == true -> "✓ 服务可用"
                failure -> "× 连接失败"
                else -> "测试连接"
            },
            color = if (failure) AppError else AppPrimaryText,
            modifier = Modifier.weight(1f),
        )
        when {
            state.running -> Text("◌", color = AppSecondaryText)
            stale -> TextButton(enabled = enabled, onClick = onClick) { Text("重新测试", color = AppAccent) }
            state.success == true -> TextButton(enabled = enabled, onClick = onClick) { Text("重新测试", color = AppAccent) }
            failure -> TextButton(onClick = onDetails) { Text("查看详情", color = AppAccent) }
            else -> TextButton(enabled = enabled, onClick = onClick) { Text("未测试", color = AppSecondaryText) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionErrorDetailsSheet(
    state: ConnectionTestUiState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppSurfaceRaised,
    ) {
        SheetTitle("连接失败")
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.failureCategory?.let { category ->
                Text(connectionCategoryLabel(category), color = AppSecondaryText, fontSize = 12.sp)
            }
            state.failureStatus?.let { status ->
                Text("HTTP $status", color = AppSecondaryText, fontSize = 12.sp)
            }
            Text(state.message ?: "连接失败", color = AppPrimaryText)
        }
        Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}

private fun connectionCategoryLabel(category: io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory): String =
    when (category) {
        io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory.Authentication -> "认证失败"
        io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory.RateLimited -> "请求过于频繁"
        io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory.TemporaryNetwork -> "网络或服务暂时不可用"
        io.github.zvensmoluya.vortexcue.synctext.GenerationErrorCategory.ModelOrProtocol -> "模型或协议不匹配"
        else -> "服务连接失败"
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
        (asrCredentialId != null || asrSecret?.isNotBlank() == true) &&
        (translationCredentialId != null || translationSecret?.isNotBlank() == true)

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
