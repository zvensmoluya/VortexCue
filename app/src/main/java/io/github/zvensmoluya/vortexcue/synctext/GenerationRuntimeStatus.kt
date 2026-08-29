package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.data.db.GenerationRuntimeStatusEntity
import io.github.zvensmoluya.vortexcue.data.db.SyncTextDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists transient execution facts separately from committed task progress.
 *
 * Runtime rows may survive process death. A waiting task always presents as queued; once the runner
 * resumes, the next real phase replaces the stale row without resetting committed work units.
 */
internal class GenerationRuntimeStatusWriter(
    private val dao: SyncTextDao,
    private val nowMs: () -> Long,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, GenerationRuntimeStatusEntity>()
    private val lastPersistedAtMs = mutableMapOf<String, Long>()
    private val dirtyRuntimeIds = mutableSetOf<String>()

    suspend fun updateTask(
        taskId: String,
        phase: GenerationRuntimePhase,
    ) = update(taskId, phase, workUnitKind = null, workUnitId = null)

    suspend fun updateWorkUnit(
        taskId: String,
        phase: GenerationRuntimePhase,
        workUnitKind: GenerationWorkUnitKind,
        workUnitId: String,
        observedItemCount: Int? = null,
        expectedItemCount: Int? = null,
        operationOrdinal: Int? = null,
        retryOrdinal: Int? = null,
    ) = update(
        taskId = taskId,
        phase = phase,
        workUnitKind = workUnitKind,
        workUnitId = workUnitId,
        observedItemCount = observedItemCount,
        expectedItemCount = expectedItemCount,
        operationOrdinal = operationOrdinal,
        retryOrdinal = retryOrdinal,
    )

    suspend fun recordTaskStreamActivity(taskId: String, delta: String) {
        if (delta.isEmpty()) return
        val addedUnits = delta.sumOf { character -> if (character.code < 128) 1 else 4 }
        val runtimeId = runtimeId(taskId, null, null)
        mutex.withLock {
            val previous = cachedOrStored(runtimeId)
            val timestamp = nowMs()
            val next =
                GenerationRuntimeStatusEntity(
                    runtimeId = runtimeId,
                    taskId = taskId,
                    workUnitKind = null,
                    workUnitId = null,
                    phase = previous?.phase ?: GenerationRuntimePhase.PreparingTranslation.name,
                    observedItemCount = (previous?.observedItemCount ?: 0).saturatingAdd(addedUnits),
                    expectedItemCount = previous?.expectedItemCount,
                    operationOrdinal = previous?.operationOrdinal,
                    retryOrdinal = previous?.retryOrdinal,
                    phaseStartedAtMs = previous?.phaseStartedAtMs ?: timestamp,
                    updatedAtMs = timestamp,
                )
            cache[runtimeId] = next
            val lastPersisted = lastPersistedAtMs[runtimeId] ?: previous?.updatedAtMs
            val firstVisibleActivity = (previous?.observedItemCount ?: 0) == 0
            if (firstVisibleActivity || lastPersisted == null || timestamp - lastPersisted >= ActivityPublishIntervalMs) {
                persist(next)
            } else {
                dirtyRuntimeIds += runtimeId
            }
        }
    }

    suspend fun flushTaskStreamActivity(taskId: String) {
        val runtimeId = runtimeId(taskId, null, null)
        mutex.withLock {
            if (runtimeId !in dirtyRuntimeIds) return@withLock
            cache[runtimeId]?.let { persist(it) }
        }
    }

    private suspend fun update(
        taskId: String,
        phase: GenerationRuntimePhase,
        workUnitKind: GenerationWorkUnitKind?,
        workUnitId: String?,
        observedItemCount: Int? = null,
        expectedItemCount: Int? = null,
        operationOrdinal: Int? = null,
        retryOrdinal: Int? = null,
    ) {
        val runtimeId = runtimeId(taskId, workUnitKind, workUnitId)
        mutex.withLock {
            val previous = cachedOrStored(runtimeId)
            val timestamp = nowMs()
            val next =
                GenerationRuntimeStatusEntity(
                    runtimeId = runtimeId,
                    taskId = taskId,
                    workUnitKind = workUnitKind?.name,
                    workUnitId = workUnitId,
                    phase = phase.name,
                    observedItemCount = observedItemCount ?: previous?.observedItemCount,
                    expectedItemCount = expectedItemCount ?: previous?.expectedItemCount,
                    operationOrdinal = operationOrdinal,
                    retryOrdinal = retryOrdinal,
                    phaseStartedAtMs =
                        if (previous?.phase == phase.name) previous.phaseStartedAtMs else timestamp,
                    updatedAtMs = timestamp,
                )
            if (previous?.sameVisibleFact(next) == true) return@withLock
            cache[runtimeId] = next
            persist(next)
        }
    }

    private suspend fun cachedOrStored(runtimeId: String): GenerationRuntimeStatusEntity? =
        cache[runtimeId] ?: dao.runtimeStatus(runtimeId)?.also { stored ->
            cache[runtimeId] = stored
            lastPersistedAtMs[runtimeId] = stored.updatedAtMs
        }

    private suspend fun persist(entity: GenerationRuntimeStatusEntity) {
        dao.upsertRuntimeStatus(entity)
        lastPersistedAtMs[entity.runtimeId] = entity.updatedAtMs
        dirtyRuntimeIds -= entity.runtimeId
    }

    private companion object {
        const val ActivityPublishIntervalMs = 250L
    }
}

private fun runtimeId(
    taskId: String,
    workUnitKind: GenerationWorkUnitKind?,
    workUnitId: String?,
): String =
    if (workUnitId == null) {
        "task:$taskId"
    } else {
        "${requireNotNull(workUnitKind).name}:$workUnitId"
    }

private fun GenerationRuntimeStatusEntity.sameVisibleFact(other: GenerationRuntimeStatusEntity): Boolean =
    taskId == other.taskId &&
        workUnitKind == other.workUnitKind &&
        workUnitId == other.workUnitId &&
        phase == other.phase &&
        observedItemCount == other.observedItemCount &&
        expectedItemCount == other.expectedItemCount &&
        operationOrdinal == other.operationOrdinal &&
        retryOrdinal == other.retryOrdinal

private fun Int.saturatingAdd(other: Int): Int =
    if (this > Int.MAX_VALUE - other) Int.MAX_VALUE else this + other
