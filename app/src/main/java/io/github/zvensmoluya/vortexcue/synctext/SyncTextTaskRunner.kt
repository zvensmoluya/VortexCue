package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.room3.withWriteTransaction
import io.github.zvensmoluya.vortexcue.data.db.AsrWindowEntity
import io.github.zvensmoluya.vortexcue.data.db.GeneratedTextResultEntity
import io.github.zvensmoluya.vortexcue.data.db.GenerationAttemptEntity
import io.github.zvensmoluya.vortexcue.data.db.GenerationTaskEntity
import io.github.zvensmoluya.vortexcue.data.db.GenerationTaskSnapshotEntity
import io.github.zvensmoluya.vortexcue.data.db.RequestAttemptEntity
import io.github.zvensmoluya.vortexcue.data.db.SyncTextRunnerLeaseEntity
import io.github.zvensmoluya.vortexcue.data.db.TranslationBatchEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.NormalizedSubtitleWriter
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.WebVttParser
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

interface SyncTextNetworkState {
    fun permits(allowMetered: Boolean): Boolean
}

class AndroidSyncTextNetworkState(context: Context) : SyncTextNetworkState {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun permits(allowMetered: Boolean): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return allowMetered || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

class SyncTextTaskRunner(
    private val database: VortexDatabase,
    private val repository: SyncTextRepository,
    private val policies: TranslationPolicyRegistry,
    private val assets: GeneratedTextAssetStore,
    private val sourceText: SourceTextMaterializer,
    private val audio: AudioWindowExtractor,
    private val spool: BoundedPcmSpool,
    private val asr: AsrEngine,
    private val translation: TranslationNetworkGateway,
    private val networkState: SyncTextNetworkState,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.syncTextDao()
    private val contentDao = database.contentDao()

    suspend fun runUntilIdle(): Boolean {
        val ownerId = newId()
        val lease = RoomRunnerLease(dao, ownerId, nowMs)
        if (!lease.acquire()) return false
        return try {
            dao.recoverInterruptedTasks()
            coroutineScope {
                val parent = currentCoroutineContext()[Job] ?: error("Runner has no Job")
                val heartbeat =
                    launch {
                        while (isActive) {
                            delay(LeaseHeartbeatMs)
                            if (!lease.renew()) {
                                parent.cancel(LeaseLostException())
                                return@launch
                            }
                        }
                    }
                try {
                    while (currentCoroutineContext().isActive) {
                        val task = repository.nextRunnableTask() ?: break
                        processTask(task)
                    }
                } finally {
                    heartbeat.cancel()
                }
            }
            true
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    private suspend fun processTask(task: GenerationTaskEntity) {
        try {
            val snapshot = requireNotNull(repository.taskSnapshot(task.taskId))
            if (!networkState.permits(snapshot.allowMeteredNetwork)) {
                repository.markWaitingForNetwork(task.taskId, true)
                return
            }
            repository.markWaitingForNetwork(task.taskId, false)
            ensureRunnable(task.taskId)
            val attempt = requireNotNull(repository.currentAttempt(task.taskId))
            val source = resolveSource(task, snapshot, attempt)
            val targetLanguage = snapshot.targetLanguage
            if (targetLanguage == null) {
                if (repository.isTerminal(task.taskId)) return
                ensureRunnable(task.taskId)
                repository.publishResult(
                    taskId = task.taskId,
                    sourceLanguage = snapshot.sourceLanguage ?: source.language,
                    targetLanguage = null,
                    presentationMode = TextPresentationMode.TargetOnly,
                    sourceCues = source.cues,
                    targetCues = null,
                    usage = source.usage,
                    sourceModel = null,
                    completeTask = true,
                )
                return
            }
            ensureRunnable(task.taskId)
            translateAndPublish(task, snapshot, requireNotNull(repository.currentAttempt(task.taskId)), source, targetLanguage)
        } catch (paused: TaskDeferredException) {
            Unit
        } catch (deferred: TaskPublicationDeferredException) {
            Unit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TranslationProtocolException) {
            repository.blockTask(
                task.taskId,
                error.category,
                error.message ?: "Translation request failed",
                blockScheme = error.category.blocksScheme(),
            )
        } catch (error: TranslationOutputException) {
            repository.blockTask(
                task.taskId,
                GenerationErrorCategory.InvalidResponse,
                "Translation output failed strict cue validation",
                blockScheme = true,
            )
        } catch (error: AsrException) {
            repository.blockTask(
                task.taskId,
                error.category,
                error.message ?: "Speech recognition failed",
                blockScheme = error.category.blocksScheme(),
            )
        } catch (error: PolicyIntegrityException) {
            repository.blockTask(task.taskId, GenerationErrorCategory.Policy, requireNotNull(error.message), true)
        } catch (error: CredentialUnavailableException) {
            repository.blockTask(task.taskId, GenerationErrorCategory.Authentication, requireNotNull(error.message), true)
        } catch (error: AudioTrackSelectionRequiredException) {
            repository.blockTask(
                task.taskId,
                GenerationErrorCategory.Media,
                "媒体包含多个音轨，请明确选择后再继续。",
                false,
            )
        } catch (error: EmbeddedTextMaterializationUnsupportedException) {
            repository.blockTask(
                task.taskId,
                GenerationErrorCategory.Media,
                "当前内嵌文字格式无法物化，请改用音轨 ASR。",
                false,
            )
        } catch (error: AudioDecodeException) {
            repository.blockTask(task.taskId, GenerationErrorCategory.Media, requireNotNull(error.message), false)
        } catch (error: IOException) {
            repository.blockTask(
                task.taskId,
                GenerationErrorCategory.Storage,
                error.message ?: "Sync text asset could not be read or written",
                false,
            )
        } catch (error: IllegalArgumentException) {
            repository.blockTask(
                task.taskId,
                GenerationErrorCategory.Configuration,
                error.message ?: "Sync text task configuration is invalid",
                true,
            )
        }
    }

    private suspend fun resolveSource(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
    ): SourceWork {
        attempt.sourceResultId?.let { resultId ->
            loadSourceResult(resultId)?.let { return it }
        }
        task.sourceResultId?.let { resultId ->
            loadSourceResult(resultId)?.let { return it }
        }
        return if (snapshot.sourceTrackId != null) {
            repository.markRunning(task.taskId, GenerationStage.MaterializingText, 0.08f)
            ensureRunnable(task.taskId)
            sourceText.materialize(task.contentId, snapshot).let { materialized ->
                SourceWork(
                    cues = materialized.cues,
                    language = snapshot.sourceLanguage ?: materialized.language,
                    usage = UsageRecord(),
                )
            }
        } else {
            performAsr(task, snapshot, attempt)
        }
    }

    private suspend fun loadSourceResult(resultId: String): SourceWork? {
        val result = dao.generatedResult(resultId) ?: return null
        return SourceWork(
            cues = assets.readTextAsset(result.sourceAssetFileName),
            language = result.sourceLanguage,
            usage = UsageRecord(result.inputTokens, result.outputTokens, result.totalTokens, result.costUsd),
            resultId = resultId,
        )
    }

    private suspend fun performAsr(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
    ): SourceWork {
        repository.markRunning(task.taskId, GenerationStage.DecodingAudio, 0.05f)
        val activeLocatorId = contentDao.activeLocator(task.contentId)?.locatorId
            ?: throw AudioDecodeException("Content has no active media locator")
        val media = contentDao.locator(activeLocatorId)
            ?: throw AudioDecodeException("Active media locator is unavailable")
        val mediaUri = Uri.parse(media.uriValue)
        val durationMs =
            media.durationMs.takeIf { it > 0L }
                ?: audio.inspect(mediaUri).mapNotNull(AudioTrackInfo::durationMs).maxOrNull()
                ?: throw AudioDecodeException("Media duration is unavailable")
        val planner =
            FixedAsrWindowPlanner(
                targetUploadMs = attempt.asrWindowMs,
                overlapMs = attempt.asrOverlapMs,
                maxEncodedBytes = attempt.asrMaxEncodedBytes,
            )
        var entities = dao.asrWindows(attempt.attemptId)
        if (entities.isEmpty()) {
            entities =
                planner.plan(durationMs).map { plan ->
                    plan.copy(windowId = "${attempt.attemptId}-${plan.windowId}").toEntity(attempt.attemptId)
                }
            dao.upsertAsrWindows(entities)
        }
        val roots = entities.filter { it.parentWindowId == null }
        val semaphore = Semaphore(AsrParallelism)
        val completed = AtomicInteger(0)
        val transcripts =
            coroutineScope {
                roots.map { entity ->
                    async {
                        semaphore.acquire()
                        try {
                            processAsrWindowTree(task, snapshot, attempt, mediaUri, durationMs, planner, entity)
                        } finally {
                            semaphore.release()
                            val count = completed.incrementAndGet()
                            repository.markRunning(
                                task.taskId,
                                GenerationStage.RecognizingSpeech,
                                0.10f + 0.40f * count / roots.size.coerceAtLeast(1),
                            )
                        }
                    }
                }.awaitAll().flatten()
            }
        ensureRunnable(task.taskId)
        val tokens = transcripts.flatMap { transcript -> transcript.cues.map { it.toTimestampedToken() } }
        val merged = AsrTimelineMerger.mergeSegments(tokens)
        if (merged.isEmpty()) throw AsrException(GenerationErrorCategory.Timeline, false, false, message = "ASR produced no owned cues")
        val language =
            snapshot.sourceLanguage
                ?: transcripts.mapNotNull(WindowTranscript::language).groupingBy { it.lowercase() }.eachCount()
                    .maxByOrNull { it.value }?.key
        val usage = transcripts.map(WindowTranscript::usage).sumRealUsage()
        val sourceResult =
            repository.publishResult(
                taskId = task.taskId,
                sourceLanguage = language,
                targetLanguage = null,
                presentationMode = TextPresentationMode.TargetOnly,
                sourceCues = merged,
                targetCues = null,
                usage = usage,
                sourceModel = attempt.asrProfile.enumValueOr(AsrProfile.OpenRouterWhisperV1).modelId,
                completeTask = snapshot.targetLanguage == null,
            )
        return SourceWork(merged, language, usage, sourceResult.resultId)
    }

    private suspend fun processAsrWindowTree(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
        mediaUri: Uri,
        mediaDurationMs: Long,
        planner: FixedAsrWindowPlanner,
        entity: AsrWindowEntity,
    ): List<WindowTranscript> {
        ensureRunnable(task.taskId)
        if (entity.state == CompletedState && entity.transcriptAssetFileName != null) {
            return listOf(
                WindowTranscript(
                    cues = WebVttParser.parse(assets.readCheckpoint(entity.transcriptAssetFileName)),
                    language = entity.detectedLanguage,
                    usage = UsageRecord(entity.inputTokens, entity.outputTokens, entity.totalTokens, entity.costUsd),
                ),
            )
        }
        if (entity.state == SplitState) {
            val children = dao.asrWindows(attempt.attemptId).filter { it.parentWindowId == entity.windowId }
            return children.sortedBy(AsrWindowEntity::ordinal).flatMap { child ->
                processAsrWindowTree(task, snapshot, attempt, mediaUri, mediaDurationMs, planner, child)
            }
        }

        val plan = entity.toPlan(mediaDurationMs)
        val wav = spool.reserve(task.taskId, plan.windowId)
        var finalFailure: AsrException? = null
        try {
            audio.extract(mediaUri, snapshot.audioTrackId, plan, wav)
            val key = repository.credentialSecret(attempt.asrCredentialId)
            for (index in 0 until MaxAsrAttempts) {
                ensureRunnable(task.taskId)
                repository.markRunning(task.taskId, GenerationStage.RecognizingSpeech, 0.10f)
                repository.markWaitingForNetwork(task.taskId, false)
                try {
                    val result =
                        asr.transcribe(
                            AsrRequest(
                                profile = attempt.asrProfile.enumValueOr(AsrProfile.OpenRouterWhisperV1),
                                apiKey = key,
                                wavFile = wav,
                                window = plan,
                                sourceLanguage = snapshot.sourceLanguage,
                            ),
                        )
                    recordAsrAttempt(attempt, result, null)
                    val cues =
                        if (attempt.asrProfile == AsrProfile.OpenRouterGrokV1.name) {
                            AsrTimelineMerger.wordsToCues(result.segments)
                        } else {
                            AsrTimelineMerger.mergeSegments(result.segments)
                        }
                    val checkpointName = assets.writeCheckpoint("${plan.windowId}.vtt", NormalizedSubtitleWriter.encode(cues))
                    dao.upsertAsrWindow(
                        entity.copy(
                            state = CompletedState,
                            encodedBytes = wav.length(),
                            transcriptAssetFileName = checkpointName,
                            errorMessage = null,
                            detectedLanguage = result.detectedLanguage,
                            inputTokens = result.usage.inputTokens,
                            outputTokens = result.usage.outputTokens,
                            totalTokens = result.usage.totalTokens,
                            costUsd = result.usage.costUsd,
                        ),
                    )
                    return listOf(WindowTranscript(cues, result.detectedLanguage, result.usage))
                } catch (error: AsrException) {
                    finalFailure = error
                    recordAsrAttempt(attempt, null, error)
                    if (!error.retryable || index == MaxAsrAttempts - 1) break
                }
            }
        } finally {
            withContext(NonCancellable) { spool.release(wav) }
        }

        val failure = requireNotNull(finalFailure)
        if (failure.shouldSplit) {
            val nextOrdinal = dao.nextAsrWindowOrdinal(attempt.attemptId)
            val split = runCatching { planner.split(plan, mediaDurationMs, nextOrdinal) }.getOrNull()
            if (split != null) {
                val children = listOf(split.first, split.second).map { it.toEntity(attempt.attemptId) }
                database.withWriteTransaction {
                    dao.upsertAsrWindow(entity.copy(state = SplitState, errorMessage = failure.message?.take(600)))
                    dao.upsertAsrWindows(children)
                }
                return children.flatMap { child ->
                    processAsrWindowTree(task, snapshot, attempt, mediaUri, mediaDurationMs, planner, child)
                }
            }
        }
        dao.upsertAsrWindow(entity.copy(state = FailedState, errorMessage = failure.message?.take(600)))
        throw failure
    }

    private suspend fun translateAndPublish(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
        source: SourceWork,
        targetLanguage: String,
    ) {
        val policy = policies.resolve(targetLanguage)
        verifyPolicySnapshot(attempt, policy)
        val normalizedSource = CueIdNormalizer.normalize(source.cues)
        val planner = TranslationBatchPlanner()
        val plans = planner.plan(normalizedSource, policy.systemPrompt + policy.stylePrompt.orEmpty())
        var entities = dao.translationBatches(attempt.attemptId)
        if (entities.isEmpty()) {
            entities = plans.map { plan -> plan.toEntity(attempt.attemptId, "${attempt.attemptId}-batch-${plan.ordinal}") }
            dao.upsertTranslationBatches(entities)
        }
        val initialByRange = entities.filter { it.parentBatchId == null }.associateBy { it.firstCueIndex to it.lastCueIndex }
        val semaphore = Semaphore(TranslationParallelism)
        val completed = AtomicInteger(0)
        repository.markRunning(task.taskId, GenerationStage.Translating, SourceProgress)
        val outputs =
            coroutineScope {
                plans.map { plan ->
                    async {
                        semaphore.acquire()
                        try {
                            val entity = initialByRange[plan.firstCueIndex to plan.lastCueIndex]
                                ?: plan.toEntity(attempt.attemptId, newId()).also { dao.upsertTranslationBatch(it) }
                            processTranslationBatch(
                                task,
                                snapshot,
                                attempt,
                                policy,
                                snapshot.sourceLanguage ?: source.language,
                                normalizedSource,
                                plan,
                                entity,
                            )
                        } finally {
                            semaphore.release()
                            val count = completed.incrementAndGet()
                            repository.markRunning(
                                task.taskId,
                                GenerationStage.Translating,
                                SourceProgress + TranslationProgressRange * count / plans.size.coerceAtLeast(1),
                            )
                        }
                    }
                }.awaitAll().flatten()
            }
        val translatedById = outputs.flatMap(BatchWork::cues).associateBy { requireNotNull(it.stableId) }
        val target =
            normalizedSource.map { cue ->
                requireNotNull(translatedById[cue.stableId]) { "Translated cue is missing after batch merge" }
                    .copy(startMs = cue.startMs, endMs = cue.endMs, stableId = cue.stableId)
            }
        ensureRunnable(task.taskId)
        repository.markRunning(task.taskId, GenerationStage.Publishing, 0.98f)
        repository.publishResult(
            taskId = task.taskId,
            sourceLanguage = snapshot.sourceLanguage ?: source.language,
            targetLanguage = targetLanguage,
            presentationMode = snapshot.presentationMode.enumValueOr(TextPresentationMode.TargetOnly),
            sourceCues = normalizedSource,
            targetCues = target,
            usage = (outputs.map(BatchWork::usage) + source.usage).sumRealUsage(),
            completeTask = true,
        )
    }

    private suspend fun processTranslationBatch(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
        policy: TranslationPolicy,
        sourceLanguage: String?,
        allSource: List<SubtitleCue>,
        plan: TranslationBatchPlan,
        entity: TranslationBatchEntity,
    ): List<BatchWork> {
        ensureRunnable(task.taskId)
        if (entity.state == CompletedState && entity.resultAssetFileName != null) {
            return listOf(
                BatchWork(
                    cues = WebVttParser.parse(assets.readCheckpoint(entity.resultAssetFileName)),
                    usage = entity.usage(),
                ),
            )
        }
        if (entity.state == SplitState) {
            val children = dao.translationBatches(attempt.attemptId).filter { it.parentBatchId == entity.batchId }
            return children.sortedBy(TranslationBatchEntity::ordinal).flatMap { child ->
                val childPlan = planForRange(allSource, child.firstCueIndex, child.lastCueIndex, child.ordinal)
                processTranslationBatch(task, snapshot, attempt, policy, sourceLanguage, allSource, childPlan, child)
            }
        }

        val key = repository.credentialSecret(attempt.translationCredentialId)
        val protocol = attempt.translationProtocol.enumValueOr(TranslationProtocol.Responses)
        val batchExecutor =
            TranslationBatchExecutor { systemPrompt, userPrompt ->
                translation.translate(
                    protocol = protocol,
                    request =
                        TranslationProviderRequest(
                            endpoint = attempt.translationEndpoint,
                            model = attempt.translationModel,
                            apiKey = key,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                        ),
                    onAttempt = { networkAttempt -> recordTranslationAttempt(attempt, protocol, networkAttempt) },
                )
            }
        try {
            val output =
                batchExecutor.execute(
                    plan = plan,
                    policy = policy,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = requireNotNull(snapshot.targetLanguage),
                )
            val checkpointName =
                assets.writeCheckpoint("${entity.batchId}.vtt", NormalizedSubtitleWriter.encode(output.targetCues))
            dao.upsertTranslationBatch(
                entity.copy(
                    state = CompletedState,
                    formatRecoveryCount = output.formatRecoveryCount,
                    repairCount = output.repairCount,
                    resultAssetFileName = checkpointName,
                    inputTokens = output.usage.inputTokens,
                    outputTokens = output.usage.outputTokens,
                    totalTokens = output.usage.totalTokens,
                    costUsd = output.usage.costUsd,
                    errorMessage = null,
                ),
            )
            return listOf(BatchWork(output.targetCues, output.usage))
        } catch (error: TranslationProtocolException) {
            if (!error.capacityExceeded || plan.translateOnly.size <= 1) {
                dao.upsertTranslationBatch(entity.copy(state = FailedState, errorMessage = error.message?.take(600)))
                throw error
            }
            val (leftPlan, rightPlan) = TranslationBatchPlanner().split(plan)
            val children =
                database.withWriteTransaction {
                    val firstOrdinal = dao.nextTranslationBatchOrdinal(attempt.attemptId)
                    val left = leftPlan.toEntity(attempt.attemptId, newId(), entity.batchId, firstOrdinal)
                    val right = rightPlan.toEntity(attempt.attemptId, newId(), entity.batchId, firstOrdinal + 1)
                    dao.upsertTranslationBatch(entity.copy(state = SplitState, errorMessage = error.message?.take(600)))
                    dao.upsertTranslationBatches(listOf(left, right))
                    listOf(leftPlan to left, rightPlan to right)
                }
            return children.flatMap { (childPlan, childEntity) ->
                processTranslationBatch(task, snapshot, attempt, policy, sourceLanguage, allSource, childPlan, childEntity)
            }
        }
    }

    private suspend fun recordTranslationAttempt(
        attempt: GenerationAttemptEntity,
        protocol: TranslationProtocol,
        networkAttempt: TranslationNetworkAttempt,
    ) {
        val response = networkAttempt.response
        val error = networkAttempt.error
        val raw = response?.rawResponse ?: error?.rawResponse
        val requestAttemptId = newId()
        val rawFile = raw?.let { assets.writeRawResponse("$requestAttemptId.json", it) }
        val timestamp = nowMs()
        database.withWriteTransaction {
            val sequence = dao.nextRequestSequence(attempt.attemptId)
            dao.upsertRequestAttempt(
                RequestAttemptEntity(
                    requestAttemptId = requestAttemptId,
                    attemptId = attempt.attemptId,
                    sequence = sequence,
                    stage = GenerationStage.Translating.name,
                    provider = protocol.name,
                    requestBytes = response?.requestBytes,
                    responseBytes = response?.responseBytes ?: raw?.size?.toLong(),
                    httpStatus = response?.httpStatus ?: error?.httpStatus,
                    inputTokens = response?.usage?.inputTokens,
                    outputTokens = response?.usage?.outputTokens,
                    totalTokens = response?.usage?.totalTokens,
                    costUsd = response?.usage?.costUsd,
                    rawResponseFileName = rawFile,
                    errorCategory = error?.category?.name,
                    errorMessage = error?.message?.take(600),
                    startedAtMs = timestamp,
                    finishedAtMs = timestamp,
                    retainUntilMs = timestamp + RawRetentionMs,
                ),
            )
        }
    }

    private suspend fun recordAsrAttempt(
        attempt: GenerationAttemptEntity,
        result: AsrWindowResult?,
        error: AsrException?,
    ) {
        val raw = result?.rawResponse ?: error?.rawResponse
        val requestAttemptId = newId()
        val rawFile = raw?.let { assets.writeRawResponse("$requestAttemptId.json", it) }
        val timestamp = nowMs()
        database.withWriteTransaction {
            val sequence = dao.nextRequestSequence(attempt.attemptId)
            dao.upsertRequestAttempt(
                RequestAttemptEntity(
                    requestAttemptId = requestAttemptId,
                    attemptId = attempt.attemptId,
                    sequence = sequence,
                    stage = GenerationStage.RecognizingSpeech.name,
                    provider = attempt.asrProfile,
                    requestBytes = result?.requestBytes,
                    responseBytes = result?.responseBytes ?: raw?.size?.toLong(),
                    httpStatus = result?.httpStatus ?: error?.httpStatus,
                    inputTokens = result?.usage?.inputTokens,
                    outputTokens = result?.usage?.outputTokens,
                    totalTokens = result?.usage?.totalTokens,
                    costUsd = result?.usage?.costUsd,
                    rawResponseFileName = rawFile,
                    errorCategory = error?.category?.name,
                    errorMessage = error?.message?.take(600),
                    startedAtMs = timestamp,
                    finishedAtMs = timestamp,
                    retainUntilMs = timestamp + RawRetentionMs,
                ),
            )
        }
    }

    private fun verifyPolicySnapshot(attempt: GenerationAttemptEntity, policy: TranslationPolicy) {
        if (
            attempt.policyId != policy.policyId ||
            attempt.policyVersion != policy.version ||
            attempt.policyRevision != policy.originRevision ||
            attempt.systemPromptSha256 != policy.systemPromptSha256 ||
            attempt.stylePromptSha256 != policy.stylePromptSha256
        ) {
            throw PolicyIntegrityException("Task policy snapshot no longer matches packaged policy assets")
        }
    }

    private suspend fun ensureRunnable(taskId: String) {
        if (!repository.isRunnable(taskId)) throw TaskDeferredException()
    }

    private fun planForRange(
        cues: List<SubtitleCue>,
        first: Int,
        last: Int,
        ordinal: Int,
    ) =
        TranslationBatchPlan(
            ordinal = ordinal,
            firstCueIndex = first,
            lastCueIndex = last,
            contextBefore = cues.subList(maxOf(0, first - 80), first),
            translateOnly = cues.subList(first, last + 1),
            contextAfter = cues.subList(last + 1, minOf(cues.size, last + 1 + 40)),
            estimatedInputTokens = 0,
        )

    companion object {
        private const val AsrParallelism = 2
        private const val TranslationParallelism = 4
        private const val MaxAsrAttempts = 3
        private const val RawRetentionMs = 7L * 24L * 60L * 60L * 1_000L
        private const val LeaseHeartbeatMs = 30_000L
        private const val SourceProgress = 0.55f
        private const val TranslationProgressRange = 0.42f
        private const val CompletedState = "Completed"
        private const val FailedState = "Failed"
        private const val SplitState = "Split"
    }
}

private class RoomRunnerLease(
    private val dao: io.github.zvensmoluya.vortexcue.data.db.SyncTextDao,
    private val ownerId: String,
    private val nowMs: () -> Long,
) {
    suspend fun acquire(): Boolean {
        val now = nowMs()
        val inserted =
            dao.insertRunnerLease(
                SyncTextRunnerLeaseEntity(LeaseKey, ownerId, now + LeaseDurationMs, generation = 1L),
            )
        return inserted != -1L || dao.tryAcquireRunnerLease(LeaseKey, ownerId, now, now + LeaseDurationMs) > 0
    }

    suspend fun renew(): Boolean {
        val now = nowMs()
        return dao.tryAcquireRunnerLease(LeaseKey, ownerId, now, now + LeaseDurationMs) > 0
    }

    suspend fun release() {
        dao.releaseRunnerLease(LeaseKey, ownerId, nowMs())
    }

    companion object {
        private const val LeaseKey = "queue"
        private const val LeaseDurationMs = 120_000L
    }
}

private class LeaseLostException : CancellationException("Sync text runner lease was lost")

private class TaskDeferredException : Exception()

private data class SourceWork(
    val cues: List<SubtitleCue>,
    val language: String?,
    val usage: UsageRecord,
    val resultId: String? = null,
)

private data class WindowTranscript(
    val cues: List<SubtitleCue>,
    val language: String?,
    val usage: UsageRecord,
)

private data class BatchWork(
    val cues: List<SubtitleCue>,
    val usage: UsageRecord,
)

private fun AsrWindowPlan.toEntity(attemptId: String) =
    AsrWindowEntity(
        windowId = windowId,
        attemptId = attemptId,
        ordinal = ordinal,
        ownedStartMs = ownedStartMs,
        ownedEndMs = ownedEndMs,
        uploadStartMs = uploadStartMs,
        uploadEndMs = uploadEndMs,
        pcmStartFrame = pcmStartFrame,
        pcmEndFrame = pcmEndFrame,
        cutReason = cutReason,
        plannerVersion = plannerVersion,
        parentWindowId = parentWindowId,
        state = "Waiting",
        encodedBytes = null,
        transcriptAssetFileName = null,
        errorMessage = null,
    )

private fun AsrWindowEntity.toPlan(mediaDurationMs: Long) =
    AsrWindowPlan(
        windowId = windowId,
        ordinal = ordinal,
        ownedStartMs = ownedStartMs,
        ownedEndMs = ownedEndMs,
        uploadStartMs = uploadStartMs,
        uploadEndMs = uploadEndMs,
        pcmStartFrame = pcmStartFrame,
        pcmEndFrame = pcmEndFrame,
        cutReason = cutReason,
        plannerVersion = plannerVersion,
        parentWindowId = parentWindowId,
        isLast = ownedEndMs == mediaDurationMs,
    )

private fun TranslationBatchPlan.toEntity(
    attemptId: String,
    batchId: String,
    parentBatchId: String? = null,
    persistedOrdinal: Int = ordinal,
) =
    TranslationBatchEntity(
        batchId = batchId,
        attemptId = attemptId,
        ordinal = persistedOrdinal,
        firstCueIndex = firstCueIndex,
        lastCueIndex = lastCueIndex,
        parentBatchId = parentBatchId,
        state = "Waiting",
        formatRecoveryCount = 0,
        repairCount = 0,
        resultAssetFileName = null,
        inputTokens = null,
        outputTokens = null,
        totalTokens = null,
        costUsd = null,
        errorMessage = null,
    )

private fun TranslationBatchEntity.usage() =
    UsageRecord(inputTokens, outputTokens, totalTokens, costUsd)

private fun SubtitleCue.toTimestampedToken() =
    AsrTimestampedToken(startMs, endMs, text)

private fun GenerationErrorCategory.blocksScheme(): Boolean =
    this == GenerationErrorCategory.Authentication ||
        this == GenerationErrorCategory.Configuration ||
        this == GenerationErrorCategory.ModelOrProtocol ||
        this == GenerationErrorCategory.Policy ||
        this == GenerationErrorCategory.InvalidResponse

private inline fun <reified T : Enum<T>> String.enumValueOr(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
