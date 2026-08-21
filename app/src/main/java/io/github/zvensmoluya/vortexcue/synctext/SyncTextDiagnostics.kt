package io.github.zvensmoluya.vortexcue.synctext

import android.os.SystemClock
import io.github.zvensmoluya.vortexcue.data.db.SyncTextDiagnosticEventEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class SyncTextDiagnosticEventType {
    UiAction,
    TaskSubmitted,
    TaskStateChanged,
    NetworkWaitChanged,
    MeteredNetworkAllowed,
    RunnerLease,
    RunnerLifecycle,
    WorkUnitLifecycle,
    ResultPublished,
    ScheduleOutcome,
}

interface SyncTextDiagnosticRecorder {
    suspend fun record(
        type: SyncTextDiagnosticEventType,
        taskId: String? = null,
        attemptId: String? = null,
        workUnitKind: String? = null,
        workUnitId: String? = null,
        stage: String? = null,
        outcome: String? = null,
        reason: String? = null,
        valueMs: Long? = null,
        valueBytes: Long? = null,
    )

    fun recordAsync(
        type: SyncTextDiagnosticEventType,
        taskId: String? = null,
        attemptId: String? = null,
        workUnitKind: String? = null,
        workUnitId: String? = null,
        stage: String? = null,
        outcome: String? = null,
        reason: String? = null,
        valueMs: Long? = null,
        valueBytes: Long? = null,
    )
}

object NoOpSyncTextDiagnosticRecorder : SyncTextDiagnosticRecorder {
    override suspend fun record(
        type: SyncTextDiagnosticEventType,
        taskId: String?,
        attemptId: String?,
        workUnitKind: String?,
        workUnitId: String?,
        stage: String?,
        outcome: String?,
        reason: String?,
        valueMs: Long?,
        valueBytes: Long?,
    ) = Unit

    override fun recordAsync(
        type: SyncTextDiagnosticEventType,
        taskId: String?,
        attemptId: String?,
        workUnitKind: String?,
        workUnitId: String?,
        stage: String?,
        outcome: String?,
        reason: String?,
        valueMs: Long?,
        valueBytes: Long?,
    ) = Unit
}

class RoomSyncTextDiagnosticRecorder(
    database: VortexDatabase,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SyncTextDiagnosticRecorder {
    private val dao = database.syncTextDao()
    private val processSessionId = newId()

    override suspend fun record(
        type: SyncTextDiagnosticEventType,
        taskId: String?,
        attemptId: String?,
        workUnitKind: String?,
        workUnitId: String?,
        stage: String?,
        outcome: String?,
        reason: String?,
        valueMs: Long?,
        valueBytes: Long?,
    ) {
        try {
            dao.insertDiagnosticEvent(
                SyncTextDiagnosticEventEntity(
                    eventId = newId(),
                    processSessionId = processSessionId,
                    eventType = type.name,
                    taskId = taskId,
                    attemptId = attemptId,
                    workUnitKind = workUnitKind,
                    workUnitId = workUnitId,
                    stage = stage,
                    outcome = outcome,
                    reason = reason?.take(MaxDiagnosticReasonLength),
                    valueMs = valueMs,
                    valueBytes = valueBytes,
                    createdAtMs = nowMs(),
                    elapsedRealtimeMs = elapsedRealtimeMs(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics must never change task behavior.
        }
    }

    override fun recordAsync(
        type: SyncTextDiagnosticEventType,
        taskId: String?,
        attemptId: String?,
        workUnitKind: String?,
        workUnitId: String?,
        stage: String?,
        outcome: String?,
        reason: String?,
        valueMs: Long?,
        valueBytes: Long?,
    ) {
        scope.launch {
            record(
                type = type,
                taskId = taskId,
                attemptId = attemptId,
                workUnitKind = workUnitKind,
                workUnitId = workUnitId,
                stage = stage,
                outcome = outcome,
                reason = reason,
                valueMs = valueMs,
                valueBytes = valueBytes,
            )
        }
    }

    suspend fun prune(beforeMs: Long) {
        dao.pruneDiagnosticEvents(beforeMs)
    }

    private companion object {
        const val MaxDiagnosticReasonLength = 240
    }
}
