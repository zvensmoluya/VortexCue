package io.github.zvensmoluya.vortexcue.synctext

import androidx.room3.withWriteTransaction
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.stableTimedTextTrackId
import io.github.zvensmoluya.vortexcue.data.db.CredentialMetadataEntity
import io.github.zvensmoluya.vortexcue.data.db.GeneratedTextResultEntity
import io.github.zvensmoluya.vortexcue.data.db.GeneratedTextSlotEntity
import io.github.zvensmoluya.vortexcue.data.db.GenerationAttemptEntity
import io.github.zvensmoluya.vortexcue.data.db.GenerationTaskEntity
import io.github.zvensmoluya.vortexcue.data.db.GenerationTaskSnapshotEntity
import io.github.zvensmoluya.vortexcue.data.db.SyncTextQueueControlEntity
import io.github.zvensmoluya.vortexcue.data.db.SyncTextSchemeEntity
import io.github.zvensmoluya.vortexcue.data.db.TextSelectionEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.text.CanonicalTextCue
import io.github.zvensmoluya.vortexcue.text.CanonicalTextNormalizer
import io.github.zvensmoluya.vortexcue.text.RevisionArtifactRole
import io.github.zvensmoluya.vortexcue.text.TextArtifactKind
import io.github.zvensmoluya.vortexcue.text.TextStorageRepository
import io.github.zvensmoluya.vortexcue.text.TimelineAssembler
import io.github.zvensmoluya.vortexcue.text.TimelineCompleteness
import io.github.zvensmoluya.vortexcue.text.TimelineRevisionReader
import io.github.zvensmoluya.vortexcue.text.TimelineRole
import io.github.zvensmoluya.vortexcue.text.TranslationDelta
import io.github.zvensmoluya.vortexcue.text.TranslationDeltaEntry
import io.github.zvensmoluya.vortexcue.text.canonicalContentHash
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

interface SyncTextScheduler {
    fun schedule()

    fun cancel(taskId: String) = Unit
}

object NoOpSyncTextScheduler : SyncTextScheduler {
    override fun schedule() = Unit
}

class CredentialStillReferencedException :
    IllegalStateException("Credential is still referenced by a scheme or task")

class SchemeStillReferencedException :
    IllegalStateException("Scheme is still referenced by a task")

internal class TaskPublicationDeferredException :
    IllegalStateException("Task publication was deferred")

