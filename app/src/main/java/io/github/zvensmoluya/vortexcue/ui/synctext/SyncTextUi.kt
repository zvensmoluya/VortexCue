package io.github.zvensmoluya.vortexcue.ui.synctext

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.zIndex
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.data.ContentDetails
import io.github.zvensmoluya.vortexcue.synctext.AsrProfile
import io.github.zvensmoluya.vortexcue.synctext.AsrProvider
import io.github.zvensmoluya.vortexcue.synctext.AudioTrackInfo
import io.github.zvensmoluya.vortexcue.synctext.CredentialMetadata
import io.github.zvensmoluya.vortexcue.synctext.CredentialPurpose
import io.github.zvensmoluya.vortexcue.synctext.DiscoveredTranslationModel
import io.github.zvensmoluya.vortexcue.synctext.GenerationInput
import io.github.zvensmoluya.vortexcue.synctext.LanguageTags
import io.github.zvensmoluya.vortexcue.synctext.SyncTextConfig
import io.github.zvensmoluya.vortexcue.synctext.TextPresentationMode
import io.github.zvensmoluya.vortexcue.synctext.TranslationProtocol
import io.github.zvensmoluya.vortexcue.synctext.TranslationReasoningLevel
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppBackground
import io.github.zvensmoluya.vortexcue.ui.theme.AppError
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceSelected
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceRaised
import java.net.URI
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ConfigControlHeight = 58.dp
private val ConfigControlShape = RoundedCornerShape(8.dp)
private val ConnectionActionShape = RoundedCornerShape(12.dp)
private const val ConnectionSuccessFeedbackMillis = 900L

