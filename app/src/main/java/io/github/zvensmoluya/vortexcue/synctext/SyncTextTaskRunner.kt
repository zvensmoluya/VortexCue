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
import io.github.zvensmoluya.vortexcue.data.db.SourcePreparationEntity
import io.github.zvensmoluya.vortexcue.data.db.SyncTextRunnerLeaseEntity
import io.github.zvensmoluya.vortexcue.data.db.TranslationBatchEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.text.CanonicalTextNormalizer
import io.github.zvensmoluya.vortexcue.text.SourceContext
import io.github.zvensmoluya.vortexcue.text.SourcePreparationArtifact
import io.github.zvensmoluya.vortexcue.text.SourceTextPatch
import io.github.zvensmoluya.vortexcue.text.TextArtifactKind
import io.github.zvensmoluya.vortexcue.text.TextArtifactPayload
import io.github.zvensmoluya.vortexcue.text.TextStorageRepository
import io.github.zvensmoluya.vortexcue.text.TimelineCompleteness
import io.github.zvensmoluya.vortexcue.text.TimelineRevisionReader
import io.github.zvensmoluya.vortexcue.text.canonicalContentHash
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SyncTextNetworkState {
    fun permits(allowMetered: Boolean): Boolean

    fun waitReason(allowMetered: Boolean): GenerationNetworkWaitReason? =
        if (permits(allowMetered)) null else GenerationNetworkWaitReason.Unavailable
}

class AndroidSyncTextNetworkState(context: Context) : SyncTextNetworkState {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun permits(allowMetered: Boolean): Boolean = waitReason(allowMetered) == null

    override fun waitReason(allowMetered: Boolean): GenerationNetworkWaitReason? {
        val network = connectivity.activeNetwork ?: return GenerationNetworkWaitReason.Unavailable
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return GenerationNetworkWaitReason.Unavailable
        if (
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return GenerationNetworkWaitReason.Unavailable
        }
        return if (!allowMetered && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            GenerationNetworkWaitReason.MeteredPermissionRequired
        } else {
            null
        }
    }
}