class SyncTextRepository(
    private val database: VortexDatabase,
    private val textStorage: TextStorageRepository,
    private val revisionReader: TimelineRevisionReader,
    private val credentials: CredentialStore,
    private val policies: TranslationPolicyRegistry,
    scope: CoroutineScope,
    private val scheduler: SyncTextScheduler = NoOpSyncTextScheduler,
    private val diagnostics: SyncTextDiagnosticRecorder = NoOpSyncTextDiagnosticRecorder,
    private val modelCatalog: TranslationModelCatalog? = null,
    private val connectionTester: SyncTextConnectionTester? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.syncTextDao()
    private val contentDao = database.contentDao()

    val schemes: StateFlow<List<SyncTextScheme>> =
        dao.observeSchemes()
            .map { entities -> entities.map(SyncTextSchemeEntity::toDomain) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Product-facing view of the single active configuration. */
    val config: StateFlow<SyncTextConfig?> =
        schemes
            .map { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
            .map { it?.toConfig() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val credentialMetadata: StateFlow<List<CredentialMetadata>> =
        dao.observeCredentials()
            .map { entities -> entities.map(CredentialMetadataEntity::toDomain) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pauseAll: StateFlow<Boolean> =
        dao.observeQueueControl()
            .map { it?.pauseAll == true }
            .stateIn(scope, SharingStarted.Eagerly, false)

    val tasks: StateFlow<List<GenerationTaskSummary>> =
        combine(dao.observeActiveTasks(), dao.observeQueueControl()) { entities, control ->
            entities.mapIndexed { index, entity ->
                entity.toSummary(queuePosition = index, pauseAll = control?.pauseAll == true)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun loadConfig(): SyncTextConfig? =
        (dao.defaultScheme() ?: dao.schemes().firstOrNull())?.toDomain()?.toConfig()

    suspend fun discoverTranslationModels(
        protocol: TranslationProtocol,
        endpoint: String,
        credentialId: String?,
        apiKey: String? = null,
    ): List<String> {
        val catalog = modelCatalog ?: return emptyList()
        val resolvedKey = validatedApiKey(apiKey?.takeIf(String::isNotBlank) ?: credentials.get(requireNotNull(credentialId)))
        return catalog.fetch(protocol, endpoint, resolvedKey)
    }

    suspend fun testTranslationConnection(
        protocol: TranslationProtocol,
        endpoint: String,
        model: String,
        credentialId: String?,
        apiKey: String? = null,
    ) {
        val tester = requireNotNull(connectionTester) { "Translation connection test is unavailable" }
        val resolvedKey =
            validatedApiKey(
                apiKey?.takeIf(String::isNotBlank) ?: credentials.get(requireNotNull(credentialId)),
            )
        val current = loadConfig()
        tester.testTranslation(
            protocol = protocol,
            endpoint = endpoint,
            model = model,
            apiKey = resolvedKey,
            targetLanguage = current?.targetLanguage ?: DefaultProbeTargetLanguage,
            userAgent = current?.translationUserAgent,
        )
    }

    suspend fun testAsrConnection(
        profile: AsrProfile,
        endpoint: String,
        credentialId: String?,
        apiKey: String? = null,
    ) {
        val tester = requireNotNull(connectionTester) { "ASR connection test is unavailable" }
        val resolvedKey = validatedApiKey(apiKey?.takeIf(String::isNotBlank) ?: credentials.get(requireNotNull(credentialId)))
        tester.testAsr(profile, endpoint, resolvedKey)
    }

    suspend fun saveConfigWithSecrets(
        config: SyncTextConfig,
        asrSecret: String?,
        translationSecret: String?,
    ): SyncTextConfig {
        val normalizedAsrSecret = asrSecret?.trim()?.takeIf(String::isNotBlank)?.let(::validatedApiKey)
        val normalizedTranslationSecret = translationSecret?.trim()?.takeIf(String::isNotBlank)?.let(::validatedApiKey)
        val sharedCredentialId =
            config.asrCredentialId
                .takeIf { it.isNotBlank() && it == config.translationCredentialId }
        val (asrCredentialId, translationCredentialId) =
            if (sharedCredentialId != null) {
                when {
                    normalizedAsrSecret == null && normalizedTranslationSecret == null ->
                        sharedCredentialId to sharedCredentialId
                    normalizedAsrSecret == null || normalizedTranslationSecret == null ||
                        normalizedAsrSecret == normalizedTranslationSecret -> {
                        val secret = normalizedAsrSecret ?: normalizedTranslationSecret!!
                        updateInlineCredential(
                            existingId = sharedCredentialId,
                            secret = secret,
                            fallbackName = config.asrProfile.provider.displayName,
                            purpose = CredentialPurpose.Shared,
                        )
                        sharedCredentialId to sharedCredentialId
                    }
                    else -> {
                        val asr =
                            saveInlineCredential(
                                existingId = "",
                                secret = normalizedAsrSecret,
                                name = config.asrProfile.provider.displayName,
                                purpose = CredentialPurpose.Asr,
                            )
                        val translation =
                            saveInlineCredential(
                                existingId = "",
                                secret = normalizedTranslationSecret,
                                name = "翻译服务",
                                purpose = CredentialPurpose.Translation,
                            )
                        asr to translation
                    }
                }
            } else {
                saveInlineCredential(
                    existingId = config.asrCredentialId,
                    secret = normalizedAsrSecret,
                    name = config.asrProfile.provider.displayName,
                    purpose = CredentialPurpose.Asr,
                ) to saveOptionalInlineCredential(
                    existingId = config.translationCredentialId,
                    secret = normalizedTranslationSecret,
                    name = "翻译服务",
                    purpose = CredentialPurpose.Translation,
                )
            }
        return saveConfig(
            config.copy(
                asrCredentialId = asrCredentialId,
                translationCredentialId = translationCredentialId,
            ),
        )
    }

    suspend fun saveConfig(config: SyncTextConfig): SyncTextConfig {
        val stableConfigId =
            config.configId.takeIf(String::isNotBlank)
                ?: dao.defaultScheme()?.schemeId
                ?: dao.schemes().firstOrNull()?.schemeId
                ?: newId()
        val existing = dao.scheme(stableConfigId)?.toDomain()
        val scheme =
            (existing ?: SyncTextScheme(
                schemeId = stableConfigId,
                name = "默认配置",
                asrProfile = config.asrProfile,
                asrEndpoint = config.asrEndpoint,
                asrCredentialId = config.asrCredentialId,
                translationProtocol = config.translationProtocol,
                translationEndpoint = config.translationEndpoint,
                translationModel = config.translationModel,
                translationCredentialId = config.translationCredentialId,
                defaultTargetLanguage = config.targetLanguage,
                defaultPresentationMode = config.presentationMode,
                translationUserAgent = config.translationUserAgent,
                isDefault = true,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )).copy(
                name = "默认配置",
                asrProfile = config.asrProfile,
                asrEndpoint = config.asrEndpoint,
                asrCredentialId = config.asrCredentialId,
                translationProtocol = config.translationProtocol,
                translationEndpoint = config.translationEndpoint,
                translationModel = config.translationModel,
                translationCredentialId = config.translationCredentialId,
                defaultTargetLanguage = config.targetLanguage,
                defaultPresentationMode = config.presentationMode,
                translationUserAgent = config.translationUserAgent,
                isDefault = true,
            )
        return saveScheme(scheme).toConfig()
    }

    suspend fun saveCredential(
        credentialId: String? = null,
        name: String,
        purpose: CredentialPurpose,
        secret: String,
    ): CredentialMetadata {
        val normalizedSecret = validatedApiKey(secret)
        val id = credentialId ?: newId()
        val existing = dao.credential(id)
        val timestamp = nowMs()
        val previousSecret = existing?.let { runCatching { credentials.get(id) }.getOrNull() }
        val effectivePurpose =
            if (existing?.purpose == CredentialPurpose.Shared.name) CredentialPurpose.Shared else purpose
        credentials.put(id, normalizedSecret)
        val entity =
            CredentialMetadataEntity(
                credentialId = id,
                name = name.trim().also { require(it.isNotBlank()) },
                purpose = effectivePurpose.name,
                maskedValue = maskCredential(normalizedSecret),
                createdAtMs = existing?.createdAtMs ?: timestamp,
                updatedAtMs = timestamp,
            )
        runCatching { dao.upsertCredential(entity) }
            .onFailure {
                if (previousSecret == null) {
                    credentials.delete(id)
                } else {
                    credentials.put(id, previousSecret)
                }
            }.getOrThrow()
        return entity.toDomain()
    }

    private suspend fun saveInlineCredential(
        existingId: String,
        secret: String?,
        name: String,
        purpose: CredentialPurpose,
    ): String {
        val normalizedSecret = secret?.trim()?.takeIf(String::isNotBlank)
        val id = existingId.trim()
        if (normalizedSecret == null) return id
        val existing = if (id.isBlank()) null else dao.credential(id)
        return saveCredential(
            credentialId = id.takeIf(String::isNotBlank),
            name = existing?.name ?: uniqueCredentialName(name),
            purpose = purpose,
            secret = normalizedSecret,
        ).credentialId
    }

    private suspend fun saveOptionalInlineCredential(
        existingId: String?,
        secret: String?,
        name: String,
        purpose: CredentialPurpose,
    ): String? {
        val normalizedSecret = secret?.trim()?.takeIf(String::isNotBlank)
        val id = existingId?.trim().orEmpty()
        if (normalizedSecret == null) return id.takeIf(String::isNotBlank)
        val existing = if (id.isBlank()) null else dao.credential(id)
        return saveCredential(
            credentialId = id.takeIf(String::isNotBlank),
            name = existing?.name ?: uniqueCredentialName(name),
            purpose = purpose,
            secret = normalizedSecret,
        ).credentialId
    }

    private suspend fun updateInlineCredential(
        existingId: String,
        secret: String,
        fallbackName: String,
        purpose: CredentialPurpose,
    ): String {
        val existing = dao.credential(existingId)
        return saveCredential(
            credentialId = existingId,
            name = existing?.name ?: uniqueCredentialName(fallbackName),
            purpose = purpose,
            secret = secret,
        ).credentialId
    }

    private suspend fun uniqueCredentialName(baseName: String): String {
        val names = dao.credentials().map { it.name }.toSet()
        if (baseName !in names) return baseName
        var suffix = 2
        while ("$baseName $suffix" in names) suffix++
        return "$baseName $suffix"
    }

    private fun validatedApiKey(value: String): String {
        val key = value.trim()
        require(key.isNotBlank()) { "API Key 不能为空" }
        require(key.all { it.code in 0x21..0x7E }) {
            "API Key 不能包含遮罩或不可发送字符，请输入真实密钥"
        }
        return key
    }

    suspend fun deleteCredential(credentialId: String) {
        if (dao.credentialReferenceCount(credentialId) > 0) throw CredentialStillReferencedException()
        if (dao.deleteCredential(credentialId) > 0) credentials.delete(credentialId)
    }

    suspend fun saveScheme(scheme: SyncTextScheme): SyncTextScheme {
        val normalized = scheme.normalizedAndValidated()
        requireCredential(normalized.asrCredentialId, CredentialPurpose.Asr)
        normalized.translationCredentialId?.let { requireCredential(it, CredentialPurpose.Translation) }
        val existing = dao.scheme(normalized.schemeId)
        val timestamp = nowMs()
        val entity =
            normalized.copy(
                createdAtMs = existing?.createdAtMs ?: timestamp,
                updatedAtMs = timestamp,
            ).toEntity()
        database.withWriteTransaction {
            dao.upsertScheme(entity)
            if (entity.isDefault) dao.clearOtherDefaultSchemes(entity.schemeId)
        }
        return requireNotNull(dao.scheme(entity.schemeId)).toDomain()
    }

    suspend fun createScheme(
        name: String,
        asrProfile: AsrProfile,
        asrEndpoint: String = asrProfile.provider.defaultEndpoint,
        asrCredentialId: String,
        translationProtocol: TranslationProtocol = TranslationProtocol.Responses,
        translationEndpoint: String? = null,
        translationUserAgent: String? = null,
        translationModel: String? = null,
        translationCredentialId: String? = null,
        defaultTargetLanguage: String = DefaultProbeTargetLanguage,
        defaultPresentationMode: TextPresentationMode = TextPresentationMode.TargetOnly,
        isDefault: Boolean = false,
    ): SyncTextScheme =
        saveScheme(
            SyncTextScheme(
                schemeId = newId(),
                name = name,
                asrProfile = asrProfile,
                asrEndpoint = asrEndpoint,
                asrCredentialId = asrCredentialId,
                translationProtocol = translationProtocol,
                translationEndpoint = translationEndpoint,
                translationUserAgent = translationUserAgent,
                translationModel = translationModel,
                translationCredentialId = translationCredentialId,
                defaultTargetLanguage = defaultTargetLanguage,
                defaultPresentationMode = defaultPresentationMode,
                isDefault = isDefault,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            ),
        )

    suspend fun deleteScheme(schemeId: String): Boolean {
        if (dao.schemeReferenceCount(schemeId) > 0) throw SchemeStillReferencedException()
        return dao.deleteScheme(schemeId) > 0
    }

    suspend fun submit(request: GenerationRequest): GenerationTaskSummary {
        val normalized = request.normalized()
        if (normalized.output is GenerationOutput.Translated) {
            val sourceResultId = normalized.generatedSourceResultId()
                ?: throw IllegalArgumentException("Translation requires a generated source result")
            return submitTranslation(
                sourceResultId = sourceResultId,
                schemeId = normalized.schemeId,
                targetLanguage = normalized.output.targetLanguage,
                presentationMode = normalized.output.presentationMode,
                allowMeteredNetwork = normalized.allowMeteredNetwork,
            )
        }
        val scheme = requireNotNull(dao.scheme(normalized.schemeId)) { "Sync text scheme does not exist" }
        requireNotNull(contentDao.content(normalized.contentId)) { "Content does not exist" }
        validateInput(normalized)
        require(credentials.contains(scheme.asrCredentialId)) { "ASR credential is unavailable" }

        val dedupeKey = sourceDedupeKey(normalized.contentId)
        dao.activeTaskByDedupeKey(dedupeKey)?.let { return it.toSummary(it.queuePosition, false) }

        val timestamp = nowMs()
        val taskId = newId()
        val attemptId = newId()
        val task =
            GenerationTaskEntity(
                taskId = taskId,
                contentId = normalized.contentId,
                schemeId = scheme.schemeId,
                kind = GenerationTaskKind.SourceGeneration.name,
                activeDedupeKey = dedupeKey,
                state = GenerationTaskState.Waiting.name,
                stage = GenerationStage.Queued.name,
                queuePosition = 0,
                progress = 0f,
                targetLanguage = null,
                waitingForNetwork = false,
                meteredNetworkAllowed = normalized.allowMeteredNetwork,
                paused = false,
                needsAttention = false,
                schemeBlocked = false,
                currentAttemptId = attemptId,
                sourceResultId = null,
                createdAtMs = timestamp,
                updatedAtMs = timestamp,
            )
        database.withWriteTransaction {
            dao.activeTaskByDedupeKey(dedupeKey)?.let { return@withWriteTransaction }
            val queued = task.copy(queuePosition = dao.maxQueuePosition() + 1)
            dao.insertTask(queued)
            dao.insertTaskSnapshot(normalized.toSnapshot(taskId, timestamp, inputSourceResultId = null))
            dao.insertAttempt(
                scheme.toAttempt(
                    taskId = taskId,
                    attemptId = attemptId,
                    ordinal = 0,
                    policy = null,
                    sourceResultId = null,
                    timestamp = timestamp,
                ),
            )
        }
        val submitted = requireNotNull(dao.activeTaskByDedupeKey(dedupeKey))
        diagnostics.record(
            SyncTextDiagnosticEventType.TaskSubmitted,
            taskId = submitted.taskId,
            attemptId = submitted.currentAttemptId,
            stage = submitted.stage,
            outcome = submitted.kind,
        )
        scheduler.schedule()
        return submitted.toSummary(submitted.queuePosition, false)
    }

    suspend fun submitTranslation(
        sourceResultId: String,
        schemeId: String? = null,
        targetLanguage: String? = null,
        presentationMode: TextPresentationMode? = null,
        allowMeteredNetwork: Boolean = false,
    ): GenerationTaskSummary {
        val source = requireNotNull(dao.generatedResult(sourceResultId)) { "Source result does not exist" }
        require(source.targetLanguage == null) { "Translation input must be a source result" }
        require(source.completeness == GeneratedResultCompleteness.Final.name) {
            "Translation requires a final source result"
        }
        val scheme = requireNotNull(
            schemeId?.let { dao.scheme(it) } ?: dao.defaultScheme() ?: dao.schemes().firstOrNull(),
        ) { "Sync text configuration does not exist" }
        val endpoint = requireNotNull(scheme.translationEndpoint) { "Translation endpoint is not configured" }
        val model = requireNotNull(scheme.translationModel) { "Translation model is not configured" }
        val credentialId = requireNotNull(scheme.translationCredentialId) { "Translation credential is not configured" }
        require(endpoint.isNotBlank() && model.isNotBlank()) { "Translation configuration is incomplete" }
        require(credentials.contains(credentialId)) { "Translation credential is unavailable" }
        val normalizedTarget = LanguageTags.normalize(targetLanguage ?: scheme.defaultTargetLanguage)
        require(LanguageTags.normalizeOrNull(source.sourceLanguage) != normalizedTarget) {
            "Source and target languages are the same"
        }
        val mode = presentationMode ?: scheme.defaultPresentationMode.enumValueOr(TextPresentationMode.TargetOnly)
        val dedupeKey = translationDedupeKey(source.contentId, sourceResultId, normalizedTarget)
        dao.activeTaskByDedupeKey(dedupeKey)?.let { return it.toSummary(it.queuePosition, false) }

        val timestamp = nowMs()
        val taskId = newId()
        val attemptId = newId()
        val policy = policies.resolve(normalizedTarget)
        val task =
            GenerationTaskEntity(
                taskId = taskId,
                contentId = source.contentId,
                schemeId = scheme.schemeId,
                kind = GenerationTaskKind.TranslationDerivation.name,
                activeDedupeKey = dedupeKey,
                state = GenerationTaskState.Waiting.name,
                stage = GenerationStage.SourcePublished.name,
                queuePosition = 0,
                progress = SourcePublishedProgress,
                targetLanguage = normalizedTarget,
                waitingForNetwork = false,
                meteredNetworkAllowed = allowMeteredNetwork,
                paused = false,
                needsAttention = false,
                schemeBlocked = false,
                currentAttemptId = attemptId,
                sourceResultId = sourceResultId,
                createdAtMs = timestamp,
                updatedAtMs = timestamp,
            )
        database.withWriteTransaction {
            dao.activeTaskByDedupeKey(dedupeKey)?.let { return@withWriteTransaction }
            dao.insertTask(task.copy(queuePosition = dao.maxQueuePosition() + 1))
            dao.insertTaskSnapshot(
                GenerationTaskSnapshotEntity(
                    taskId = taskId,
                    inputKind = "SourceResult",
                    sourceTrackId = null,
                    embeddedTrackId = null,
                    audioTrackId = null,
                    inputSourceResultId = sourceResultId,
                    sourceLanguage = source.sourceLanguage,
                    targetLanguage = normalizedTarget,
                    presentationMode = mode.name,
                    allowMeteredNetwork = allowMeteredNetwork,
                    allowAsrFallback = false,
                    submittedAtMs = timestamp,
                ),
            )
            dao.insertAttempt(
                scheme.toAttempt(taskId, attemptId, 0, policy, sourceResultId, timestamp),
            )
        }
        diagnostics.record(
            SyncTextDiagnosticEventType.TaskSubmitted,
            taskId = taskId,
            attemptId = attemptId,
            stage = GenerationStage.SourcePublished.name,
            outcome = GenerationTaskKind.TranslationDerivation.name,
        )
        scheduler.schedule()
        return requireNotNull(dao.activeTaskByDedupeKey(dedupeKey)).toSummary(dao.task(taskId)?.queuePosition ?: 0, false)
    }

    suspend fun moveTask(taskId: String, targetPosition: Int) {
        database.withWriteTransaction {
            val active = dao.activeTasks()
            val moving = active.firstOrNull { it.taskId == taskId } ?: return@withWriteTransaction
            if (moving.state != GenerationTaskState.Waiting.name) return@withWriteTransaction
            val waiting = active.filter { it.state == GenerationTaskState.Waiting.name }.toMutableList()
            val from = waiting.indexOfFirst { it.taskId == taskId }
            if (from < 0) return@withWriteTransaction
            waiting.removeAt(from)
            waiting.add(targetPosition.coerceIn(0, waiting.size), moving)
            val waitingPositions = active.filter { it.state == GenerationTaskState.Waiting.name }.map { it.queuePosition }.sorted()
            waiting.zip(waitingPositions).forEach { (task, position) ->
                dao.upsertTask(task.copy(queuePosition = position, updatedAtMs = nowMs()))
            }
        }
    }

    suspend fun pauseTask(taskId: String) {
        val attemptId = dao.task(taskId)?.currentAttemptId
        if (dao.pauseTask(taskId, nowMs()) > 0) {
            diagnostics.record(
                SyncTextDiagnosticEventType.TaskStateChanged,
                taskId = taskId,
                attemptId = attemptId,
                outcome = GenerationTaskState.Paused.name,
                reason = "User",
            )
            scheduler.schedule()
        }
    }

    suspend fun abortTask(taskId: String) {
        val attemptId = dao.task(taskId)?.currentAttemptId
        if (dao.markTaskAborted(taskId, nowMs()) > 0) {
            diagnostics.record(
                SyncTextDiagnosticEventType.TaskStateChanged,
                taskId = taskId,
                attemptId = attemptId,
                outcome = GenerationTaskState.Aborted.name,
                reason = "User",
            )
            scheduler.cancel(taskId)
            scheduler.schedule()
        }
    }

    suspend fun resumeTask(taskId: String) {
        var attemptId: String? = null
        database.withWriteTransaction {
            val task = dao.task(taskId)?.takeUnless(GenerationTaskEntity::isTerminal) ?: return@withWriteTransaction
            attemptId = task.currentAttemptId
            val timestamp = nowMs()
            if (task.schemeBlocked) {
                dao.resumeBlockedSchemeTasks(task.schemeId, timestamp)
            } else {
                dao.resumeTask(taskId, timestamp)
            }
            Unit
        }
        if (attemptId != null) {
            diagnostics.record(
                SyncTextDiagnosticEventType.TaskStateChanged,
                taskId = taskId,
                attemptId = attemptId,
                outcome = GenerationTaskState.Waiting.name,
                reason = "UserResume",
            )
        }
        scheduler.schedule()
    }

    suspend fun setPauseAll(paused: Boolean) {
        dao.upsertQueueControl(SyncTextQueueControlEntity(pauseAll = paused, updatedAtMs = nowMs()))
        if (!paused) scheduler.schedule()
    }

    suspend fun deleteTask(taskId: String) {
        val attemptId = dao.task(taskId)?.currentAttemptId
        if (dao.markTaskDeleted(taskId, nowMs()) > 0) {
            diagnostics.record(
                SyncTextDiagnosticEventType.TaskStateChanged,
                taskId = taskId,
                attemptId = attemptId,
                outcome = GenerationTaskState.Deleted.name,
                reason = "User",
            )
        }
        scheduler.schedule()
    }

    suspend fun cancelForContent(contentId: String) {
        dao.activeTasksForContent(contentId).forEach { pauseTask(it.taskId) }
    }

    suspend fun abortForContent(contentId: String) {
        dao.activeTasksForContent(contentId).forEach { abortTask(it.taskId) }
    }

    suspend fun retryForContent(contentId: String) {
        dao.activeTasksForContent(contentId).forEach { resumeTask(it.taskId) }
    }

    suspend fun allowMeteredNetworkForContent(contentId: String) {
        val waiting = dao.networkWaitingTasks().filter { it.contentId == contentId }
        if (dao.allowMeteredNetworkForContent(contentId, nowMs()) > 0) {
            waiting.forEach { task ->
                diagnostics.record(
                    SyncTextDiagnosticEventType.MeteredNetworkAllowed,
                    taskId = task.taskId,
                    attemptId = task.currentAttemptId,
                    outcome = "AllowedForTask",
                )
            }
            scheduler.schedule()
        }
    }

    suspend fun changeScheme(taskId: String, schemeId: String) {
        val scheme = requireNotNull(dao.scheme(schemeId)) { "Sync text scheme does not exist" }
        var changedAttemptId: String? = null
        database.withWriteTransaction {
            val task = dao.task(taskId)?.takeUnless(GenerationTaskEntity::isTerminal) ?: return@withWriteTransaction
            val snapshot = requireNotNull(dao.taskSnapshot(taskId))
            val attempts = dao.attemptsForTask(taskId)
            val previous = requireNotNull(attempts.lastOrNull())
            val target = snapshot.targetLanguage
            val policy = target?.let(policies::resolve)
            val attemptId = newId()
            changedAttemptId = attemptId
            val timestamp = nowMs()
            dao.insertAttempt(
                scheme.toAttempt(
                    taskId = taskId,
                    attemptId = attemptId,
                    ordinal = previous.ordinal + 1,
                    policy = policy,
                    sourceResultId = previous.sourceResultId ?: task.sourceResultId,
                    timestamp = timestamp,
                ),
            )
            dao.upsertTask(
                task.copy(
                    schemeId = schemeId,
                    currentAttemptId = attemptId,
                    state = GenerationTaskState.Waiting.name,
                    stage =
                        if (task.sourceResultId == null) GenerationStage.Queued.name else GenerationStage.SourcePublished.name,
                    progress = if (task.sourceResultId == null) 0f else SourcePublishedProgress,
                    paused = false,
                    needsAttention = false,
                    schemeBlocked = false,
                    waitingForNetwork = false,
                    networkWaitReason = null,
                    errorCategory = null,
                    errorMessage = null,
                    updatedAtMs = timestamp,
                ),
            )
        }
        changedAttemptId?.let { attemptId ->
            diagnostics.record(
                SyncTextDiagnosticEventType.TaskStateChanged,
                taskId = taskId,
                attemptId = attemptId,
                outcome = GenerationTaskState.Waiting.name,
                reason = "SchemeChanged",
            )
        }
        scheduler.schedule()
    }

    suspend fun setPresentationMode(slotId: String, mode: TextPresentationMode) {
        dao.updateGeneratedSlotPresentationMode(slotId, mode.name, nowMs())
    }

    suspend fun loadPresentation(slotId: String): GeneratedTextPresentation? {
        val slot = dao.generatedSlot(slotId) ?: return null
        val entity = dao.currentGeneratedResult(slotId) ?: return null
        val source = revisionReader.read(entity.sourceRevisionId).toSubtitleCues()
        val target = entity.targetRevisionId?.let { revisionReader.read(it).toSubtitleCues() }
        return GeneratedTextPresentation(
            result = entity.toDomain(slot.presentationMode.enumValueOr(TextPresentationMode.TargetOnly)),
            sourceCues = source,
            targetCues = target,
        )
    }

    fun observePresentation(slotId: String) =
        dao.observeGeneratedSlot(slotId).map { slot ->
            if (slot == null) null else loadPresentation(slotId)
        }

    suspend fun currentSourceResult(contentId: String): GeneratedTextResult? {
        val slot = dao.generatedSlot(contentId, SourceSlotKey) ?: return null
        val result = dao.currentGeneratedResult(slot.slotId) ?: return null
        return result.toDomain(slot.presentationMode.enumValueOr(TextPresentationMode.TargetOnly))
    }

    suspend fun publishResult(
        taskId: String,
        sourceLanguage: String?,
        targetLanguage: String?,
        presentationMode: TextPresentationMode,
        sourceCues: List<SubtitleCue>,
        targetCues: List<SubtitleCue>?,
        usage: UsageRecord = UsageRecord(),
        sourceModel: String? = null,
        completeTask: Boolean,
        completeness: GeneratedResultCompleteness =
            if (completeTask) GeneratedResultCompleteness.Final else GeneratedResultCompleteness.Partial,
        coveredEndMs: Long? = sourceCues.maxOfOrNull(SubtitleCue::endMs),
        completedWindowCount: Int? = null,
        totalWindowCount: Int? = null,
        sourceRevisionId: String? = null,
        partialArtifactIds: List<String> = emptyList(),
    ): GeneratedTextResult {
        val taskBeforeWrite = requireNotNull(dao.task(taskId)) { "Task no longer exists" }
        val attempt = requireNotNull(dao.attempt(taskBeforeWrite.currentAttemptId))
        val normalizedSourceLanguage = LanguageTags.normalizeOrNull(sourceLanguage)
        val normalizedTargetLanguage = LanguageTags.normalizeOrNull(targetLanguage)
        require((normalizedTargetLanguage == null) == (targetCues == null))
        val sourceTimeline = TimelineAssembler.assembleFinal(sourceCues)
        val resolvedSourceRevision =
            sourceRevisionId?.let { revisionId ->
                requireNotNull(textStorage.revision(revisionId)) { "Source revision is unavailable" }.also {
                    require(it.contentId == taskBeforeWrite.contentId)
                    require(it.contentHash == canonicalContentHash(sourceTimeline)) {
                        "Source revision does not match the published cues"
                    }
                }
            } ?: if (completeness == GeneratedResultCompleteness.Partial) {
                require(partialArtifactIds.isNotEmpty()) { "Partial publication requires work-unit artifacts" }
                textStorage.createRevision(
                    contentId = taskBeforeWrite.contentId,
                    role = TimelineRole.Source,
                    completeness = TimelineCompleteness.Partial,
                    artifacts =
                        partialArtifactIds.map { artifactId ->
                            RevisionArtifactRole.AsrWorkUnit to
                                requireNotNull(textStorage.artifact(artifactId)) {
                                    "ASR work-unit artifact is unavailable"
                                }
                        },
                    contentHash = canonicalContentHash(sourceTimeline),
                    cueCount = sourceTimeline.cues.size,
                    coveredEndMs = coveredEndMs,
                )
            } else {
                val artifact = textStorage.publishTimelineArtifact(TextArtifactKind.FinalTimeline, sourceTimeline)
                textStorage.createRevision(
                    contentId = taskBeforeWrite.contentId,
                    role = TimelineRole.Source,
                    completeness = TimelineCompleteness.Final,
                    artifacts = listOf(RevisionArtifactRole.Timeline to artifact),
                    contentHash = canonicalContentHash(sourceTimeline),
                    cueCount = sourceTimeline.cues.size,
                    coveredEndMs = coveredEndMs,
                )
            }
        val targetRevision =
            targetCues?.let { cues ->
                val targetTimeline = CanonicalTextNormalizer.alignTranslation(sourceTimeline, cues)
                val artifact =
                    textStorage.publishTranslationDelta(
                        TranslationDelta(
                            sourceRevisionId = resolvedSourceRevision.revisionId,
                            sourceRevisionHash = resolvedSourceRevision.contentHash,
                            entries = targetTimeline.cues.map { TranslationDeltaEntry(it.cueId, it.text) },
                        ),
                    )
                textStorage.createRevision(
                    contentId = taskBeforeWrite.contentId,
                    role = TimelineRole.Target,
                    completeness = TimelineCompleteness.Final,
                    artifacts = listOf(RevisionArtifactRole.TranslationDelta to artifact),
                    contentHash = canonicalContentHash(targetTimeline),
                    cueCount = targetTimeline.cues.size,
                    coveredEndMs = targetTimeline.cues.maxOf(CanonicalTextCue::endMs),
                    sourceRevision = resolvedSourceRevision,
                )
            }
        val resultId = newId()
        var replaced: GeneratedTextResultEntity? = null
        val published =
            runCatching {
                database.withWriteTransaction {
                    val task = requireNotNull(dao.task(taskId)) { "Task no longer exists" }
                    if (
                        task.currentAttemptId != attempt.attemptId ||
                        task.paused ||
                        task.needsAttention ||
                        task.waitingForNetwork ||
                        task.isTerminal() ||
                        dao.queueControl()?.pauseAll == true
                    ) {
                        throw TaskPublicationDeferredException()
                    }
                    val targetKey = normalizedTargetLanguage ?: SourceSlotKey
                    val timestamp = nowMs()
                    val existingSlot = dao.generatedSlot(task.contentId, targetKey)
                    val slotId = existingSlot?.slotId ?: stableSlotId(task.contentId, targetKey)
                    val slot =
                        GeneratedTextSlotEntity(
                            slotId = slotId,
                            contentId = task.contentId,
                            targetKey = targetKey,
                            targetLanguage = normalizedTargetLanguage,
                            presentationMode = presentationMode.name,
                            currentResultId = resultId,
                            createdAtMs = existingSlot?.createdAtMs ?: timestamp,
                            updatedAtMs = timestamp,
                        )
                    if (existingSlot == null) dao.upsertGeneratedSlot(slot.copy(currentResultId = null))
                    val entity =
                        GeneratedTextResultEntity(
                            resultId = resultId,
                            slotId = slotId,
                            contentId = task.contentId,
                            taskId = taskId,
                            attemptId = attempt.attemptId,
                            sourceLanguage = normalizedSourceLanguage,
                            targetLanguage = normalizedTargetLanguage,
                            presentationMode = presentationMode.name,
                            sourceRevisionId = resolvedSourceRevision.revisionId,
                            targetRevisionId = targetRevision?.revisionId,
                            sourceSha256 = resolvedSourceRevision.contentHash,
                            targetSha256 = targetRevision?.contentHash,
                            cueCount = sourceTimeline.cues.size,
                            completeness = completeness.name,
                            coveredEndMs = coveredEndMs,
                            completedWindowCount = completedWindowCount,
                            totalWindowCount = totalWindowCount,
                            schemeId = attempt.schemeId,
                            model = if (normalizedTargetLanguage == null) sourceModel else attempt.translationModel,
                            policyId = normalizedTargetLanguage?.let { attempt.policyId },
                            policyVersion = normalizedTargetLanguage?.let { attempt.policyVersion },
                            policyRevision = normalizedTargetLanguage?.let { attempt.policyRevision },
                            systemPromptSha256 = normalizedTargetLanguage?.let { attempt.systemPromptSha256 },
                            stylePromptSha256 = normalizedTargetLanguage?.let { attempt.stylePromptSha256 },
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            totalTokens = usage.totalTokens,
                            costUsd = usage.costUsd,
                            createdAtMs = timestamp,
                        )
                    dao.insertGeneratedResult(entity)
                    dao.upsertGeneratedSlot(slot)
                    upsertGeneratedTrack(entity, slot)
                    activateGeneratedTrackWhenAllowed(task.contentId, slotId)
                    val replacementCandidate = existingSlot?.currentResultId?.let { dao.generatedResult(it) }
                    replaced =
                        replacementCandidate?.takeIf {
                            dao.activeSourceResultReferenceCount(it.resultId) == 0
                        }
                    replaced?.let { dao.deleteGeneratedResult(it.resultId) }
                    val sourceResultId = if (normalizedTargetLanguage == null) resultId else task.sourceResultId
                    dao.upsertAttempt(
                        attempt.copy(
                            sourceResultId = sourceResultId,
                            state = if (completeTask) GenerationTaskState.Completed.name else GenerationTaskState.Running.name,
                            finishedAtMs = timestamp.takeIf { completeTask },
                        ),
                    )
                    dao.upsertTask(
                        task.copy(
                            activeDedupeKey = if (completeTask) null else task.activeDedupeKey,
                            state = if (completeTask) GenerationTaskState.Completed.name else GenerationTaskState.Running.name,
                            stage = if (completeTask) GenerationStage.Completed.name else GenerationStage.SourcePublished.name,
                            progress = if (completeTask) 1f else SourcePublishedProgress,
                            sourceResultId = sourceResultId,
                            waitingForNetwork = false,
                            networkWaitReason = null,
                            errorCategory = null,
                            errorMessage = null,
                            updatedAtMs = timestamp,
                        ),
                    )
                    entity
                }
            }.getOrThrow()
        scheduler.schedule()
        val slot = requireNotNull(dao.generatedSlot(published.slotId))
        diagnostics.record(
            SyncTextDiagnosticEventType.ResultPublished,
            taskId = taskId,
            attemptId = attempt.attemptId,
            workUnitKind = "GeneratedResult",
            workUnitId = published.resultId,
            stage = if (completeTask) GenerationStage.Completed.name else GenerationStage.SourcePublished.name,
            outcome = completeness.name,
            reason = "cueCount=${published.cueCount}",
        )
        return published.toDomain(slot.presentationMode.enumValueOr(presentationMode))
    }

    internal suspend fun nextRunnableTask(): GenerationTaskEntity? {
        if (dao.queueControl()?.pauseAll == true) return null
        return dao.nextRunnableTask()
    }

    internal suspend fun markRunning(taskId: String, stage: GenerationStage, progress: Float) {
        val task = dao.task(taskId)
        val normalizedProgress = progress.coerceIn(0f, 1f)
        if (dao.markTaskRunning(taskId, stage.name, normalizedProgress, nowMs()) > 0) {
            val updated = dao.task(taskId)
            if (
                updated != null &&
                (task?.state != updated.state || task.stage != updated.stage || task.progress != updated.progress)
            ) {
                diagnostics.record(
                    SyncTextDiagnosticEventType.TaskStateChanged,
                    taskId = taskId,
                    attemptId = updated.currentAttemptId,
                    stage = updated.stage,
                    outcome = GenerationTaskState.Running.name,
                    reason = "progress=${(updated.progress * 1_000).toInt()}",
                )
            }
        }
    }

    internal suspend fun markWaitingForNetwork(taskId: String, reason: GenerationNetworkWaitReason?) {
        val task = dao.task(taskId)
        val changed = if (reason == null) {
            if (task?.waitingForNetwork == true) dao.clearTaskNetworkWait(taskId, nowMs()) else 0
        } else {
            if (task?.networkWaitReason != reason.name) {
                dao.markTaskWaitingForNetwork(taskId, reason.name, nowMs())
            } else {
                0
            }
        }
        if (changed > 0) {
            diagnostics.record(
                SyncTextDiagnosticEventType.NetworkWaitChanged,
                taskId = taskId,
                attemptId = task?.currentAttemptId,
                outcome = if (reason == null) "Cleared" else "Waiting",
                reason = reason?.name,
            )
        }
    }

    internal suspend fun blockTask(
        taskId: String,
        category: GenerationErrorCategory,
        message: String,
        blockScheme: Boolean,
    ) {
        var changedAttemptId: String? = null
        database.withWriteTransaction {
            val task = dao.task(taskId)?.takeUnless(GenerationTaskEntity::isTerminal) ?: return@withWriteTransaction
            val timestamp = nowMs()
            val changed = if (blockScheme) {
                dao.markSchemeTasksNeedsAttention(
                    schemeId = task.schemeId,
                    category = category.name,
                    message = message.take(MaxErrorMessageLength),
                    updatedAtMs = timestamp,
                )
            } else {
                dao.markTaskNeedsAttention(
                    taskId = taskId,
                    category = category.name,
                    message = message.take(MaxErrorMessageLength),
                    schemeBlocked = false,
                    updatedAtMs = timestamp,
                )
            }
            if (changed > 0) changedAttemptId = task.currentAttemptId
            Unit
        }
        changedAttemptId?.let { attemptId ->
            diagnostics.record(
                SyncTextDiagnosticEventType.TaskStateChanged,
                taskId = taskId,
                attemptId = attemptId,
                outcome = GenerationTaskState.NeedsAttention.name,
                reason = category.name,
            )
        }
        scheduler.schedule()
    }

    internal suspend fun taskSnapshot(taskId: String) = dao.taskSnapshot(taskId)

    internal suspend fun currentAttempt(taskId: String): GenerationAttemptEntity? =
        dao.task(taskId)?.currentAttemptId?.let { dao.attempt(it) }

    internal suspend fun credentialSecret(credentialId: String): String = credentials.get(credentialId)

    internal suspend fun isRunnable(taskId: String): Boolean {
        if (dao.queueControl()?.pauseAll == true) return false
        val task = dao.task(taskId) ?: return false
        return !task.paused && !task.needsAttention && !task.waitingForNetwork && !task.isTerminal()
    }

    internal suspend fun isTerminal(taskId: String): Boolean = dao.task(taskId)?.isTerminal() != false

    internal suspend fun meteredNetworkAllowed(taskId: String): Boolean =
        dao.task(taskId)?.meteredNetworkAllowed == true

    suspend fun wakeEligibleNetworkTasks(networkState: SyncTextNetworkState) {
        database.withWriteTransaction {
            dao.networkWaitingTasks().forEach { task ->
                val reason = networkState.waitReason(task.meteredNetworkAllowed)
                if (reason == null) {
                    if (dao.clearTaskNetworkWait(task.taskId, nowMs()) > 0) {
                        diagnostics.record(
                            SyncTextDiagnosticEventType.NetworkWaitChanged,
                            taskId = task.taskId,
                            attemptId = task.currentAttemptId,
                            outcome = "Cleared",
                        )
                    }
                } else if (task.networkWaitReason != reason.name) {
                    if (dao.markTaskWaitingForNetwork(task.taskId, reason.name, nowMs()) > 0) {
                        diagnostics.record(
                            SyncTextDiagnosticEventType.NetworkWaitChanged,
                            taskId = task.taskId,
                            attemptId = task.currentAttemptId,
                            outcome = "Waiting",
                            reason = reason.name,
                        )
                    }
                }
            }
        }
    }

    suspend fun hasRetryableNetworkWaitingTasks(): Boolean =
        dao.networkWaitingTasks().any {
            it.networkWaitReason != GenerationNetworkWaitReason.MeteredPermissionRequired.name
        }

    private suspend fun requireCredential(credentialId: String, intended: CredentialPurpose) {
        val metadata = requireNotNull(dao.credential(credentialId)) { "Credential does not exist" }
        val purpose = metadata.purpose.enumValueOr(CredentialPurpose.Shared)
        require(purpose == intended || purpose == CredentialPurpose.Shared) {
            "Credential is not enabled for ${intended.name.lowercase()}"
        }
        require(credentials.contains(credentialId)) { "Credential secret is unavailable" }
    }

    private suspend fun validateInput(request: GenerationRequest) {
        when (val input = request.input) {
            is GenerationInput.ExistingTrack ->
                require(contentDao.textTracks(request.contentId).any { it.trackId == input.trackId }) {
                    "Text track does not exist"
                }
            is GenerationInput.EmbeddedTrack ->
                require(
                    contentDao.textTracks(request.contentId).any {
                        it.trackId == input.trackId && it.embeddedTrackId == input.embeddedTrackId
                    },
                ) { "Embedded text track does not exist" }
            is GenerationInput.DiscoveredCandidate ->
                error("Discovered text candidate must be materialized before task submission")
            is GenerationInput.AudioTrack -> Unit
        }
    }

    private suspend fun GenerationRequest.generatedSourceResultId(): String? {
        val trackId = input.trackId ?: return null
        val track = contentDao.textTracks(contentId).firstOrNull { it.trackId == trackId } ?: return null
        val slotId = track.generatedSlotId ?: return null
        return dao.currentGeneratedResult(slotId)?.resultId
    }

    private suspend fun upsertGeneratedTrack(
        result: GeneratedTextResultEntity,
        slot: GeneratedTextSlotEntity,
    ) {
        val language = result.targetLanguage ?: result.sourceLanguage
        val displayLanguage = language ?: "source"
        val trackId = stableTimedTextTrackId(result.contentId, TimedTextOrigin.Generated, "generated:${slot.slotId}")
        contentDao.upsertTextTrack(
            TimedTextTrackEntity(
                trackId = trackId,
                contentId = result.contentId,
                origin = TimedTextOrigin.Generated.name,
                locatorIdentity = "generated:${slot.slotId}",
                revisionId = null,
                embeddedTrackId = null,
                displayName = "$displayLanguage.generated.vtt",
                mimeType = "text/vtt",
                format = "vtt",
                language = language,
                label =
                    when {
                        result.targetLanguage != null -> "生成译文 · ${result.targetLanguage}"
                        result.completeness == GeneratedResultCompleteness.Partial.name -> "生成原文 · 部分"
                        else -> "生成原文"
                    },
                matchBasis = TimedTextMatchBasis.GeneratedResult.name,
                readiness = TimedTextReadiness.Ready.name,
                failureMessage = null,
                generatedSlotId = slot.slotId,
            ),
        )
    }

    private suspend fun activateGeneratedTrackWhenAllowed(contentId: String, slotId: String) {
        val trackId = stableTimedTextTrackId(contentId, TimedTextOrigin.Generated, "generated:$slotId")
        val current = contentDao.textSelections(contentId).firstOrNull { it.slot == TextTrackSlot.Primary.name }
        if (current == null || current.mode == TextSelectionMode.Automatic.name) {
            contentDao.upsertTextSelection(
                TextSelectionEntity(
                    contentId = contentId,
                    slot = TextTrackSlot.Primary.name,
                    selectedTrackId = trackId,
                    mode = TextSelectionMode.Automatic.name,
                ),
            )
        }
    }

    private fun GenerationRequest.normalized(): GenerationRequest =
        copy(
            contentId = contentId.trim().also { require(it.isNotBlank()) },
            sourceLanguage = LanguageTags.normalizeOrNull(sourceLanguage),
            output =
                when (val value = output) {
                    GenerationOutput.SourceOnly -> value
                    is GenerationOutput.Translated ->
                        value.copy(targetLanguage = LanguageTags.normalize(value.targetLanguage))
                },
        )

    private fun GenerationRequest.toSnapshot(
        taskId: String,
        timestamp: Long,
        inputSourceResultId: String?,
    ): GenerationTaskSnapshotEntity {
        val translated = output as? GenerationOutput.Translated
        return GenerationTaskSnapshotEntity(
            taskId = taskId,
            inputKind = input::class.simpleName ?: error("Unknown generation input"),
            sourceTrackId = input.trackId,
            embeddedTrackId = (input as? GenerationInput.EmbeddedTrack)?.embeddedTrackId,
            audioTrackId = (input as? GenerationInput.AudioTrack)?.audioTrackId,
            inputSourceResultId = inputSourceResultId,
            sourceLanguage = sourceLanguage,
            targetLanguage = translated?.targetLanguage,
            presentationMode = translated?.presentationMode?.name ?: TextPresentationMode.TargetOnly.name,
            allowMeteredNetwork = allowMeteredNetwork,
            allowAsrFallback = allowAsrFallback,
            submittedAtMs = timestamp,
        )
    }

    private fun SyncTextSchemeEntity.toAttempt(
        taskId: String,
        attemptId: String,
        ordinal: Int,
        policy: TranslationPolicy?,
        sourceResultId: String?,
        timestamp: Long,
    ) =
        GenerationAttemptEntity(
            attemptId = attemptId,
            taskId = taskId,
            ordinal = ordinal,
            schemeId = schemeId,
            asrProfile = asrProfile,
            asrEndpoint = asrEndpoint,
            asrCredentialId = asrCredentialId,
            translationProtocol = translationProtocol,
            translationEndpoint = translationEndpoint,
            translationUserAgent = translationUserAgent,
            translationModel = translationModel,
            translationCredentialId = translationCredentialId,
            policyId = policy?.policyId ?: NoTranslationPolicyId,
            policyVersion = policy?.version ?: 1,
            policyRevision = policy?.originRevision ?: TranslationPolicyRegistry.OriginRevision,
            systemPromptSha256 = policy?.systemPromptSha256.orEmpty(),
            stylePromptSha256 = policy?.stylePromptSha256,
            requestSchemaVersion = policy?.requestSchemaVersion ?: 1,
            asrWindowMs = AsrWindowMs,
            asrOverlapMs = AsrOverlapMs,
            asrMaxEncodedBytes = AsrMaxEncodedBytes,
            sourceResultId = sourceResultId,
            state = GenerationTaskState.Waiting.name,
            createdAtMs = timestamp,
        )

    private fun SyncTextScheme.normalizedAndValidated(): SyncTextScheme {
        val normalizedAsrEndpoint = asrProfile.normalizedAsrEndpoint(asrEndpoint)
        val endpoint = translationEndpoint?.trim()?.removeSuffix("/")?.takeIf(String::isNotBlank)
        val model = translationModel?.trim()?.takeIf(String::isNotBlank)
        val credentialId = translationCredentialId?.trim()?.takeIf(String::isNotBlank)
        val configuredParts = listOf(endpoint, model, credentialId).count { it != null }
        require(configuredParts == 0 || configuredParts == 3) { "Translation configuration is incomplete" }
        endpoint?.let { normalizeHttpsEndpoint(it.replace("{model}", "model"), "Translation endpoint") }
        val userAgent = translationUserAgent?.trim()?.takeIf(String::isNotBlank)
        require(userAgent == null || userAgent.length <= MaxUserAgentLength) {
            "Translation User-Agent must not exceed $MaxUserAgentLength characters"
        }
        require(userAgent == null || userAgent.all { it.code in 0x20..0x7e }) {
            "Translation User-Agent must contain printable ASCII only"
        }
        return copy(
            name = name.trim().also { require(it.isNotBlank()) },
            asrEndpoint = normalizedAsrEndpoint,
            translationEndpoint = endpoint,
            translationUserAgent = userAgent,
            translationModel = model,
            translationCredentialId = credentialId,
            defaultTargetLanguage = LanguageTags.normalize(defaultTargetLanguage),
        )
    }

    companion object {
        private const val DefaultProbeTargetLanguage = "zh-CN"
        private const val SourceSlotKey = "@source"
        private const val NoTranslationPolicyId = "none.v1"
        private const val AsrWindowMs = 300_000L
        private const val AsrOverlapMs = 3_000L
        private const val AsrMaxEncodedBytes = 24L * 1024L * 1024L
        private const val SourcePublishedProgress = 0.55f
        private const val MaxErrorMessageLength = 600
        private const val MaxUserAgentLength = 256

        private fun stableSlotId(contentId: String, targetKey: String): String =
            "slot-${sha256("$contentId|$targetKey").take(32)}"

        private fun sourceDedupeKey(contentId: String): String =
            sha256("$contentId|${GenerationTaskKind.SourceGeneration.name}")

        private fun translationDedupeKey(contentId: String, sourceResultId: String, targetLanguage: String): String =
            sha256("$contentId|${GenerationTaskKind.TranslationDerivation.name}|$sourceResultId|$targetLanguage")

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

private fun SyncTextSchemeEntity.toDomain() =
    SyncTextScheme(
        schemeId = schemeId,
        name = name,
        asrProfile = AsrProfile.fromStoredValue(asrProfile),
        asrEndpoint = asrEndpoint,
        asrCredentialId = asrCredentialId,
        translationProtocol = translationProtocol.enumValueOr(TranslationProtocol.Responses),
        translationEndpoint = translationEndpoint,
        translationUserAgent = translationUserAgent,
        translationModel = translationModel,
        translationCredentialId = translationCredentialId,
        defaultTargetLanguage = defaultTargetLanguage,
        defaultPresentationMode = defaultPresentationMode.enumValueOr(TextPresentationMode.TargetOnly),
        isDefault = isDefault,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun SyncTextScheme.toConfig() =
    SyncTextConfig(
        configId = schemeId,
        asrEndpoint = asrEndpoint,
        asrProfile = asrProfile,
        asrCredentialId = asrCredentialId,
        translationProtocol = translationProtocol,
        translationEndpoint = translationEndpoint,
        translationModel = translationModel,
        translationCredentialId = translationCredentialId,
        targetLanguage = defaultTargetLanguage,
        presentationMode = defaultPresentationMode,
        translationUserAgent = translationUserAgent,
    )

private fun normalizeHttpsEndpoint(value: String, label: String): String {
    val endpoint = value.trim().removeSuffix("/")
    val uri = runCatching { URI(endpoint) }.getOrNull()
    require(
        uri?.scheme.equals("https", ignoreCase = true) &&
            !uri?.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null,
    ) {
        "$label must be HTTPS"
    }
    return endpoint
}

private fun AsrProfile.normalizedAsrEndpoint(value: String): String {
    val endpoint = normalizeHttpsEndpoint(value, "ASR endpoint")
    if (provider != AsrProvider.OpenAi) return endpoint
    require(
        endpoint == OpenAiAsrEngine.OpenAiBaseUrl ||
            endpoint == OpenAiAsrEngine.OpenAiTranscriptionUrl,
    ) {
        "OpenAI ASR endpoint must use https://api.openai.com/v1"
    }
    return endpoint
}

private fun SyncTextScheme.toEntity() =
    SyncTextSchemeEntity(
        schemeId = schemeId,
        name = name,
        asrProfile = asrProfile.name,
        asrEndpoint = asrEndpoint,
        asrCredentialId = asrCredentialId,
        translationProtocol = translationProtocol.name,
        translationEndpoint = translationEndpoint,
        translationUserAgent = translationUserAgent,
        translationModel = translationModel,
        translationCredentialId = translationCredentialId,
        defaultTargetLanguage = defaultTargetLanguage,
        defaultPresentationMode = defaultPresentationMode.name,
        isDefault = isDefault,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun CredentialMetadataEntity.toDomain() =
    CredentialMetadata(
        credentialId = credentialId,
        name = name,
        purpose = purpose.enumValueOr(CredentialPurpose.Shared),
        maskedValue = maskedValue,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun GenerationTaskEntity.toSummary(queuePosition: Int, pauseAll: Boolean) =
    GenerationTaskSummary(
        taskId = taskId,
        contentId = contentId,
        schemeId = schemeId,
        kind = kind.enumValueOr(GenerationTaskKind.SourceGeneration),
        queuePosition = queuePosition,
        state = state.enumValueOr(GenerationTaskState.NeedsAttention),
        stage = stage.enumValueOr(GenerationStage.Queued),
        progress = progress.coerceIn(0f, 1f),
        targetLanguage = targetLanguage,
        waitingForNetwork = waitingForNetwork,
        networkWaitReason = networkWaitReason?.enumValueOr(GenerationNetworkWaitReason.Unavailable),
        paused = paused || pauseAll,
        needsAttention = needsAttention,
        blockedByScheme = schemeBlocked,
        errorCategory = errorCategory?.enumValueOr(GenerationErrorCategory.Configuration),
        errorMessage = errorMessage,
    )

private fun GeneratedTextResultEntity.toDomain(presentationMode: TextPresentationMode) =
    GeneratedTextResult(
        resultId = resultId,
        slotId = slotId,
        contentId = contentId,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        sourceRevisionId = sourceRevisionId,
        targetRevisionId = targetRevisionId,
        sourceSha256 = sourceSha256,
        targetSha256 = targetSha256,
        cueCount = cueCount,
        completeness = completeness.enumValueOr(GeneratedResultCompleteness.Final),
        coveredEndMs = coveredEndMs,
        completedWindowCount = completedWindowCount,
        totalWindowCount = totalWindowCount,
        presentationMode = presentationMode,
        schemeId = schemeId,
        attemptId = attemptId,
        model = model,
        policyId = policyId,
        policyVersion = policyVersion,
        policyRevision = policyRevision,
        systemPromptSha256 = systemPromptSha256,
        stylePromptSha256 = stylePromptSha256,
        usage = UsageRecord(inputTokens, outputTokens, totalTokens, costUsd),
        createdAtMs = createdAtMs,
    )

private fun GenerationTaskEntity.isTerminal(): Boolean =
    state == GenerationTaskState.Completed.name ||
        state == GenerationTaskState.Deleted.name ||
        state == GenerationTaskState.Aborted.name

private inline fun <reified T : Enum<T>> String.enumValueOr(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
