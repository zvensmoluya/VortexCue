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
    private val assets: GeneratedTextAssetStore,
    private val credentials: CredentialStore,
    private val policies: TranslationPolicyRegistry,
    scope: CoroutineScope,
    private val scheduler: SyncTextScheduler = NoOpSyncTextScheduler,
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
        val resolvedKey = apiKey?.trim()?.takeIf(String::isNotBlank) ?: credentials.get(requireNotNull(credentialId))
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
        val resolvedKey = apiKey?.trim()?.takeIf(String::isNotBlank) ?: credentials.get(requireNotNull(credentialId))
        tester.testTranslation(protocol, endpoint, model, resolvedKey)
    }

    suspend fun testAsrConnection(
        profile: AsrProfile,
        endpoint: String,
        credentialId: String?,
        apiKey: String? = null,
    ) {
        val tester = requireNotNull(connectionTester) { "ASR connection test is unavailable" }
        val resolvedKey = apiKey?.trim()?.takeIf(String::isNotBlank) ?: credentials.get(requireNotNull(credentialId))
        tester.testAsr(profile, endpoint, resolvedKey)
    }

    suspend fun saveConfigWithSecrets(
        config: SyncTextConfig,
        asrSecret: String?,
        translationSecret: String?,
    ): SyncTextConfig {
        val normalizedAsrSecret = asrSecret?.trim()?.takeIf(String::isNotBlank)
        val normalizedTranslationSecret = translationSecret?.trim()?.takeIf(String::isNotBlank)
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
                            fallbackName = "OpenRouter",
                            purpose = CredentialPurpose.Shared,
                        )
                        sharedCredentialId to sharedCredentialId
                    }
                    else -> {
                        val asr =
                            saveInlineCredential(
                                existingId = "",
                                secret = normalizedAsrSecret,
                                name = "OpenRouter",
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
                    name = "OpenRouter",
                    purpose = CredentialPurpose.Asr,
                ) to saveInlineCredential(
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
        val id = credentialId ?: newId()
        val existing = dao.credential(id)
        val timestamp = nowMs()
        val previousSecret = existing?.let { runCatching { credentials.get(id) }.getOrNull() }
        credentials.put(id, secret)
        val entity =
            CredentialMetadataEntity(
                credentialId = id,
                name = name.trim().also { require(it.isNotBlank()) },
                purpose = purpose.name,
                maskedValue = maskCredential(secret),
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

    suspend fun deleteCredential(credentialId: String) {
        if (dao.credentialReferenceCount(credentialId) > 0) throw CredentialStillReferencedException()
        if (dao.deleteCredential(credentialId) > 0) credentials.delete(credentialId)
    }

    suspend fun saveScheme(scheme: SyncTextScheme): SyncTextScheme {
        val normalized = scheme.normalizedAndValidated()
        requireCredential(normalized.asrCredentialId, CredentialPurpose.Asr)
        requireCredential(normalized.translationCredentialId, CredentialPurpose.Translation)
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
        asrEndpoint: String = OpenRouterAsrEngine.OpenRouterBaseUrl,
        asrCredentialId: String,
        translationProtocol: TranslationProtocol,
        translationEndpoint: String,
        translationUserAgent: String? = null,
        translationModel: String,
        translationCredentialId: String,
        defaultTargetLanguage: String,
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
        val scheme = requireNotNull(dao.scheme(normalized.schemeId)) { "Sync text scheme does not exist" }
        requireNotNull(contentDao.content(normalized.contentId)) { "Content does not exist" }
        validateInput(normalized)
        val reusableSourceResultId = normalized.generatedSourceResultId()
        require(credentials.contains(scheme.asrCredentialId)) { "ASR credential is unavailable" }
        require(credentials.contains(scheme.translationCredentialId)) { "Translation credential is unavailable" }

        val targetLanguage = normalized.output.targetLanguageOrNull()
        val dedupeKey = dedupeKey(normalized.contentId, targetLanguage)
        dao.activeTaskByDedupeKey(dedupeKey)?.let { return it.toSummary(it.queuePosition, false) }

        val timestamp = nowMs()
        val taskId = newId()
        val attemptId = newId()
        val policy = targetLanguage?.let(policies::resolve)
        val task =
            GenerationTaskEntity(
                taskId = taskId,
                contentId = normalized.contentId,
                schemeId = scheme.schemeId,
                activeDedupeKey = dedupeKey,
                state = GenerationTaskState.Waiting.name,
                stage =
                    if (reusableSourceResultId == null) {
                        GenerationStage.Queued.name
                    } else {
                        GenerationStage.SourcePublished.name
                    },
                queuePosition = 0,
                progress = if (reusableSourceResultId == null) 0f else SourcePublishedProgress,
                targetLanguage = targetLanguage,
                waitingForNetwork = false,
                paused = false,
                needsAttention = false,
                schemeBlocked = false,
                currentAttemptId = attemptId,
                sourceResultId = reusableSourceResultId,
                createdAtMs = timestamp,
                updatedAtMs = timestamp,
            )
        database.withWriteTransaction {
            dao.activeTaskByDedupeKey(dedupeKey)?.let { return@withWriteTransaction }
            val queued = task.copy(queuePosition = dao.maxQueuePosition() + 1)
            dao.insertTask(queued)
            dao.insertTaskSnapshot(normalized.toSnapshot(taskId, timestamp))
            dao.insertAttempt(
                scheme.toAttempt(
                    taskId = taskId,
                    attemptId = attemptId,
                    ordinal = 0,
                    policy = policy,
                    sourceResultId = reusableSourceResultId,
                    timestamp = timestamp,
                ),
            )
        }
        val submitted = requireNotNull(dao.activeTaskByDedupeKey(dedupeKey))
        scheduler.schedule()
        return submitted.toSummary(submitted.queuePosition, false)
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
        dao.task(taskId)?.takeUnless(GenerationTaskEntity::isTerminal)?.let { task ->
            dao.upsertTask(
                task.copy(
                    state = GenerationTaskState.Paused.name,
                    paused = true,
                    waitingForNetwork = false,
                    updatedAtMs = nowMs(),
                ),
            )
            scheduler.schedule()
        }
    }

    suspend fun resumeTask(taskId: String) {
        database.withWriteTransaction {
            val task = dao.task(taskId)?.takeUnless(GenerationTaskEntity::isTerminal) ?: return@withWriteTransaction
            val timestamp = nowMs()
            val affected =
                if (task.schemeBlocked) dao.activeTasksForScheme(task.schemeId) else listOf(task)
            affected.forEach { blocked ->
                dao.upsertTask(
                    blocked.copy(
                        state = GenerationTaskState.Waiting.name,
                        paused = false,
                        needsAttention = false,
                        schemeBlocked = false,
                        waitingForNetwork = false,
                        errorCategory = null,
                        errorMessage = null,
                        updatedAtMs = timestamp,
                    ),
                )
            }
        }
        scheduler.schedule()
    }

    suspend fun setPauseAll(paused: Boolean) {
        dao.upsertQueueControl(SyncTextQueueControlEntity(pauseAll = paused, updatedAtMs = nowMs()))
        if (!paused) scheduler.schedule()
    }

    suspend fun deleteTask(taskId: String) {
        dao.task(taskId)?.takeUnless(GenerationTaskEntity::isTerminal)?.let { task ->
            dao.upsertTask(
                task.copy(
                    activeDedupeKey = null,
                    state = GenerationTaskState.Deleted.name,
                    paused = true,
                    waitingForNetwork = false,
                    needsAttention = false,
                    schemeBlocked = false,
                    updatedAtMs = nowMs(),
                ),
            )
        }
        scheduler.schedule()
    }

    suspend fun cancelForContent(contentId: String) {
        dao.activeTasksForContent(contentId).forEach { deleteTask(it.taskId) }
    }

    suspend fun retryForContent(contentId: String) {
        dao.activeTasksForContent(contentId).forEach { resumeTask(it.taskId) }
    }

    suspend fun changeScheme(taskId: String, schemeId: String) {
        val scheme = requireNotNull(dao.scheme(schemeId)) { "Sync text scheme does not exist" }
        database.withWriteTransaction {
            val task = dao.task(taskId)?.takeUnless(GenerationTaskEntity::isTerminal) ?: return@withWriteTransaction
            val snapshot = requireNotNull(dao.taskSnapshot(taskId))
            val attempts = dao.attemptsForTask(taskId)
            val previous = requireNotNull(attempts.lastOrNull())
            val target = snapshot.targetLanguage
            val policy = target?.let(policies::resolve)
            val attemptId = newId()
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
                    errorCategory = null,
                    errorMessage = null,
                    updatedAtMs = timestamp,
                ),
            )
        }
        scheduler.schedule()
    }

    suspend fun setPresentationMode(slotId: String, mode: TextPresentationMode) {
        val slot = dao.generatedSlot(slotId) ?: return
        dao.upsertGeneratedSlot(slot.copy(presentationMode = mode.name, updatedAtMs = nowMs()))
    }

    suspend fun loadPresentation(slotId: String): GeneratedTextPresentation? {
        val slot = dao.generatedSlot(slotId) ?: return null
        val entity = dao.currentGeneratedResult(slotId) ?: return null
        val source = assets.readTextAsset(entity.sourceAssetFileName)
        val target = entity.targetAssetFileName?.let { assets.readTextAsset(it) }
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
    ): GeneratedTextResult {
        val taskBeforeWrite = requireNotNull(dao.task(taskId)) { "Task no longer exists" }
        val attempt = requireNotNull(dao.attempt(taskBeforeWrite.currentAttemptId))
        val normalizedSourceLanguage = LanguageTags.normalizeOrNull(sourceLanguage)
        val normalizedTargetLanguage = LanguageTags.normalizeOrNull(targetLanguage)
        require((normalizedTargetLanguage == null) == (targetCues == null))
        val resultId = newId()
        val stored = assets.writeResult(resultId, sourceCues, targetCues)
        var replaced: GeneratedTextResultEntity? = null
        val published =
            runCatching {
                database.withWriteTransaction {
                    val task = requireNotNull(dao.task(taskId)) { "Task no longer exists" }
                    if (
                        task.currentAttemptId != attempt.attemptId ||
                        task.paused ||
                        task.needsAttention ||
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
                            sourceAssetFileName = stored.source.fileName,
                            targetAssetFileName = stored.target?.fileName,
                            sourceSha256 = stored.source.sha256,
                            targetSha256 = stored.target?.sha256,
                            cueCount = stored.cueCount,
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
                            errorCategory = null,
                            errorMessage = null,
                            updatedAtMs = timestamp,
                        ),
                    )
                    entity
                }
            }.getOrElse { error ->
                assets.delete(stored.source.fileName)
                stored.target?.let { assets.delete(it.fileName) }
                throw error
            }
        replaced?.let { old ->
            assets.delete(old.sourceAssetFileName)
            old.targetAssetFileName?.let { assets.delete(it) }
        }
        scheduler.schedule()
        val slot = requireNotNull(dao.generatedSlot(published.slotId))
        return published.toDomain(slot.presentationMode.enumValueOr(presentationMode))
    }

    internal suspend fun nextRunnableTask(): GenerationTaskEntity? {
        if (dao.queueControl()?.pauseAll == true) return null
        return dao.nextRunnableTask()
    }

    internal suspend fun markRunning(taskId: String, stage: GenerationStage, progress: Float) {
        dao.task(taskId)?.let { task ->
            if (!task.paused && !task.needsAttention && !task.isTerminal()) {
                dao.upsertTask(
                    task.copy(
                        state = GenerationTaskState.Running.name,
                        stage = stage.name,
                        progress = progress.coerceIn(0f, 1f),
                        waitingForNetwork = false,
                        updatedAtMs = nowMs(),
                    ),
                )
            }
        }
    }

    internal suspend fun markWaitingForNetwork(taskId: String, waiting: Boolean) {
        dao.task(taskId)?.let { task ->
            if (!task.isTerminal()) dao.upsertTask(task.copy(waitingForNetwork = waiting, updatedAtMs = nowMs()))
        }
    }

    internal suspend fun blockTask(
        taskId: String,
        category: GenerationErrorCategory,
        message: String,
        blockScheme: Boolean,
    ) {
        database.withWriteTransaction {
            val task = dao.task(taskId) ?: return@withWriteTransaction
            val affected = if (blockScheme) dao.activeTasksForScheme(task.schemeId) else listOf(task)
            val timestamp = nowMs()
            affected.forEach { item ->
                dao.upsertTask(
                    item.copy(
                        state = GenerationTaskState.NeedsAttention.name,
                        needsAttention = true,
                        schemeBlocked = blockScheme,
                        waitingForNetwork = false,
                        errorCategory = category.name,
                        errorMessage = message.take(MaxErrorMessageLength),
                        updatedAtMs = timestamp,
                    ),
                )
            }
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
        return !task.paused && !task.needsAttention && !task.isTerminal()
    }

    internal suspend fun isTerminal(taskId: String): Boolean = dao.task(taskId)?.isTerminal() != false

    suspend fun wakeEligibleNetworkTasks(networkState: SyncTextNetworkState) {
        database.withWriteTransaction {
            dao.networkWaitingTasks().forEach { task ->
                val snapshot = dao.taskSnapshot(task.taskId) ?: return@forEach
                if (networkState.permits(snapshot.allowMeteredNetwork)) {
                    dao.upsertTask(task.copy(waitingForNetwork = false, updatedAtMs = nowMs()))
                }
            }
        }
    }

    suspend fun hasNetworkWaitingTasks(): Boolean = dao.networkWaitingTasks().isNotEmpty()

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
                uriValue = null,
                embeddedTrackId = null,
                displayName = "$displayLanguage.generated.vtt",
                mimeType = "text/vtt",
                cachedFileName = null,
                format = "vtt",
                language = language,
                label = if (result.targetLanguage == null) "生成原文" else "生成译文 · ${result.targetLanguage}",
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

    private fun GenerationRequest.toSnapshot(taskId: String, timestamp: Long): GenerationTaskSnapshotEntity {
        val translated = output as? GenerationOutput.Translated
        return GenerationTaskSnapshotEntity(
            taskId = taskId,
            inputKind = input::class.simpleName ?: error("Unknown generation input"),
            sourceTrackId = input.trackId,
            embeddedTrackId = (input as? GenerationInput.EmbeddedTrack)?.embeddedTrackId,
            audioTrackId = (input as? GenerationInput.AudioTrack)?.audioTrackId,
            sourceLanguage = sourceLanguage,
            targetLanguage = translated?.targetLanguage,
            presentationMode = translated?.presentationMode?.name ?: TextPresentationMode.TargetOnly.name,
            allowMeteredNetwork = allowMeteredNetwork,
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
        val normalizedAsrEndpoint = normalizeHttpsEndpoint(asrEndpoint, "ASR endpoint")
        val endpoint = translationEndpoint.trim().removeSuffix("/")
        val uri = runCatching { URI(endpoint.replace("{model}", "model")) }.getOrNull()
        require(
            uri?.scheme.equals("https", ignoreCase = true) &&
                !uri?.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) {
            "Translation endpoint must be HTTPS"
        }
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
            translationModel = translationModel.trim().also { require(it.isNotBlank()) },
            defaultTargetLanguage = LanguageTags.normalize(defaultTargetLanguage),
        )
    }

    companion object {
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

        private fun dedupeKey(contentId: String, targetLanguage: String?): String =
            sha256("$contentId|${targetLanguage ?: SourceSlotKey}")

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
        asrProfile = asrProfile.enumValueOr(AsrProfile.OpenRouterWhisperV1),
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
        queuePosition = queuePosition,
        state = state.enumValueOr(GenerationTaskState.NeedsAttention),
        stage = stage.enumValueOr(GenerationStage.Queued),
        progress = progress.coerceIn(0f, 1f),
        targetLanguage = targetLanguage,
        waitingForNetwork = waitingForNetwork,
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
        sourceAssetFileName = sourceAssetFileName,
        targetAssetFileName = targetAssetFileName,
        sourceSha256 = sourceSha256,
        targetSha256 = targetSha256,
        cueCount = cueCount,
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
    state == GenerationTaskState.Completed.name || state == GenerationTaskState.Deleted.name

private inline fun <reified T : Enum<T>> String.enumValueOr(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