class SyncTextTaskRunner(
    private val database: VortexDatabase,
    private val repository: SyncTextRepository,
    private val policies: TranslationPolicyRegistry,
    private val sourcePreparationPolicies: SourcePreparationPolicyRegistry,
    private val rawResponses: RawResponseStore,
    private val textStorage: TextStorageRepository,
    private val revisionReader: TimelineRevisionReader,
    private val sourceText: SourceTextMaterializer,
    private val audio: AudioWindowExtractor,
    private val spool: PcmSpool,
    private val asr: AsrEngine,
    private val translation: TranslationNetworkGateway,
    private val networkState: SyncTextNetworkState,
    private val diagnostics: SyncTextDiagnosticRecorder = NoOpSyncTextDiagnosticRecorder,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.syncTextDao()
    private val contentDao = database.contentDao()
    private val runtimeStatus = GenerationRuntimeStatusWriter(dao, nowMs)

    suspend fun runUntilIdle(): Boolean {
        val ownerId = newId()
        val lease = RoomRunnerLease(dao, ownerId, nowMs)
        if (!lease.acquire()) {
            diagnostics.record(SyncTextDiagnosticEventType.RunnerLease, outcome = "Rejected")
            return false
        }
        val startedElapsedMs = elapsedRealtimeMs()
        diagnostics.record(SyncTextDiagnosticEventType.RunnerLease, outcome = "Acquired")
        diagnostics.record(
            SyncTextDiagnosticEventType.RunnerLifecycle,
            outcome = "Started",
            valueBytes = usedHeapBytes(),
        )
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
                            diagnostics.record(
                                SyncTextDiagnosticEventType.RunnerLifecycle,
                                outcome = "Heartbeat",
                                valueBytes = usedHeapBytes(),
                            )
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
            withContext(NonCancellable) {
                diagnostics.record(
                    SyncTextDiagnosticEventType.RunnerLifecycle,
                    outcome = "Finished",
                    valueMs = (elapsedRealtimeMs() - startedElapsedMs).coerceAtLeast(0L),
                    valueBytes = usedHeapBytes(),
                )
                lease.release()
                diagnostics.record(SyncTextDiagnosticEventType.RunnerLease, outcome = "Released")
            }
        }
    }

    private suspend fun processTask(task: GenerationTaskEntity) {
        try {
            val snapshot = requireNotNull(repository.taskSnapshot(task.taskId))
            val kind = task.kind.enumValueOr(GenerationTaskKind.SourceGeneration)
            val requiresNetwork =
                kind == GenerationTaskKind.TranslationDerivation ||
                    snapshot.sourceTrackId == null ||
                    snapshot.targetLanguage != null
            val networkWaitReason = networkState.waitReason(task.meteredNetworkAllowed)
            if (requiresNetwork && networkWaitReason != null) {
                repository.markWaitingForNetwork(task.taskId, networkWaitReason)
                return
            }
            repository.markWaitingForNetwork(task.taskId, null)
            ensureRunnable(task.taskId)
            val attempt = requireNotNull(repository.currentAttempt(task.taskId))
            val source = resolveSource(task, snapshot, attempt)
            if (kind == GenerationTaskKind.SourceGeneration) {
                val targetLanguage = snapshot.targetLanguage
                if (targetLanguage == null) {
                    if (repository.isTerminal(task.taskId)) return
                    ensureRunnable(task.taskId)
                    runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.Publishing)
                    repository.publishResult(
                        taskId = task.taskId,
                        sourceLanguage = snapshot.sourceLanguage ?: source.language,
                        targetLanguage = null,
                        presentationMode = TextPresentationMode.TargetOnly,
                        sourceCues = source.cues,
                        targetCues = null,
                        usage = source.usage,
                        sourceModel = source.sourceModel,
                        completeTask = true,
                        sourceRevisionId = source.revisionId,
                        coveredEndMs = source.coveredEndMs,
                        completedWindowCount = source.completedWindowCount,
                        totalWindowCount = source.totalWindowCount,
                    )
                    return
                }
                ensureRunnable(task.taskId)
                val publishedSource =
                    if (source.revisionId != null) {
                        source
                    } else {
                        runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.Publishing)
                        val result =
                            repository.publishResult(
                                taskId = task.taskId,
                                sourceLanguage = snapshot.sourceLanguage ?: source.language,
                                targetLanguage = null,
                                presentationMode = TextPresentationMode.TargetOnly,
                                sourceCues = source.cues,
                                targetCues = null,
                                usage = source.usage,
                                sourceModel = source.sourceModel,
                                completeTask = false,
                                completeness = GeneratedResultCompleteness.Final,
                                sourceRevisionId = source.revisionId,
                                coveredEndMs = source.coveredEndMs,
                                completedWindowCount = source.completedWindowCount,
                                totalWindowCount = source.totalWindowCount,
                            )
                        source.copy(resultId = result.resultId, revisionId = result.sourceRevisionId)
                    }
                translateAndPublish(
                    task,
                    snapshot,
                    requireNotNull(repository.currentAttempt(task.taskId)),
                    publishedSource,
                    targetLanguage,
                )
                return
            }
            val targetLanguage = requireNotNull(snapshot.targetLanguage) { "Translation target language is missing" }
            ensureRunnable(task.taskId)
            translateAndPublish(task, snapshot, requireNotNull(repository.currentAttempt(task.taskId)), source, targetLanguage)
        } catch (paused: TaskDeferredException) {
            Unit
        } catch (deferred: TaskPublicationDeferredException) {
            Unit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TranslationProtocolException) {
            if (!deferForUnavailableNetwork(task.taskId, error.category)) {
                repository.blockTask(
                    task.taskId,
                    error.category,
                    error.message ?: "Translation request failed",
                    blockScheme = error.category.blocksScheme(),
                )
            }
        } catch (error: TranslationOutputException) {
            repository.blockTask(
                task.taskId,
                GenerationErrorCategory.InvalidResponse,
                "Translation output failed strict cue validation",
                blockScheme = false,
            )
        } catch (error: AsrException) {
            if (!deferForUnavailableNetwork(task.taskId, error.category)) {
                repository.blockTask(
                    task.taskId,
                    error.category,
                    error.message ?: "Speech recognition failed",
                    blockScheme = error.category.blocksScheme(),
                )
            }
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

    private suspend fun deferForUnavailableNetwork(
        taskId: String,
        category: GenerationErrorCategory,
    ): Boolean {
        if (category != GenerationErrorCategory.TemporaryNetwork) return false
        val reason = networkState.waitReason(repository.meteredNetworkAllowed(taskId)) ?: return false
        repository.markWaitingForNetwork(taskId, reason)
        return true
    }

    private suspend fun resolveSource(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
    ): SourceWork {
        if (task.kind.enumValueOr(GenerationTaskKind.SourceGeneration) == GenerationTaskKind.TranslationDerivation) {
            runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.PreparingTranslation)
            snapshot.inputSourceRevisionId?.let { revisionId ->
                val revision = requireNotNull(textStorage.revision(revisionId)) { "Translation source revision is unavailable" }
                require(
                    revision.contentId == task.contentId &&
                        revision.contentHash == snapshot.inputSourceRevisionHash &&
                        revision.completeness == TimelineCompleteness.Final,
                ) {
                    "Translation source revision snapshot does not match the task"
                }
                return SourceWork(
                    cues = revisionReader.read(revisionId).toSubtitleCues(),
                    language = snapshot.sourceLanguage,
                    usage = UsageRecord(),
                    resultId = snapshot.inputSourceResultId,
                    revisionId = revisionId,
                    sourceContext = loadSourceContext(revisionId),
                )
            }
            val frozenResultId = requireNotNull(snapshot.inputSourceResultId) { "Translation source result is missing" }
            require(task.sourceResultId == frozenResultId && attempt.sourceResultId == frozenResultId) {
                "Translation source result snapshot does not match the task"
            }
            val entity = requireNotNull(dao.generatedResult(frozenResultId)) { "Translation source result is unavailable" }
            require(entity.targetLanguage == null && entity.completeness == GeneratedResultCompleteness.Final.name) {
                "Translation requires a final source result"
            }
            return requireNotNull(loadSourceResult(frozenResultId)) { "Translation source result is unavailable" }
        }
        return if (snapshot.sourceTrackId != null) {
            runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.MaterializingText)
            repository.markRunning(task.taskId, GenerationStage.MaterializingText, 0.08f)
            ensureRunnable(task.taskId)
            try {
                sourceText.materialize(task.contentId, snapshot).let { materialized ->
                    SourceWork(
                        cues = materialized.cues,
                        language = snapshot.sourceLanguage ?: materialized.language,
                        usage = UsageRecord(),
                        revisionId = materialized.revisionId,
                    )
                }
            } catch (error: SourceTextUnavailableException) {
                if (!snapshot.allowAsrFallback || !error.safeToFallback) throw error
                prepareAsrSource(
                    task,
                    attempt,
                    performAsr(task, snapshot.copy(sourceTrackId = null, embeddedTrackId = null), attempt),
                )
            }
        } else {
            prepareAsrSource(task, attempt, performAsr(task, snapshot, attempt))
        }
    }

    private suspend fun loadSourceResult(resultId: String): SourceWork? {
        val result = dao.generatedResult(resultId) ?: return null
        return SourceWork(
            cues = revisionReader.read(result.sourceRevisionId).toSubtitleCues(),
            language = result.sourceLanguage,
            usage = UsageRecord(result.inputTokens, result.outputTokens, result.totalTokens, result.costUsd),
            resultId = resultId,
            revisionId = result.sourceRevisionId,
            sourceContext = loadSourceContext(result.sourceRevisionId),
        )
    }

    private suspend fun loadSourceContext(sourceRevisionId: String): SourceContext {
        val checkpoint = dao.sourcePreparationForRevision(sourceRevisionId) ?: return SourceContext.Empty
        val artifactId = checkpoint.outputArtifactId ?: return SourceContext.Empty
        val decoded = textStorage.readArtifact(artifactId)
        val output = (decoded.payload as? TextArtifactPayload.Preparation)?.value
            ?: throw CheckpointIntegrityException("Source preparation checkpoint payload is invalid")
        if (output.inputSourceHash != checkpoint.inputSourceSha256) {
            throw CheckpointIntegrityException("Source preparation checkpoint source hash does not match")
        }
        return output.sourceContext
    }

    private suspend fun prepareAsrSource(
        task: GenerationTaskEntity,
        attempt: GenerationAttemptEntity,
        source: SourceWork,
    ): SourceWork {
        ensureRunnable(task.taskId)
        repository.markRunning(task.taskId, GenerationStage.PreparingSource, SourcePreparationProgress)
        runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.PreparingSource)
        val policy = sourcePreparationPolicies.resolve()
        verifySourcePreparationPolicySnapshot(attempt, policy)
        val normalizedSource = CanonicalTextNormalizer.normalizeGenerated(source.cues).toSubtitleCues()
        val inputTimeline = CanonicalTextNormalizer.normalizeGenerated(normalizedSource)
        val inputHash = canonicalContentHash(inputTimeline)
        val existing = dao.sourcePreparation(attempt.attemptId)
        if (
            existing?.outputArtifactId != null &&
            existing.inputSourceSha256 == inputHash &&
            existing.policyVersion == policy.version &&
            existing.promptSha256 == policy.promptSha256 &&
            existing.state != SourcePreparationCheckpointState.Running.name
        ) {
            val output = readSourcePreparationCheckpoint(existing, inputHash, policy)
            diagnostics.record(
                SyncTextDiagnosticEventType.WorkUnitLifecycle,
                taskId = task.taskId,
                attemptId = attempt.attemptId,
                workUnitKind = SourcePreparationWorkUnitKind,
                workUnitId = attempt.attemptId,
                stage = GenerationStage.PreparingSource.name,
                outcome = "Reused",
            )
            return source.copy(
                cues = SourcePatchApplier.apply(normalizedSource, output.patches),
                usage = listOf(source.usage, existing.usage()).sumRealUsage(),
                sourceContext = output.sourceContext,
            )
        }

        val startedAtMs = nowMs()
        val signals = SourcePreparationSignalDetector.detect(normalizedSource)
        dao.upsertSourcePreparation(
            SourcePreparationEntity(
                attemptId = attempt.attemptId,
                inputSourceSha256 = inputHash,
                state = SourcePreparationCheckpointState.Running.name,
                policyVersion = policy.version,
                promptSha256 = policy.promptSha256,
                model = attempt.translationModel,
                createdAtMs = existing?.createdAtMs ?: startedAtMs,
                updatedAtMs = startedAtMs,
            ),
        )
        diagnostics.record(
            SyncTextDiagnosticEventType.WorkUnitLifecycle,
            taskId = task.taskId,
            attemptId = attempt.attemptId,
            workUnitKind = SourcePreparationWorkUnitKind,
            workUnitId = attempt.attemptId,
            stage = GenerationStage.PreparingSource.name,
            outcome = "Started",
            reason = "flags=${signals.flags.size}",
        )

        var usage = UsageRecord()
        var failure: Throwable? = null
        val configured =
            !attempt.translationEndpoint.isNullOrBlank() &&
                !attempt.translationModel.isNullOrBlank() &&
                !attempt.translationCredentialId.isNullOrBlank()
        val modelOutput =
            if (configured) {
                try {
                    val protocol = attempt.translationProtocol.enumValueOr(TranslationProtocol.Responses)
                    val providerRequest =
                        TranslationProviderRequest(
                            endpoint = requireNotNull(attempt.translationEndpoint),
                            model = requireNotNull(attempt.translationModel),
                            apiKey = repository.credentialSecret(requireNotNull(attempt.translationCredentialId)),
                            systemPrompt = policy.systemPrompt,
                            userPrompt =
                                SourcePreparationPromptRenderer.render(
                                    normalizedSource,
                                    source.language,
                                    signals.flags,
                                ),
                            reasoningLevel = policy.reasoningLevel,
                            userAgent = attempt.translationUserAgent,
                        )
                    val response =
                        requestSourcePreparationWithReasoningFallback(
                            request = providerRequest,
                            onFallback = {
                                diagnostics.record(
                                    SyncTextDiagnosticEventType.WorkUnitLifecycle,
                                    taskId = task.taskId,
                                    attemptId = attempt.attemptId,
                                    workUnitKind = SourcePreparationWorkUnitKind,
                                    workUnitId = attempt.attemptId,
                                    stage = GenerationStage.PreparingSource.name,
                                    outcome = "ReasoningCompatibilityFallback",
                                    reason = "provider_rejected_explicit_low",
                                )
                            },
                        ) { effectiveRequest ->
                            translation.translate(
                                protocol = protocol,
                                request = effectiveRequest,
                                onAttempt = { networkAttempt ->
                                    recordSourcePreparationAttempt(attempt, protocol, networkAttempt)
                                },
                                onStreamEvent = { event ->
                                    when (event) {
                                        is TranslationStreamEvent.AttemptStarted ->
                                            runtimeStatus.updateTask(
                                                task.taskId,
                                                GenerationRuntimePhase.AwaitingSourcePreparation,
                                            )
                                        is TranslationStreamEvent.ReasoningDelta -> {
                                            runtimeStatus.recordTaskStreamActivity(task.taskId, event.text)
                                            runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.PreparingSource)
                                        }
                                        is TranslationStreamEvent.OutputDelta -> {
                                            runtimeStatus.recordTaskStreamActivity(task.taskId, event.text)
                                            runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.ReceivingSourcePreparation)
                                        }
                                        TranslationStreamEvent.AttemptCompleted -> {
                                            runtimeStatus.flushTaskStreamActivity(task.taskId)
                                            runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.ValidatingSourcePreparation)
                                        }
                                        TranslationStreamEvent.AttemptFailed -> {
                                            runtimeStatus.flushTaskStreamActivity(task.taskId)
                                            runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.AwaitingSourcePreparation)
                                        }
                                    }
                                },
                            )
                        }
                    usage = response.usage
                    SourcePreparationResponseParser.parse(
                        response = response.text,
                        sourceCues = normalizedSource,
                        policyDropIds = signals.policyPatches.mapTo(mutableSetOf(), SourceTextPatch::cueId),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failure = error
                    null
                }
            } else {
                null
            }

        var checkpointState =
            when {
                !configured -> SourcePreparationCheckpointState.SkippedNoModel
                failure != null -> SourcePreparationCheckpointState.DegradedError
                else -> SourcePreparationCheckpointState.Completed
            }
        var output =
            SourcePreparationArtifact(
                inputSourceHash = inputHash,
                policyVersion = policy.version,
                patches = signals.policyPatches + modelOutput?.patches.orEmpty(),
                sourceContext = modelOutput?.sourceContext ?: SourceContext.Empty,
            )
        val refined =
            runCatching { SourcePatchApplier.apply(normalizedSource, output.patches) }.getOrElse { error ->
                failure = failure ?: error
                checkpointState = SourcePreparationCheckpointState.DegradedError
                output =
                    SourcePreparationArtifact(
                        inputSourceHash = inputHash,
                        policyVersion = policy.version,
                        patches = emptyList(),
                        sourceContext = SourceContext.Empty,
                    )
                normalizedSource
            }
        val artifact = textStorage.publishSourcePreparation(output)
        val finishedAtMs = nowMs()
        database.withWriteTransaction {
            textStorage.registerArtifact(artifact)
            dao.upsertSourcePreparation(
                SourcePreparationEntity(
                    attemptId = attempt.attemptId,
                    inputSourceSha256 = inputHash,
                    state = checkpointState.name,
                    outputArtifactId = artifact.artifactId,
                    outputSha256 = artifact.payloadSha256,
                    policyVersion = policy.version,
                    promptSha256 = policy.promptSha256,
                    model = attempt.translationModel.takeIf { configured },
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    totalTokens = usage.totalTokens,
                    costUsd = usage.costUsd,
                    errorMessage = failure?.message?.take(600),
                    createdAtMs = existing?.createdAtMs ?: startedAtMs,
                    updatedAtMs = finishedAtMs,
                ),
            )
        }
        diagnostics.record(
            SyncTextDiagnosticEventType.WorkUnitLifecycle,
            taskId = task.taskId,
            attemptId = attempt.attemptId,
            workUnitKind = SourcePreparationWorkUnitKind,
            workUnitId = attempt.attemptId,
            stage = GenerationStage.PreparingSource.name,
            outcome = checkpointState.name,
            reason = failure?.rootCauseType() ?: "patches=${output.patches.size}",
            valueMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L),
        )
        return source.copy(
            cues = refined,
            usage = listOf(source.usage, usage).sumRealUsage(),
            sourceContext = output.sourceContext,
        )
    }

    private suspend fun readSourcePreparationCheckpoint(
        checkpoint: SourcePreparationEntity,
        inputHash: String,
        policy: SourcePreparationPolicy,
    ): SourcePreparationArtifact {
        val artifactId = requireNotNull(checkpoint.outputArtifactId)
        val expectedHash = checkpoint.outputSha256
            ?: throw CheckpointIntegrityException("Source preparation checkpoint hash is missing")
        val metadata = textStorage.artifact(artifactId)
            ?: throw CheckpointIntegrityException("Source preparation checkpoint metadata is missing")
        if (metadata.payloadSha256 != expectedHash) {
            throw CheckpointIntegrityException("Source preparation checkpoint hash does not match")
        }
        val decoded = textStorage.readArtifact(artifactId)
        val output = (decoded.payload as? TextArtifactPayload.Preparation)?.value
            ?: throw CheckpointIntegrityException("Source preparation checkpoint payload is invalid")
        if (output.inputSourceHash != inputHash || output.policyVersion != policy.version) {
            throw CheckpointIntegrityException("Source preparation checkpoint input does not match")
        }
        return output
    }

    private suspend fun performAsr(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
    ): SourceWork {
        runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.Planning)
        repository.markRunning(task.taskId, GenerationStage.DecodingAudio, 0.05f)
        val activeLocatorId = snapshot.mediaLocatorId ?: contentDao.activeLocator(task.contentId)?.locatorId
            ?: throw AudioDecodeException("Content has no active media locator")
        val media = contentDao.locator(activeLocatorId)
            ?: throw AudioDecodeException("Active media locator is unavailable")
        require(media.contentId == task.contentId) { "Frozen media locator does not belong to the task content" }
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
        val roots = entities.filter { it.parentWindowId == null }.sortedBy(AsrWindowEntity::ownedStartMs)
        val publicationMutex = Mutex()
        val completedByRoot = linkedMapOf<String, List<WindowTranscript>>()
        var publishedRootCount =
            task.sourceResultId?.let { dao.generatedResult(it)?.completedWindowCount } ?: 0
        val initiallyCompletedRootIds =
            roots.filter { it.state == CompletedState && it.transcriptArtifactId != null }
                .mapTo(mutableSetOf(), AsrWindowEntity::windowId)
        val completed = AtomicInteger(initiallyCompletedRootIds.size)
        val transcripts =
            fifoParallelMap(roots, AsrParallelism) { entity ->
                processAsrWindowTree(task, snapshot, attempt, mediaUri, durationMs, planner, entity).also { work ->
                    if (entity.windowId !in initiallyCompletedRootIds) completed.incrementAndGet()
                    repository.markRunning(
                        task.taskId,
                        GenerationStage.RecognizingSpeech,
                        0.10f + 0.40f * completed.get() / roots.size.coerceAtLeast(1),
                    )
                    publicationMutex.withLock {
                        completedByRoot[entity.windowId] = work
                        val prefix = roots.takeWhile { completedByRoot.containsKey(it.windowId) }
                        if (prefix.size > publishedRootCount) {
                            val prefixWork = prefix.flatMap { requireNotNull(completedByRoot[it.windowId]) }
                            val prefixCues = mergeWindowTranscripts(prefixWork)
                            if (prefixCues.isNotEmpty()) {
                                val normalizedPrefix =
                                    CanonicalTextNormalizer.normalizeGenerated(prefixCues).toSubtitleCues()
                                val language = resolveAsrLanguage(snapshot, prefixWork)
                                repository.publishResult(
                                    taskId = task.taskId,
                                    sourceLanguage = language,
                                    targetLanguage = null,
                                    presentationMode = TextPresentationMode.TargetOnly,
                                    sourceCues = normalizedPrefix,
                                    targetCues = null,
                                    usage = prefixWork.map(WindowTranscript::usage).sumRealUsage(),
                                    sourceModel = AsrProfile.fromStoredValue(attempt.asrProfile).modelId,
                                    completeTask = false,
                                    completeness = GeneratedResultCompleteness.Partial,
                                    coveredEndMs = prefix.last().ownedEndMs,
                                    completedWindowCount = prefix.size,
                                    totalWindowCount = roots.size,
                                    partialArtifactIds = prefixWork.map(WindowTranscript::artifactId),
                                )
                                publishedRootCount = prefix.size
                            }
                        }
                    }
                }
            }.flatten()
        val merged = mergeWindowTranscripts(transcripts)
        if (merged.isEmpty()) {
            throw AsrException(
                category = GenerationErrorCategory.Timeline,
                retryable = false,
                shouldSplit = false,
                message = "未检测到可转写语音",
            )
        }
        val language = resolveAsrLanguage(snapshot, transcripts)
        val usage = transcripts.map(WindowTranscript::usage).sumRealUsage()
        val normalized = CanonicalTextNormalizer.normalizeGenerated(merged).toSubtitleCues()
        return SourceWork(
            cues = normalized,
            language = language,
            usage = usage,
            sourceModel = AsrProfile.fromStoredValue(attempt.asrProfile).modelId,
            coveredEndMs = roots.lastOrNull()?.ownedEndMs,
            completedWindowCount = roots.size,
            totalWindowCount = roots.size,
        )
    }

    private fun mergeWindowTranscripts(transcripts: List<WindowTranscript>): List<SubtitleCue> {
        val tokens = transcripts.flatMap { transcript -> transcript.cues.map { it.toTimestampedToken() } }
        return AsrTimelineMerger.mergeSegments(tokens)
    }

    private fun resolveAsrLanguage(
        snapshot: GenerationTaskSnapshotEntity,
        transcripts: List<WindowTranscript>,
    ): String? =
        snapshot.sourceLanguage
            ?: transcripts.mapNotNull(WindowTranscript::language).groupingBy { it.lowercase() }.eachCount()
                .maxByOrNull { it.value }?.key

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
        if (entity.state == CompletedState && entity.transcriptArtifactId != null) {
            diagnostics.record(
                SyncTextDiagnosticEventType.WorkUnitLifecycle,
                taskId = task.taskId,
                attemptId = attempt.attemptId,
                workUnitKind = AsrWorkUnitKind,
                workUnitId = entity.windowId,
                stage = GenerationStage.RecognizingSpeech.name,
                outcome = "Reused",
            )
            return listOf(
                WindowTranscript(
                    cues = readAsrCheckpoint(entity),
                    language = entity.detectedLanguage,
                    usage = UsageRecord(entity.inputTokens, entity.outputTokens, entity.totalTokens, entity.costUsd),
                    artifactId = entity.transcriptArtifactId,
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
        val workUnitStartedElapsedMs = elapsedRealtimeMs()
        diagnostics.record(
            SyncTextDiagnosticEventType.WorkUnitLifecycle,
            taskId = task.taskId,
            attemptId = attempt.attemptId,
            workUnitKind = AsrWorkUnitKind,
            workUnitId = entity.windowId,
            stage = GenerationStage.RecognizingSpeech.name,
            outcome = "Started",
            valueMs = plan.uploadEndMs - plan.uploadStartMs,
        )
        dao.upsertAsrWindow(entity.copy(state = RunningState, errorMessage = null))
        runtimeStatus.updateWorkUnit(
            taskId = task.taskId,
            phase = GenerationRuntimePhase.ExtractingAudio,
            workUnitKind = GenerationWorkUnitKind.AsrWindow,
            workUnitId = entity.windowId,
        )
        val wav = spool.reserve(task.taskId, plan.windowId)
        var finalFailure: AsrException? = null
        try {
            audio.extract(mediaUri, snapshot.audioTrackId, plan, wav)
            dao.upsertAsrWindow(entity.copy(state = RunningState, encodedBytes = wav.length(), errorMessage = null))
            val key = repository.credentialSecret(attempt.asrCredentialId)
            for (index in 0 until MaxAsrAttempts) {
                ensureRunnable(task.taskId)
                repository.markRunning(task.taskId, GenerationStage.RecognizingSpeech, 0.10f)
                runtimeStatus.updateWorkUnit(
                    taskId = task.taskId,
                    phase = GenerationRuntimePhase.AwaitingAsr,
                    workUnitKind = GenerationWorkUnitKind.AsrWindow,
                    workUnitId = entity.windowId,
                    retryOrdinal = index + 1,
                )
                val startedAtMs = nowMs()
                val startedElapsedMs = elapsedRealtimeMs()
                try {
                    val result =
                        asr.transcribe(
                            AsrRequest(
                                profile = AsrProfile.fromStoredValue(attempt.asrProfile),
                                endpoint = attempt.asrEndpoint,
                                apiKey = key,
                                wavFile = wav,
                                window = plan,
                                sourceLanguage = snapshot.sourceLanguage,
                            ),
                        )
                    recordAsrAttempt(
                        attempt = attempt,
                        windowId = entity.windowId,
                        retryOrdinal = index + 1,
                        result = result,
                        error = null,
                        startedAtMs = startedAtMs,
                        finishedAtMs = nowMs(),
                        durationMs = (elapsedRealtimeMs() - startedElapsedMs).coerceAtLeast(0L),
                    )
                    val cues =
                        when (AsrProfile.fromStoredValue(attempt.asrProfile).timelineStrategy) {
                            AsrTimelineStrategy.Segment -> AsrTimelineMerger.mergeSegments(result.segments)
                            AsrTimelineStrategy.Word -> AsrTimelineMerger.wordsToCues(result.segments)
                        }
                    val timeline = CanonicalTextNormalizer.normalizeAsrWorkUnit(cues)
                    val checkpoint = textStorage.publishTimelineArtifact(TextArtifactKind.AsrWorkUnitOutput, timeline)
                    database.withWriteTransaction {
                        textStorage.registerArtifact(checkpoint)
                        dao.upsertAsrWindow(
                            entity.copy(
                                state = CompletedState,
                                encodedBytes = wav.length(),
                                transcriptArtifactId = checkpoint.artifactId,
                                transcriptSha256 = checkpoint.payloadSha256,
                                cueCount = checkpoint.cueCount,
                                errorMessage = null,
                                detectedLanguage = result.detectedLanguage,
                                inputTokens = result.usage.inputTokens,
                                outputTokens = result.usage.outputTokens,
                                totalTokens = result.usage.totalTokens,
                                costUsd = result.usage.costUsd,
                            ),
                        )
                    }
                    runtimeStatus.updateWorkUnit(
                        taskId = task.taskId,
                        phase = GenerationRuntimePhase.Completed,
                        workUnitKind = GenerationWorkUnitKind.AsrWindow,
                        workUnitId = entity.windowId,
                    )
                    diagnostics.record(
                        SyncTextDiagnosticEventType.WorkUnitLifecycle,
                        taskId = task.taskId,
                        attemptId = attempt.attemptId,
                        workUnitKind = AsrWorkUnitKind,
                        workUnitId = entity.windowId,
                        stage = GenerationStage.RecognizingSpeech.name,
                        outcome = "Completed",
                        valueMs = (elapsedRealtimeMs() - workUnitStartedElapsedMs).coerceAtLeast(0L),
                        valueBytes = wav.length(),
                    )
                    return listOf(
                        WindowTranscript(
                            timeline.toSubtitleCues(),
                            result.detectedLanguage,
                            result.usage,
                            checkpoint.artifactId,
                        ),
                    )
                } catch (error: AsrException) {
                    finalFailure = error
                    recordAsrAttempt(
                        attempt = attempt,
                        windowId = entity.windowId,
                        retryOrdinal = index + 1,
                        result = null,
                        error = error,
                        startedAtMs = startedAtMs,
                        finishedAtMs = nowMs(),
                        durationMs = (elapsedRealtimeMs() - startedElapsedMs).coerceAtLeast(0L),
                    )
                    if (!error.retryable || index == MaxAsrAttempts - 1) break
                    val waitReason = networkState.waitReason(repository.meteredNetworkAllowed(task.taskId))
                    if (waitReason != null) {
                        repository.markWaitingForNetwork(task.taskId, waitReason)
                        throw TaskDeferredException()
                    }
                    runtimeStatus.updateWorkUnit(
                        taskId = task.taskId,
                        phase = GenerationRuntimePhase.RetryingAsr,
                        workUnitKind = GenerationWorkUnitKind.AsrWindow,
                        workUnitId = entity.windowId,
                        retryOrdinal = index + 2,
                    )
                    delay(AsrRetryPolicy.delayMs(error, index))
                }
            }
        } finally {
            withContext(NonCancellable) { spool.release(wav) }
        }

        val failure = requireNotNull(finalFailure)
        if (AsrRetryPolicy.shouldSplit(failure, plan)) {
            val nextOrdinal = dao.nextAsrWindowOrdinal(attempt.attemptId)
            val split = runCatching { planner.split(plan, mediaDurationMs, nextOrdinal) }.getOrNull()
            if (split != null) {
                runtimeStatus.updateWorkUnit(
                    taskId = task.taskId,
                    phase = GenerationRuntimePhase.Splitting,
                    workUnitKind = GenerationWorkUnitKind.AsrWindow,
                    workUnitId = entity.windowId,
                )
                val children = listOf(split.first, split.second).map { it.toEntity(attempt.attemptId) }
                database.withWriteTransaction {
                    dao.upsertAsrWindow(entity.copy(state = SplitState, errorMessage = failure.message?.take(600)))
                    dao.upsertAsrWindows(children)
                }
                diagnostics.record(
                    SyncTextDiagnosticEventType.WorkUnitLifecycle,
                    taskId = task.taskId,
                    attemptId = attempt.attemptId,
                    workUnitKind = AsrWorkUnitKind,
                    workUnitId = entity.windowId,
                    stage = GenerationStage.RecognizingSpeech.name,
                    outcome = "Split",
                    reason = failure.category.name,
                    valueMs = (elapsedRealtimeMs() - workUnitStartedElapsedMs).coerceAtLeast(0L),
                )
                return children.flatMap { child ->
                    processAsrWindowTree(task, snapshot, attempt, mediaUri, mediaDurationMs, planner, child)
                }
            }
        }
        dao.upsertAsrWindow(entity.copy(state = FailedState, errorMessage = failure.message?.take(600)))
        diagnostics.record(
            SyncTextDiagnosticEventType.WorkUnitLifecycle,
            taskId = task.taskId,
            attemptId = attempt.attemptId,
            workUnitKind = AsrWorkUnitKind,
            workUnitId = entity.windowId,
            stage = GenerationStage.RecognizingSpeech.name,
            outcome = "Failed",
            reason = failure.category.name,
            valueMs = (elapsedRealtimeMs() - workUnitStartedElapsedMs).coerceAtLeast(0L),
        )
        throw failure
    }

    private suspend fun readAsrCheckpoint(entity: AsrWindowEntity): List<SubtitleCue> {
        val artifactId = requireNotNull(entity.transcriptArtifactId)
        val expectedHash = entity.transcriptSha256
            ?: throw CheckpointIntegrityException("ASR checkpoint hash is missing")
        val expectedCueCount = entity.cueCount
            ?: throw CheckpointIntegrityException("ASR checkpoint cue count is missing")
        val metadata = textStorage.artifact(artifactId)
            ?: throw CheckpointIntegrityException("ASR checkpoint metadata is missing")
        if (metadata.payloadSha256 != expectedHash) {
            throw CheckpointIntegrityException("ASR checkpoint hash does not match")
        }
        val decoded = textStorage.readArtifact(artifactId)
        val cues = (decoded.payload as? TextArtifactPayload.Timeline)?.value?.toSubtitleCues()
            ?: throw CheckpointIntegrityException("ASR checkpoint payload is invalid")
        if (cues.size != expectedCueCount) throw CheckpointIntegrityException("ASR checkpoint cue count does not match")
        if (cues.any { it.startMs < entity.ownedStartMs || it.endMs > entity.ownedEndMs }) {
            throw CheckpointIntegrityException("ASR checkpoint cue range is invalid")
        }
        return cues
    }

    private suspend fun translateAndPublish(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
        source: SourceWork,
        targetLanguage: String,
    ) {
        runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.PreparingTranslation)
        val policy = policies.resolve(targetLanguage)
        verifyPolicySnapshot(attempt, policy)
        val normalizedSource = CueIdNormalizer.normalize(source.cues)
        val planner = TranslationBatchPlanner()
        val sourceContextPrompt = SourceContextPromptRenderer.render(source.sourceContext)
        val plans =
            planner.plan(
                normalizedSource,
                policy.systemPrompt + policy.stylePrompt.orEmpty() + sourceContextPrompt,
            )
        var entities = dao.translationBatches(attempt.attemptId)
        if (entities.isEmpty()) {
            entities = plans.map { plan -> plan.toEntity(attempt.attemptId, "${attempt.attemptId}-batch-${plan.ordinal}") }
            dao.upsertTranslationBatches(entities)
        }
        val initialByRange = entities.filter { it.parentBatchId == null }.associateBy { it.firstCueIndex to it.lastCueIndex }
        val initiallyCompletedRanges =
            initialByRange.filterValues { it.state == CompletedState && it.resultArtifactId != null }.keys
        val completed = AtomicInteger(initiallyCompletedRanges.size)
        repository.markRunning(
            task.taskId,
            GenerationStage.Translating,
            SourceProgress + TranslationProgressRange * completed.get() / plans.size.coerceAtLeast(1),
        )
        val outputs =
            fifoParallelMap(plans, TranslationParallelism) { plan ->
                val range = plan.firstCueIndex to plan.lastCueIndex
                val entity = initialByRange[range]
                    ?: plan.toEntity(attempt.attemptId, newId()).also { dao.upsertTranslationBatch(it) }
                processTranslationBatch(
                    task,
                    snapshot,
                    attempt,
                    policy,
                    snapshot.sourceLanguage ?: source.language,
                    source.sourceContext,
                    normalizedSource,
                    plan,
                    entity,
                ).also {
                    if (range !in initiallyCompletedRanges) completed.incrementAndGet()
                    repository.markRunning(
                        task.taskId,
                        GenerationStage.Translating,
                        SourceProgress + TranslationProgressRange * completed.get() / plans.size.coerceAtLeast(1),
                    )
                }
            }.flatten()
        val translatedById = outputs.flatMap(BatchWork::cues).associateBy { requireNotNull(it.stableId) }
        val target =
            normalizedSource.map { cue ->
                requireNotNull(translatedById[cue.stableId]) { "Translated cue is missing after batch merge" }
                    .copy(startMs = cue.startMs, endMs = cue.endMs, stableId = cue.stableId)
            }
        ensureRunnable(task.taskId)
        runtimeStatus.updateTask(task.taskId, GenerationRuntimePhase.Publishing)
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
            sourceRevisionId = requireNotNull(source.revisionId) { "Translation source revision is missing" },
        )
    }

    private suspend fun processTranslationBatch(
        task: GenerationTaskEntity,
        snapshot: GenerationTaskSnapshotEntity,
        attempt: GenerationAttemptEntity,
        policy: TranslationPolicy,
        sourceLanguage: String?,
        sourceContext: SourceContext,
        allSource: List<SubtitleCue>,
        plan: TranslationBatchPlan,
        entity: TranslationBatchEntity,
    ): List<BatchWork> {
        ensureRunnable(task.taskId)
        if (entity.state == CompletedState && entity.resultArtifactId != null) {
            diagnostics.record(
                SyncTextDiagnosticEventType.WorkUnitLifecycle,
                taskId = task.taskId,
                attemptId = attempt.attemptId,
                workUnitKind = TranslationWorkUnitKind,
                workUnitId = entity.batchId,
                stage = GenerationStage.Translating.name,
                outcome = "Reused",
            )
            return listOf(
                BatchWork(
                    cues = readTranslationCheckpoint(entity, plan),
                    usage = entity.usage(),
                ),
            )
        }
        if (entity.state == SplitState) {
            val children = dao.translationBatches(attempt.attemptId).filter { it.parentBatchId == entity.batchId }
            return children.sortedBy(TranslationBatchEntity::ordinal).flatMap { child ->
                val childPlan = planForRange(allSource, child.firstCueIndex, child.lastCueIndex, child.ordinal)
                processTranslationBatch(
                    task,
                    snapshot,
                    attempt,
                    policy,
                    sourceLanguage,
                    sourceContext,
                    allSource,
                    childPlan,
                    child,
                )
            }
        }

        val workUnitStartedElapsedMs = elapsedRealtimeMs()
        diagnostics.record(
            SyncTextDiagnosticEventType.WorkUnitLifecycle,
            taskId = task.taskId,
            attemptId = attempt.attemptId,
            workUnitKind = TranslationWorkUnitKind,
            workUnitId = entity.batchId,
            stage = GenerationStage.Translating.name,
            outcome = "Started",
        )
        dao.upsertTranslationBatch(entity.copy(state = RunningState, errorMessage = null))
        runtimeStatus.updateWorkUnit(
            taskId = task.taskId,
            phase = GenerationRuntimePhase.PreparingTranslation,
            workUnitKind = GenerationWorkUnitKind.TranslationBatch,
            workUnitId = entity.batchId,
            expectedItemCount = plan.translateOnly.size,
        )
        val key = repository.credentialSecret(requireNotNull(attempt.translationCredentialId))
        val endpoint = requireNotNull(attempt.translationEndpoint) { "Translation endpoint is missing from the attempt" }
        val model = requireNotNull(attempt.translationModel) { "Translation model is missing from the attempt" }
        val protocol = attempt.translationProtocol.enumValueOr(TranslationProtocol.Responses)
        val reasoningLevel =
            attempt.translationReasoningLevel.enumValueOr(TranslationReasoningLevel.Off)
        val operationOrdinal = AtomicInteger(0)
        val batchExecutor =
            TranslationBatchExecutor { systemPrompt, userPrompt ->
                val operation = operationOrdinal.incrementAndGet()
                val expectedIds = plan.translateOnly.mapNotNull(SubtitleCue::stableId).toSet()
                val receivedIds = mutableSetOf<String>()
                val outputBuffer = StringBuilder()
                translation.translate(
                    protocol = protocol,
                    request =
                        TranslationProviderRequest(
                            endpoint = endpoint,
                            model = model,
                            apiKey = key,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            reasoningLevel = reasoningLevel,
                            userAgent = attempt.translationUserAgent,
                        ),
                    onAttempt = { networkAttempt ->
                        recordTranslationAttempt(
                            attempt = attempt,
                            protocol = protocol,
                            batchId = entity.batchId,
                            operationOrdinal = operation,
                            networkAttempt = networkAttempt,
                        )
                    },
                    onStreamEvent = { event ->
                        when (event) {
                            is TranslationStreamEvent.AttemptStarted -> {
                                outputBuffer.clear()
                                receivedIds.clear()
                                runtimeStatus.updateWorkUnit(
                                    taskId = task.taskId,
                                    phase =
                                        if (operation > 1 || event.attemptNumber > 1) {
                                            GenerationRuntimePhase.RetryingTranslation
                                        } else {
                                            GenerationRuntimePhase.AwaitingTranslation
                                        },
                                    workUnitKind = GenerationWorkUnitKind.TranslationBatch,
                                    workUnitId = entity.batchId,
                                    observedItemCount = 0,
                                    expectedItemCount = expectedIds.size,
                                    operationOrdinal = operation,
                                    retryOrdinal = event.attemptNumber,
                                )
                            }
                            is TranslationStreamEvent.ReasoningDelta -> {
                                runtimeStatus.recordTaskStreamActivity(task.taskId, event.text)
                                runtimeStatus.updateWorkUnit(
                                    taskId = task.taskId,
                                    phase = GenerationRuntimePhase.ModelReasoning,
                                    workUnitKind = GenerationWorkUnitKind.TranslationBatch,
                                    workUnitId = entity.batchId,
                                    observedItemCount = receivedIds.size,
                                    expectedItemCount = expectedIds.size,
                                    operationOrdinal = operation,
                                )
                            }
                            is TranslationStreamEvent.OutputDelta -> {
                                runtimeStatus.recordTaskStreamActivity(task.taskId, event.text)
                                outputBuffer.append(event.text)
                                expectedIds.forEach { cueId ->
                                    if (cueId !in receivedIds && outputBuffer.contains("[$cueId]")) receivedIds += cueId
                                }
                                runtimeStatus.updateWorkUnit(
                                    taskId = task.taskId,
                                    phase = GenerationRuntimePhase.ReceivingTranslation,
                                    workUnitKind = GenerationWorkUnitKind.TranslationBatch,
                                    workUnitId = entity.batchId,
                                    observedItemCount = receivedIds.size,
                                    expectedItemCount = expectedIds.size,
                                    operationOrdinal = operation,
                                )
                            }
                            TranslationStreamEvent.AttemptCompleted -> {
                                runtimeStatus.flushTaskStreamActivity(task.taskId)
                                runtimeStatus.updateWorkUnit(
                                    taskId = task.taskId,
                                    phase = GenerationRuntimePhase.ValidatingTranslation,
                                    workUnitKind = GenerationWorkUnitKind.TranslationBatch,
                                    workUnitId = entity.batchId,
                                    observedItemCount = receivedIds.size,
                                    expectedItemCount = expectedIds.size,
                                    operationOrdinal = operation,
                                )
                            }
                            TranslationStreamEvent.AttemptFailed -> {
                                runtimeStatus.flushTaskStreamActivity(task.taskId)
                                runtimeStatus.updateWorkUnit(
                                    taskId = task.taskId,
                                    phase = GenerationRuntimePhase.RetryingTranslation,
                                    workUnitKind = GenerationWorkUnitKind.TranslationBatch,
                                    workUnitId = entity.batchId,
                                    observedItemCount = receivedIds.size,
                                    expectedItemCount = expectedIds.size,
                                    operationOrdinal = operation,
                                )
                            }
                        }
                    },
                )
            }
        try {
            val output =
                batchExecutor.execute(
                    plan = plan,
                    policy = policy,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = requireNotNull(snapshot.targetLanguage),
                    sourceContext = sourceContext,
                )
            val timeline = CanonicalTextNormalizer.normalizeGenerated(output.targetCues)
            val checkpoint =
                textStorage.publishTimelineArtifact(TextArtifactKind.TranslationWorkUnitOutput, timeline)
            database.withWriteTransaction {
                textStorage.registerArtifact(checkpoint)
                dao.upsertTranslationBatch(
                    entity.copy(
                        state = CompletedState,
                        formatRecoveryCount = output.formatRecoveryCount,
                        repairCount = output.repairCount,
                        resultArtifactId = checkpoint.artifactId,
                        resultSha256 = checkpoint.payloadSha256,
                        cueCount = checkpoint.cueCount,
                        inputTokens = output.usage.inputTokens,
                        outputTokens = output.usage.outputTokens,
                        totalTokens = output.usage.totalTokens,
                        costUsd = output.usage.costUsd,
                        errorMessage = null,
                    ),
                )
            }
            runtimeStatus.updateWorkUnit(
                taskId = task.taskId,
                phase = GenerationRuntimePhase.Completed,
                workUnitKind = GenerationWorkUnitKind.TranslationBatch,
                workUnitId = entity.batchId,
                observedItemCount = output.targetCues.size,
                expectedItemCount = plan.translateOnly.size,
            )
            diagnostics.record(
                SyncTextDiagnosticEventType.WorkUnitLifecycle,
                taskId = task.taskId,
                attemptId = attempt.attemptId,
                workUnitKind = TranslationWorkUnitKind,
                workUnitId = entity.batchId,
                stage = GenerationStage.Translating.name,
                outcome = "Completed",
                valueMs = (elapsedRealtimeMs() - workUnitStartedElapsedMs).coerceAtLeast(0L),
            )
            if (output.normalizedIdCount > 0) {
                diagnostics.record(
                    SyncTextDiagnosticEventType.TranslationValidation,
                    taskId = task.taskId,
                    attemptId = attempt.attemptId,
                    workUnitKind = TranslationWorkUnitKind,
                    workUnitId = entity.batchId,
                    stage = GenerationStage.Translating.name,
                    outcome = "Normalized",
                    reason = "MissingCuePrefix=${output.normalizedIdCount}",
                )
            }
            return listOf(BatchWork(output.targetCues, output.usage))
        } catch (error: TranslationProtocolException) {
            if (!error.capacityExceeded || plan.translateOnly.size <= 1) {
                dao.upsertTranslationBatch(entity.copy(state = FailedState, errorMessage = error.message?.take(600)))
                diagnostics.record(
                    SyncTextDiagnosticEventType.WorkUnitLifecycle,
                    taskId = task.taskId,
                    attemptId = attempt.attemptId,
                    workUnitKind = TranslationWorkUnitKind,
                    workUnitId = entity.batchId,
                    stage = GenerationStage.Translating.name,
                    outcome = "Failed",
                    reason = error.category.name,
                    valueMs = (elapsedRealtimeMs() - workUnitStartedElapsedMs).coerceAtLeast(0L),
                )
                throw error
            }
            val (leftPlan, rightPlan) = TranslationBatchPlanner().split(plan)
            runtimeStatus.updateWorkUnit(
                taskId = task.taskId,
                phase = GenerationRuntimePhase.Splitting,
                workUnitKind = GenerationWorkUnitKind.TranslationBatch,
                workUnitId = entity.batchId,
                expectedItemCount = plan.translateOnly.size,
            )
            val children =
                database.withWriteTransaction {
                    val firstOrdinal = dao.nextTranslationBatchOrdinal(attempt.attemptId)
                    val left = leftPlan.toEntity(attempt.attemptId, newId(), entity.batchId, firstOrdinal)
                    val right = rightPlan.toEntity(attempt.attemptId, newId(), entity.batchId, firstOrdinal + 1)
                    dao.upsertTranslationBatch(entity.copy(state = SplitState, errorMessage = error.message?.take(600)))
                    dao.upsertTranslationBatches(listOf(left, right))
                    listOf(leftPlan to left, rightPlan to right)
                }
            diagnostics.record(
                SyncTextDiagnosticEventType.WorkUnitLifecycle,
                taskId = task.taskId,
                attemptId = attempt.attemptId,
                workUnitKind = TranslationWorkUnitKind,
                workUnitId = entity.batchId,
                stage = GenerationStage.Translating.name,
                outcome = "Split",
                reason = error.category.name,
                valueMs = (elapsedRealtimeMs() - workUnitStartedElapsedMs).coerceAtLeast(0L),
            )
            return children.flatMap { (childPlan, childEntity) ->
                processTranslationBatch(
                    task,
                    snapshot,
                    attempt,
                    policy,
                    sourceLanguage,
                    sourceContext,
                    allSource,
                    childPlan,
                    childEntity,
                )
            }
        } catch (error: TranslationOutputException) {
            val issueSummary = error.diagnosticIssueSummary()
            dao.upsertTranslationBatch(entity.copy(state = FailedState, errorMessage = issueSummary.take(600)))
            diagnostics.record(
                SyncTextDiagnosticEventType.TranslationValidation,
                taskId = task.taskId,
                attemptId = attempt.attemptId,
                workUnitKind = TranslationWorkUnitKind,
                workUnitId = entity.batchId,
                stage = GenerationStage.Translating.name,
                outcome = "Rejected",
                reason = issueSummary,
                valueMs = (elapsedRealtimeMs() - workUnitStartedElapsedMs).coerceAtLeast(0L),
            )
            throw error
        }
    }

    private suspend fun readTranslationCheckpoint(
        entity: TranslationBatchEntity,
        plan: TranslationBatchPlan,
    ): List<SubtitleCue> {
        val artifactId = requireNotNull(entity.resultArtifactId)
        val expectedHash = entity.resultSha256
            ?: throw CheckpointIntegrityException("Translation checkpoint hash is missing")
        val expectedCueCount = entity.cueCount
            ?: throw CheckpointIntegrityException("Translation checkpoint cue count is missing")
        val metadata = textStorage.artifact(artifactId)
            ?: throw CheckpointIntegrityException("Translation checkpoint metadata is missing")
        if (metadata.payloadSha256 != expectedHash) {
            throw CheckpointIntegrityException("Translation checkpoint hash does not match")
        }
        val decoded = textStorage.readArtifact(artifactId)
        val cues = (decoded.payload as? TextArtifactPayload.Timeline)?.value?.toSubtitleCues()
            ?: throw CheckpointIntegrityException("Translation checkpoint payload is invalid")
        if (cues.size != expectedCueCount || cues.size != plan.translateOnly.size) {
            throw CheckpointIntegrityException("Translation checkpoint cue count does not match")
        }
        val expectedIds = plan.translateOnly.map { it.stableId }
        if (cues.map { it.stableId } != expectedIds) {
            throw CheckpointIntegrityException("Translation checkpoint cue ids do not match")
        }
        return cues
    }

    private suspend fun recordTranslationAttempt(
        attempt: GenerationAttemptEntity,
        protocol: TranslationProtocol,
        batchId: String,
        operationOrdinal: Int,
        networkAttempt: TranslationNetworkAttempt,
    ) {
        val response = networkAttempt.response
        val error = networkAttempt.error
        val raw = response?.rawResponse ?: error?.rawResponse
        val requestAttemptId = newId()
        val rawFile = raw?.let { rawResponses.write("$requestAttemptId.json", it) }
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
                    providerRequestId = response?.requestId ?: error?.requestId,
                    workUnitKind = TranslationWorkUnitKind,
                    workUnitId = batchId,
                    operationOrdinal = operationOrdinal,
                    retryOrdinal = networkAttempt.attemptNumber,
                    requestBytes = response?.requestBytes,
                    responseBytes = response?.responseBytes ?: raw?.size?.toLong(),
                    httpStatus = response?.httpStatus ?: error?.httpStatus,
                    inputTokens = response?.usage?.inputTokens,
                    outputTokens = response?.usage?.outputTokens,
                    totalTokens = response?.usage?.totalTokens,
                    costUsd = response?.usage?.costUsd,
                    rawResponseFileName = rawFile,
                    errorCategory = error?.category?.name,
                    errorMessage = error?.diagnosticDetails()?.take(600),
                    startedAtMs = networkAttempt.startedAtMs ?: timestamp,
                    finishedAtMs = networkAttempt.finishedAtMs ?: timestamp,
                    durationMs = networkAttempt.durationMs,
                    retainUntilMs = timestamp + RawRetentionMs,
                ),
            )
        }
    }

    private suspend fun recordSourcePreparationAttempt(
        attempt: GenerationAttemptEntity,
        protocol: TranslationProtocol,
        networkAttempt: TranslationNetworkAttempt,
    ) {
        val response = networkAttempt.response
        val error = networkAttempt.error
        val raw = response?.rawResponse ?: error?.rawResponse
        val requestAttemptId = newId()
        val rawFile = raw?.let { rawResponses.write("$requestAttemptId.json", it) }
        val timestamp = nowMs()
        database.withWriteTransaction {
            val sequence = dao.nextRequestSequence(attempt.attemptId)
            dao.upsertRequestAttempt(
                RequestAttemptEntity(
                    requestAttemptId = requestAttemptId,
                    attemptId = attempt.attemptId,
                    sequence = sequence,
                    stage = GenerationStage.PreparingSource.name,
                    provider = protocol.name,
                    providerRequestId = response?.requestId ?: error?.requestId,
                    workUnitKind = SourcePreparationWorkUnitKind,
                    workUnitId = attempt.attemptId,
                    operationOrdinal = 1,
                    retryOrdinal = networkAttempt.attemptNumber,
                    requestBytes = response?.requestBytes,
                    responseBytes = response?.responseBytes ?: raw?.size?.toLong(),
                    httpStatus = response?.httpStatus ?: error?.httpStatus,
                    inputTokens = response?.usage?.inputTokens,
                    outputTokens = response?.usage?.outputTokens,
                    totalTokens = response?.usage?.totalTokens,
                    costUsd = response?.usage?.costUsd,
                    rawResponseFileName = rawFile,
                    errorCategory = error?.category?.name,
                    errorMessage = error?.diagnosticDetails()?.take(600),
                    startedAtMs = networkAttempt.startedAtMs ?: timestamp,
                    finishedAtMs = networkAttempt.finishedAtMs ?: timestamp,
                    durationMs = networkAttempt.durationMs,
                    retainUntilMs = timestamp + RawRetentionMs,
                ),
            )
        }
    }

    private suspend fun recordAsrAttempt(
        attempt: GenerationAttemptEntity,
        windowId: String,
        retryOrdinal: Int,
        result: AsrWindowResult?,
        error: AsrException?,
        startedAtMs: Long,
        finishedAtMs: Long,
        durationMs: Long,
    ) {
        val raw = result?.rawResponse ?: error?.rawResponse
        val requestAttemptId = newId()
        val rawFile = raw?.let { rawResponses.write("$requestAttemptId.json", it) }
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
                    providerRequestId = result?.requestId ?: error?.requestId,
                    workUnitKind = AsrWorkUnitKind,
                    workUnitId = windowId,
                    operationOrdinal = 1,
                    retryOrdinal = retryOrdinal,
                    requestBytes = result?.requestBytes,
                    responseBytes = result?.responseBytes ?: raw?.size?.toLong(),
                    httpStatus = result?.httpStatus ?: error?.httpStatus,
                    inputTokens = result?.usage?.inputTokens,
                    outputTokens = result?.usage?.outputTokens,
                    totalTokens = result?.usage?.totalTokens,
                    costUsd = result?.usage?.costUsd,
                    rawResponseFileName = rawFile,
                    errorCategory = error?.category?.name,
                    errorMessage = error?.diagnosticDetails()?.take(600),
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAtMs,
                    durationMs = durationMs,
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

    private fun verifySourcePreparationPolicySnapshot(
        attempt: GenerationAttemptEntity,
        policy: SourcePreparationPolicy,
    ) {
        if (
            attempt.sourcePreparationPolicyVersion != policy.version ||
            attempt.sourcePreparationPromptSha256 != policy.promptSha256
        ) {
            throw PolicyIntegrityException("Task source preparation policy snapshot no longer matches packaged policy assets")
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

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    companion object {
        private const val AsrParallelism = 2
        private const val TranslationParallelism = 4
        private const val MaxAsrAttempts = 3
        private const val RawRetentionMs = 7L * 24L * 60L * 60L * 1_000L
        private const val LeaseHeartbeatMs = 30_000L
        private const val SourcePreparationProgress = 0.52f
        private const val SourceProgress = 0.55f
        private const val TranslationProgressRange = 0.42f
        private const val CompletedState = "Completed"
        private const val RunningState = "Running"
        private const val FailedState = "Failed"
        private const val SplitState = "Split"
        private const val AsrWorkUnitKind = "AsrWindow"
        private const val SourcePreparationWorkUnitKind = "SourcePreparation"
        private const val TranslationWorkUnitKind = "TranslationBatch"
    }
}

internal object AsrRetryPolicy {
    fun delayMs(
        error: AsrException,
        failedAttemptIndex: Int,
    ): Long = error.retryAfterMs ?: (BaseDelayMs * (1L shl failedAttemptIndex.coerceIn(0, 6)))

    fun shouldSplit(
        error: AsrException,
        window: AsrWindowPlan,
    ): Boolean = error.shouldSplit && window.parentWindowId == null

    private const val BaseDelayMs = 500L
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

private class CheckpointIntegrityException(message: String) : IOException(message)

private data class SourceWork(
    val cues: List<SubtitleCue>,
    val language: String?,
    val usage: UsageRecord,
    val resultId: String? = null,
    val revisionId: String? = null,
    val sourceContext: SourceContext = SourceContext.Empty,
    val sourceModel: String? = null,
    val coveredEndMs: Long? = null,
    val completedWindowCount: Int? = null,
    val totalWindowCount: Int? = null,
)

private data class WindowTranscript(
    val cues: List<SubtitleCue>,
    val language: String?,
    val usage: UsageRecord,
    val artifactId: String,
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
        transcriptArtifactId = null,
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
        resultArtifactId = null,
        resultSha256 = null,
        cueCount = null,
        inputTokens = null,
        outputTokens = null,
        totalTokens = null,
        costUsd = null,
        errorMessage = null,
    )

private fun TranslationBatchEntity.usage() =
    UsageRecord(inputTokens, outputTokens, totalTokens, costUsd)

private fun SourcePreparationEntity.usage() =
    UsageRecord(inputTokens, outputTokens, totalTokens, costUsd)

private fun TranslationOutputException.diagnosticIssueSummary(): String {
    val counts =
        issues.groupingBy(TranslationValidationIssue::code).eachCount().entries
            .sortedBy { it.key.name }
            .joinToString(",") { "${it.key.name}=${it.value}" }
    return listOfNotNull(
        counts.takeIf(String::isNotBlank)?.let { "issues=$it" },
        normalizedIdCount.takeIf { it > 0 }?.let { "normalizedIds=$it" },
    ).joinToString(";").ifBlank { "issues=Unknown" }
}

private fun TranslationProtocolException.diagnosticDetails(): String =
    diagnosticErrorDetails(message, phase?.name, cause)

private fun AsrException.diagnosticDetails(): String =
    diagnosticErrorDetails(message, (cause as? TranslationProtocolException)?.phase?.name, cause)

private fun diagnosticErrorDetails(
    message: String?,
    phase: String?,
    cause: Throwable?,
): String =
    listOfNotNull(
        message?.takeIf(String::isNotBlank),
        phase?.let { "phase=$it" },
        cause?.rootCauseType()?.let { "cause=$it" },
    ).joinToString("; ")

private fun Throwable.rootCauseType(): String {
    var current = this
    val visited = mutableSetOf<Throwable>()
    while (visited.add(current)) {
        val next = current.cause ?: break
        current = next
    }
    return current::class.simpleName ?: current.javaClass.name
}

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