private enum class ConnectionActionPhase {
    Idle,
    Testing,
    Success,
    Failure,
}

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
    val reasoningLevel: TranslationReasoningLevel,
    val credentialId: String?,
    val secret: String,
    val targetLanguage: String,
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
    var textInputFocused by remember { mutableStateOf(false) }
    var lastCommittedDraft by remember(existing?.configId) {
        mutableStateOf<SyncTextConfigDraft?>(null)
    }
    val translationListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val asrListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var translationDraft by remember(existing?.configId) {
        mutableStateOf(
            TranslationPageDraft(
                protocol = existing?.translationProtocol ?: TranslationProtocol.Responses,
                endpoint = existing?.translationEndpoint?.toBaseUrl()
                    ?: TranslationProtocol.Responses.defaultBaseUrl(),
                model = existing?.translationModel.orEmpty(),
                reasoningLevel = existing?.translationReasoningLevel ?: TranslationReasoningLevel.Off,
                credentialId = existing?.translationCredentialId,
                secret = "",
                targetLanguage = existing?.targetLanguage ?: "zh-CN",
            ),
        )
    }
    var asrDraft by remember(existing?.configId) {
        mutableStateOf(
            AsrPageDraft(
                profile = existing?.asrProfile ?: AsrProfile.OpenRouterWhisperV1,
                endpoint = existing?.asrEndpoint ?: AsrProfile.OpenRouterWhisperV1.provider.defaultEndpoint,
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
            translationReasoningLevel = translation.reasoningLevel,
            translationCredentialId = translation.credentialId,
            translationSecret = null,
            targetLanguage = translation.targetLanguage,
            presentationMode = existing?.presentationMode ?: TextPresentationMode.TargetOnly,
        )
    fun commitDraft(
        translation: TranslationPageDraft = translationDraft,
        asr: AsrPageDraft = asrDraft,
    ) {
        val draft = fullDraft(translation, asr)
        if (!draft.isComplete()) return
        if (draft == lastCommittedDraft) return
        lastCommittedDraft = draft
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
                name = if (purpose == CredentialPurpose.Asr) asrDraft.profile.provider.displayName else "翻译服务",
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
    val commitLatestDraft by rememberUpdatedState { commitDraft() }
    DisposableEffect(Unit) {
        onDispose { commitLatestDraft() }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        PagerTabs(
            pagerState = pagerState,
            onSelect = { page ->
                focusManager.clearFocus(force = true)
                commitDraft()
                scope.launch {
                    pagerState.animateScrollToPage(page)
                }
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !textInputFocused,
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
                        modelsError = state.translationModelsError,
                        connectionTest = state.translationConnectionTest,
                        busy = state.busy || state.configSaving,
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
                                    translationDraft.reasoningLevel,
                                    translationDraft.credentialId,
                                    translationDraft.secret.takeIf(String::isNotBlank),
                                    fingerprint,
                                ),
                            )
                        },
                        onTextInputFocusChanged = { textInputFocused = it },
                    )
                else ->
                    AsrPage(
                        draft = asrDraft,
                        connectionTest = state.asrConnectionTest,
                        busy = state.busy || state.configSaving,
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
                        onTextInputFocusChanged = { textInputFocused = it },
                    )
                }
            }
            if (state.configSaving) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(2.dp),
                    color = AppAccent,
                    trackColor = Color.Transparent,
                )
            }
            state.configError?.let { error ->
                Text(
                    error,
                    color = AppError,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(AppBackground.copy(alpha = 0.96f))
                        .padding(horizontal = 20.dp, vertical = 6.dp),
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
    val pagePosition =
        (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, 1f)
    val stretchProgress = sin(PI * pagePosition).toFloat().coerceIn(0f, 1f)
    val landingScale by animateFloatAsState(
        targetValue = if (pagerState.isScrollInProgress) 0.985f else 1f,
        animationSpec =
            if (pagerState.isScrollInProgress) {
                tween(90)
            } else {
                spring(dampingRatio = 0.72f, stiffness = 650f)
            },
        label = "config-tab-landing",
    )
    val indicatorShape = RoundedCornerShape(6.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .selectableGroup()
                .testTag("sync-text-config-tabs"),
        ) {
            val tabWidth = maxWidth / 2
            val indicatorInset = 3.dp
            val indicatorStretch = 6.dp * stretchProgress
            val indicatorOffset =
                tabWidth * pagePosition + indicatorInset - indicatorStretch / 2
            val indicatorWidth = tabWidth - indicatorInset * 2 + indicatorStretch

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(indicatorWidth)
                    .fillMaxHeight()
                    .padding(vertical = indicatorInset)
                    .graphicsLayer {
                        scaleY = landingScale - stretchProgress * 0.012f
                    }
                    .background(AppSurfaceSelected, indicatorShape)
                    .border(1.dp, AppAccent.copy(alpha = 0.16f), indicatorShape)
                    .testTag("sync-text-config-tab-indicator"),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                listOf("翻译", "语音识别").forEachIndexed { index, label ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val pressProgress by animateFloatAsState(
                        targetValue = if (pressed) 1f else 0f,
                        animationSpec = tween(if (pressed) 80 else 150),
                        label = "config-tab-press",
                    )
                    val selectionProgress =
                        (1f - abs(pagePosition - index)).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .selectable(
                                selected = pagerState.settledPage == index,
                                onClick = {
                                    if (pagerState.targetPage != index) onSelect(index)
                                },
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = null,
                            )
                            .testTag("sync-text-config-tab-$index"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = lerp(AppSecondaryText, AppPrimaryText, selectionProgress),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .offset(y = 1.dp * pressProgress)
                                .graphicsLayer {
                                    val scale = 1f - pressProgress * 0.018f
                                    scaleX = scale
                                    scaleY = scale
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationPage(
    draft: TranslationPageDraft,
    availableModels: List<DiscoveredTranslationModel>,
    loadingModels: Boolean,
    modelsError: String?,
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
    onTextInputFocusChanged: (Boolean) -> Unit,
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
                onFocusChanged = onTextInputFocusChanged,
            )
        }
        item {
            ModelPicker(
                value = draft.model,
                options = availableModels,
                loading = loadingModels,
                error = modelsError,
                onOpen = { onDiscoverModels(draft) },
                onValueChange = {
                    val next = draft.copy(model = it)
                    onDraftChanged(next)
                    onCommit(next)
                },
            )
        }
        item {
            ReasoningLevelPicker(
                value = draft.reasoningLevel,
                onValueChange = {
                    val next = draft.copy(reasoningLevel = it)
                    onDraftChanged(next)
                    onCommit(next)
                },
            )
        }
        item {
            TargetLanguagePicker(
                value = draft.targetLanguage,
                onValueChange = {
                    val next = draft.copy(targetLanguage = it)
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
                onFocusChanged = onTextInputFocusChanged,
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
    onTextInputFocusChanged: (Boolean) -> Unit,
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
            AsrProviderPicker(
                value = draft.profile.provider,
                onValueChange = { provider ->
                    val next =
                        draft.copy(
                            profile = AsrProfile.defaultFor(provider),
                            endpoint = provider.defaultEndpoint,
                            credentialId = null,
                            secret = "",
                        )
                    onDraftChanged(next)
                    onCommit(next)
                },
            )
        }
        item {
            AsrModelPicker(
                value = draft.profile,
                options = AsrProfile.forProvider(draft.profile.provider),
                onValueChange = {
                    val next = draft.copy(profile = it)
                    onDraftChanged(next)
                    onCommit(next)
                },
            )
        }
        if (draft.profile.provider.endpointEditable) {
            item {
                BaseUrlField(
                    value = draft.endpoint,
                    placeholder = draft.profile.provider.defaultEndpoint,
                    onValueChange = { onDraftChanged(draft.copy(endpoint = it)) },
                    onCommit = { value -> onCommit(draft.copy(endpoint = value)) },
                    onFocusChanged = onTextInputFocusChanged,
                )
            }
        }
        item {
            ApiKeyField(
                value = draft.secret,
                hasSavedKey = hasSavedKey,
                saveError = credentialSaveError,
                saveCompletedRequestId = saveCompletedRequestId,
                onValueChange = { onDraftChanged(draft.copy(secret = it)) },
                onSave = onSaveCredential,
                onFocusChanged = onTextInputFocusChanged,
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
    onFocusChanged: (Boolean) -> Unit,
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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConfigControlHeight)
                .onFocusChanged { focusState ->
                    onFocusChanged(focusState.isFocused)
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
                }
                .testTag("sync-text-base-url-field"),
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
    onFocusChanged: (Boolean) -> Unit,
) {
    var editing by rememberSaveable(hasSavedKey) { mutableStateOf(!hasSavedKey) }
    var localError by rememberSaveable(hasSavedKey, saveError) { mutableStateOf<String?>(null) }
    var externalErrorVisible by remember(saveError) { mutableStateOf(true) }
    val error = localError ?: saveError.takeIf { externalErrorVisible }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(saveCompletedRequestId) {
        if (saveCompletedRequestId != null) {
            onFocusChanged(false)
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
                focusManager.clearFocus()
                onFocusChanged(false)
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
                                    onFocusChanged(false)
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
                    .onFocusChanged { onFocusChanged(it.isFocused) }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ModelPicker(
    value: String,
    options: List<DiscoveredTranslationModel>,
    loading: Boolean,
    error: String?,
    onOpen: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ConfigChoiceRow(
        label = "模型",
        value = value.ifBlank { "选择模型" },
        onClick = {
            onOpen()
            query = ""
            visible = true
        },
        modifier = Modifier.testTag("translation-model-picker"),
    )
    if (visible) {
        val trimmedQuery = query.trim()
        val matchingModels =
            remember(options, trimmedQuery) {
                if (trimmedQuery.isBlank()) {
                    options
                } else {
                    options
                        .filter { model -> model.matches(trimmedQuery) }
                        .sortedWith(
                            compareBy<DiscoveredTranslationModel> { it.matchRank(trimmedQuery) }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.requestId },
                        )
                }
            }
        val selectedModel =
            remember(options, value) {
                options.firstOrNull { it.requestId == value }
                    ?: value.takeIf(String::isNotBlank)?.let { DiscoveredTranslationModel(it, it) }
            }
        val browseGroups =
            remember(options, value) {
                options
                    .filterNot { it.requestId == value }
                    .toBrowseGroups()
            }
        val canUseManualValue =
            trimmedQuery.isNotBlank() && options.none { it.requestId == trimmedQuery }
        ModalBottomSheet(
            onDismissRequest = { visible = false },
            containerColor = AppSurfaceRaised,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            ) {
                SheetTitle("模型")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索名称或输入模型 ID") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("translation-model-search"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AppAccent,
                    )
                }
                error?.let { message ->
                    Text(
                        message,
                        color = AppError,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                if (canUseManualValue) {
                    ManualModelRow(
                        modelId = trimmedQuery,
                        onClick = {
                            onValueChange(trimmedQuery)
                            visible = false
                        },
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("translation-model-list"),
                ) {
                    if (trimmedQuery.isBlank()) {
                        selectedModel?.let { model ->
                            item(key = "current-model") {
                                ModelSectionHeader("当前使用")
                            }
                            item(key = "current-model:${model.requestId}") {
                                ModelChoiceRow(
                                    model = model,
                                    selected = true,
                                    onClick = { visible = false },
                                )
                            }
                        }
                        browseGroups.forEach { group ->
                            group.label?.let { label ->
                                stickyHeader(key = "model-group:$label") {
                                    ModelSectionHeader(label)
                                }
                            }
                            items(
                                items = group.models,
                                key = { model -> "model:${model.requestId}" },
                            ) { model ->
                                ModelChoiceRow(
                                    model = model,
                                    selected = false,
                                    onClick = {
                                        onValueChange(model.requestId)
                                        visible = false
                                    },
                                )
                            }
                        }
                    } else {
                        items(
                            items = matchingModels,
                            key = { model -> "search-model:${model.requestId}" },
                        ) { model ->
                            ModelChoiceRow(
                                model = model,
                                selected = model.requestId == value,
                                onClick = {
                                    onValueChange(model.requestId)
                                    visible = false
                                },
                            )
                        }
                    }
                    if (!loading && matchingModels.isEmpty()) {
                        item(key = "empty-model-results") {
                            Text(
                                if (trimmedQuery.isBlank()) {
                                    "服务未返回模型目录，可以直接输入模型 ID"
                                } else {
                                    "没有匹配的目录结果"
                                },
                                color = AppSecondaryText,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

private data class ModelBrowseGroup(
    val label: String?,
    val models: List<DiscoveredTranslationModel>,
)

private fun List<DiscoveredTranslationModel>.toBrowseGroups(): List<ModelBrowseGroup> {
    val namedGroups =
        filter { it.groupLabel != null }
            .groupBy { requireNotNull(it.groupLabel) }
            .entries
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .map { (label, models) ->
                ModelBrowseGroup(
                    label = label,
                    models = models.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }),
                )
            }
    val ungrouped = filter { it.groupLabel == null }
    return if (ungrouped.isEmpty()) namedGroups else namedGroups + ModelBrowseGroup(null, ungrouped)
}

private fun DiscoveredTranslationModel.matches(query: String): Boolean =
    displayName.contains(query, ignoreCase = true) ||
        requestId.contains(query, ignoreCase = true) ||
        groupLabel?.contains(query, ignoreCase = true) == true

private fun DiscoveredTranslationModel.matchRank(query: String): Int =
    when {
        requestId.equals(query, ignoreCase = true) -> 0
        displayName.equals(query, ignoreCase = true) -> 1
        displayName.startsWith(query, ignoreCase = true) -> 2
        requestId.startsWith(query, ignoreCase = true) -> 3
        else -> 4
    }

@Composable
private fun ModelSectionHeader(title: String) {
    Text(
        title,
        color = AppSecondaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceRaised)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun ModelChoiceRow(
    model: DiscoveredTranslationModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp)
            .testTag("translation-model-${model.requestId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                model.displayName,
                color = AppPrimaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.displayName != model.requestId) {
                Text(
                    model.requestId,
                    color = AppMutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "已选择",
                tint = AppAccent,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun ManualModelRow(
    modelId: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("translation-model-manual-action"),
    ) {
        Column {
            Text(
                "使用“$modelId”",
                color = AppAccent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "作为模型 ID 原样保存",
                color = AppMutedText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun ReasoningLevelPicker(
    value: TranslationReasoningLevel,
    onValueChange: (TranslationReasoningLevel) -> Unit,
) {
    val levels = TranslationReasoningLevel.entries
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var visualIndex by remember { mutableIntStateOf(value.ordinal) }
    var dragPosition by remember { mutableFloatStateOf(value.ordinal.toFloat()) }
    var lastTickIndex by remember { mutableIntStateOf(value.ordinal) }

    LaunchedEffect(value) {
        if (!dragging) {
            visualIndex = value.ordinal
            dragPosition = value.ordinal.toFloat()
            lastTickIndex = value.ordinal
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConfigFieldLabel("思考强度")
            Text(
                value.reasoningHint(),
                color = AppMutedText,
                fontSize = 12.sp,
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag("translation-reasoning-selector"),
        ) {
            val segmentWidth = maxWidth / levels.size
            val segmentWidthPx = with(density) { segmentWidth.toPx() }.coerceAtLeast(1f)
            val indicatorPosition by animateFloatAsState(
                targetValue = if (dragging) dragPosition else visualIndex.toFloat(),
                animationSpec =
                    if (dragging) {
                        tween(durationMillis = 40)
                    } else {
                        spring(dampingRatio = 0.58f, stiffness = 560f)
                    },
                label = "reasoning-selector-position",
            )
            val indicatorElevation by animateDpAsState(
                targetValue = if (dragging) 9.dp else 2.dp,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 520f),
                label = "reasoning-selector-elevation",
            )
            val fractionalStretch = abs(indicatorPosition - indicatorPosition.roundToInt()).coerceIn(0f, 0.5f) * 2f
            val stretch = 7.dp * fractionalStretch
            val draggableState = rememberDraggableState { delta ->
                dragPosition =
                    (dragPosition + delta / segmentWidthPx)
                        .coerceIn(0f, levels.lastIndex.toFloat())
                val tickIndex = dragPosition.roundToInt().coerceIn(0, levels.lastIndex)
                if (tickIndex != lastTickIndex) {
                    lastTickIndex = tickIndex
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppSurfaceRaised, RoundedCornerShape(18.dp))
                    .border(1.dp, AppHairline, RoundedCornerShape(18.dp))
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Horizontal,
                        onDragStarted = {
                            dragging = true
                            dragPosition = indicatorPosition
                            lastTickIndex = dragPosition.roundToInt()
                        },
                        onDragStopped = {
                            val settledIndex = dragPosition.roundToInt().coerceIn(0, levels.lastIndex)
                            visualIndex = settledIndex
                            dragPosition = settledIndex.toFloat()
                            dragging = false
                            levels[settledIndex].takeIf { it != value }?.let(onValueChange)
                        },
                    )
                    .selectableGroup(),
            ) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = segmentWidth * indicatorPosition + 4.dp - stretch / 2,
                            y = 4.dp,
                        )
                        .width(segmentWidth - 8.dp + stretch)
                        .fillMaxHeight()
                        .padding(bottom = 8.dp)
                        .shadow(indicatorElevation, RoundedCornerShape(14.dp), clip = false)
                        .graphicsLayer {
                            scaleY = 1f - fractionalStretch * 0.035f
                        }
                        .background(AppSurfaceSelected, RoundedCornerShape(14.dp))
                        .border(1.dp, AppAccent.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                        .zIndex(0f)
                        .testTag("translation-reasoning-indicator"),
                )

                Row(modifier = Modifier.fillMaxSize().zIndex(1f)) {
                    levels.forEachIndexed { index, level ->
                        val proximity = (1f - abs(indicatorPosition - index)).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .selectable(
                                    selected = value == level,
                                    onClick = {
                                        if (visualIndex != index) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        visualIndex = index
                                        dragPosition = index.toFloat()
                                        lastTickIndex = index
                                        if (level != value) onValueChange(level)
                                    },
                                    role = Role.RadioButton,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                )
                                .testTag("translation-reasoning-${level.name.lowercase()}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                level.reasoningLabel(),
                                color = lerp(AppSecondaryText, AppPrimaryText, proximity),
                                fontSize = 14.sp,
                                fontWeight = if (proximity > 0.6f) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.graphicsLayer {
                                    val scale = 0.98f + proximity * 0.04f
                                    scaleX = scale
                                    scaleY = scale
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetLanguagePicker(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val options =
        listOf(
            "zh-CN" to "简体中文",
            "zh-TW" to "繁體中文",
            "en" to "English",
            "ja" to "日本語",
            "ko" to "한국어",
        )
    val label = options.firstOrNull { it.first == value }?.second ?: value
    ConfigChoiceRow(label = "翻译成", value = label, onClick = { visible = true })
    if (visible) {
        ModalBottomSheet(onDismissRequest = { visible = false }, containerColor = AppSurfaceRaised) {
            SheetTitle("翻译成")
            options.forEach { (tag, title) ->
                SheetChoiceRow(
                    title = title,
                    selected = tag == value,
                    onClick = {
                        onValueChange(tag)
                        visible = false
                    },
                )
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsrProviderPicker(
    value: AsrProvider,
    onValueChange: (AsrProvider) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    ConfigChoiceRow(label = "识别服务", value = value.displayName, onClick = { visible = true })
    if (visible) {
        ModalBottomSheet(onDismissRequest = { visible = false }, containerColor = AppSurfaceRaised) {
            SheetTitle("识别服务")
            AsrProvider.entries.forEach { provider ->
                SheetChoiceRow(
                    title = provider.displayName,
                    selected = provider == value,
                    onClick = {
                        onValueChange(provider)
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
private fun AsrModelPicker(
    value: AsrProfile,
    options: List<AsrProfile>,
    onValueChange: (AsrProfile) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    ConfigChoiceRow(label = "识别模型", value = value.displayLabel(), onClick = { visible = true })
    if (visible) {
        ModalBottomSheet(onDismissRequest = { visible = false }, containerColor = AppSurfaceRaised) {
            SheetTitle("识别模型")
            options.forEach { profile ->
                SheetChoiceRow(
                    title = profile.displayModelLabel(),
                    selected = profile == value,
                    onClick = {
                        onValueChange(profile)
                        visible = false
                    },
                )
            }
            Spacer(Modifier.navigationBarsPadding())
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
internal fun ConnectionStatusRow(
    state: ConnectionTestUiState,
    stale: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val failure = state.success == false && !stale && !state.running
    var wasRunning by remember { mutableStateOf(state.running) }
    var showSuccessFeedback by remember { mutableStateOf(false) }
    LaunchedEffect(state.running, state.success, stale) {
        val justCompleted = wasRunning && !state.running
        wasRunning = state.running
        if (justCompleted && state.success == true && !stale) {
            showSuccessFeedback = true
            delay(ConnectionSuccessFeedbackMillis)
            showSuccessFeedback = false
        } else if (state.running || stale || state.success != true) {
            showSuccessFeedback = false
        }
    }

    val phase =
        when {
            state.running -> ConnectionActionPhase.Testing
            showSuccessFeedback -> ConnectionActionPhase.Success
            failure -> ConnectionActionPhase.Failure
            else -> ConnectionActionPhase.Idle
        }
    val actionEnabled = enabled && phase != ConnectionActionPhase.Testing && phase != ConnectionActionPhase.Success
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && actionEnabled) 0.96f else 1f,
        animationSpec = tween(if (pressed) 70 else 150),
        label = "connection-action-press",
    )
    val containerColor by animateColorAsState(
        targetValue =
            when (phase) {
                ConnectionActionPhase.Success -> AppAccent.copy(alpha = 0.22f)
                ConnectionActionPhase.Failure -> AppError.copy(alpha = 0.12f)
                ConnectionActionPhase.Testing -> AppSurfaceSelected
                ConnectionActionPhase.Idle -> AppSurfaceRaised
            },
        animationSpec = tween(160),
        label = "connection-action-container",
    )
    val borderColor by animateColorAsState(
        targetValue =
            when (phase) {
                ConnectionActionPhase.Success -> AppAccent.copy(alpha = 0.5f)
                ConnectionActionPhase.Failure -> AppError.copy(alpha = 0.42f)
                ConnectionActionPhase.Testing -> AppAccent.copy(alpha = 0.28f)
                ConnectionActionPhase.Idle -> AppHairline
            },
        animationSpec = tween(160),
        label = "connection-action-border",
    )
    val actionColor =
        when {
            !actionEnabled && phase == ConnectionActionPhase.Idle -> AppMutedText
            phase == ConnectionActionPhase.Failure -> AppError
            else -> AppPrimaryText
        }
    val haptics = LocalHapticFeedback.current

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
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(40.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .background(containerColor, ConnectionActionShape)
                .border(1.dp, borderColor, ConnectionActionShape)
                .clickable(
                    enabled = actionEnabled,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                )
                .testTag("connection-test-action"),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = phase,
                animationSpec = tween(140),
                label = "connection-action-state",
            ) { currentPhase ->
                val currentLabel =
                    when (currentPhase) {
                        ConnectionActionPhase.Testing -> "正在测试"
                        ConnectionActionPhase.Success -> "连接成功"
                        ConnectionActionPhase.Failure -> "重试"
                        ConnectionActionPhase.Idle ->
                            if (state.success == true && !stale) "重新测试" else "测试连接"
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    when (currentPhase) {
                        ConnectionActionPhase.Testing -> CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 1.8.dp,
                            color = AppAccent,
                        )
                        ConnectionActionPhase.Success -> Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = actionColor,
                            modifier = Modifier.size(16.dp),
                        )
                        ConnectionActionPhase.Failure -> Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = actionColor,
                            modifier = Modifier.size(16.dp),
                        )
                        ConnectionActionPhase.Idle -> Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = actionColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = currentLabel,
                        color = actionColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
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
        (asrCredentialId != null || asrSecret?.isNotBlank() == true)

private fun TranslationPageDraft.translationFingerprint(): String =
    fingerprintOf(
        protocol.name,
        endpoint.trim(),
        model.trim(),
        reasoningLevel.name,
        credentialId.orEmpty(),
        secret,
        targetLanguage,
    )

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

private data class AsrSourceLanguageChoice(
    val tag: String?,
    val label: String,
    val detail: String,
)

private val asrSourceLanguageChoices =
    listOf(
        AsrSourceLanguageChoice(null, "自动检测", "由识别模型判断音频语言"),
        AsrSourceLanguageChoice("ja", "日语", "明确提示模型按日语识别"),
        AsrSourceLanguageChoice("en", "英语", "明确提示模型按英语识别"),
        AsrSourceLanguageChoice("zh-CN", "简体中文", "识别结果会在整理前确定性转换为简体中文"),
    )

@Composable
private fun GenerationInputDialog(
    content: ContentDetails,
    state: SyncTextUiState,
    onAction: (SyncTextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices =
        remember(content, state.audioTracks, state.generationAudioOnly) {
            inputChoices(content, state.audioTracks, audioOnly = state.generationAudioOnly)
        }
    var selectedInput by remember(content.contentId, choices) {
        mutableStateOf(choices.singleOrNull()?.input)
    }
    var sourceLanguage by rememberSaveable(content.contentId) { mutableStateOf<String?>(null) }

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
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AppAccent,
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            if (state.inspectingAudioTracks) "正在读取音轨" else "正在启动语音识别",
                            color = AppSecondaryText,
                            fontSize = 12.sp,
                        )
                    }
                }
                state.audioInspectionError?.let { Text(it, color = AppError, fontSize = 12.sp) }
                if (!state.inspectingAudioTracks) {
                    LazyColumn(
                        modifier =
                            Modifier.fillMaxWidth().heightIn(
                                max = if (selectedInput is GenerationInput.AudioTrack) 260.dp else 360.dp,
                            ),
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
                    if (selectedInput is GenerationInput.AudioTrack) {
                        AsrSourceLanguagePicker(
                            value = sourceLanguage,
                            enabled = !state.busy,
                            onValueChange = { sourceLanguage = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy && !state.inspectingAudioTracks && selectedInput != null,
                onClick = {
                    onAction(
                        SyncTextAction.SubmitSelectedInput(
                            input = requireNotNull(selectedInput),
                            sourceLanguage = sourceLanguage.takeIf {
                                selectedInput is GenerationInput.AudioTrack
                            },
                        ),
                    )
                },
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
private fun AsrSourceLanguagePicker(
    value: String?,
    enabled: Boolean,
    onValueChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected =
        asrSourceLanguageChoices.firstOrNull { it.tag == value }
            ?: asrSourceLanguageChoices.first()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("识别语言", color = AppPrimaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = enabled,
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(selected.label, color = if (enabled) AppAccent else AppMutedText)
                    Text("▾", color = AppSecondaryText)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                asrSourceLanguageChoices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = {
                            onValueChange(choice.tag)
                            expanded = false
                        },
                    )
                }
            }
        }
        Text(selected.detail, color = AppMutedText, fontSize = 10.sp)
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

private fun inputChoices(
    content: ContentDetails,
    audioTracks: List<AudioTrackInfo>,
    audioOnly: Boolean,
): List<InputChoice> =
    buildList {
        if (!audioOnly) {
            content.availableTextTracks
                .filter {
                    it.readiness !in
                        setOf(
                            TimedTextReadiness.Unreadable,
                            TimedTextReadiness.ParseFailed,
                            TimedTextReadiness.MissingArtifact,
                        )
                }
                .forEach { track ->
                    val input =
                        track.locator.candidateId?.let(GenerationInput::DiscoveredCandidate) ?: when {
                            track.origin == TimedTextOrigin.Embedded ->
                                track.locator.embeddedTrackId?.let {
                                    GenerationInput.EmbeddedTrack(track.stableId, it)
                                }
                            else -> GenerationInput.ExistingTrack(track.stableId)
                        } ?: return@forEach
                    add(
                        InputChoice(
                            label = track.label ?: track.language ?: track.locator.displayName,
                            detail = "采用现有同步文字",
                            input = input,
                        ),
                    )
                }
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

private fun TranslationReasoningLevel.reasoningLabel(): String =
    when (this) {
        TranslationReasoningLevel.Off -> "关"
        TranslationReasoningLevel.Low -> "低"
        TranslationReasoningLevel.Medium -> "中"
        TranslationReasoningLevel.High -> "高"
    }

private fun TranslationReasoningLevel.reasoningHint(): String =
    when (this) {
        TranslationReasoningLevel.Off -> "速度优先"
        TranslationReasoningLevel.Low -> "轻量思考"
        TranslationReasoningLevel.Medium -> "平衡"
        TranslationReasoningLevel.High -> "质量优先"
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
        AsrProfile.OpenRouterQwen3Asr17BV1 -> "Qwen3 ASR 1.7B"
        AsrProfile.OpenAiWhisperV1 -> "Whisper-1"
    }

private fun AsrProfile.displayModelLabel(): String =
    when (this) {
        AsrProfile.OpenRouterWhisperV1 -> "Whisper large-v3"
        AsrProfile.OpenRouterQwen3Asr17BV1 -> "Qwen3 ASR 1.7B"
        AsrProfile.OpenAiWhisperV1 -> "Whisper-1"
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
