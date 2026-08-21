package io.github.zvensmoluya.vortexcue.synctext

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncTextDiagnosticsTest {
    private lateinit var database: VortexDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `room diagnostics are bounded structured and prunable`() = runBlocking {
        var nowMs = 100L
        var elapsedMs = 500L
        var nextId = 0
        val recorder =
            RoomSyncTextDiagnosticRecorder(
                database = database,
                scope = scope,
                nowMs = { nowMs },
                elapsedRealtimeMs = { elapsedMs },
                newId = { "diagnostic-${nextId++}" },
            )

        recorder.record(
            SyncTextDiagnosticEventType.WorkUnitLifecycle,
            taskId = "task",
            attemptId = "attempt",
            workUnitKind = "AsrWindow",
            workUnitId = "window",
            stage = GenerationStage.RecognizingSpeech.name,
            outcome = "Completed",
            reason = "x".repeat(300),
            valueMs = 42L,
            valueBytes = 1_024L,
        )
        nowMs = 200L
        elapsedMs = 600L
        recorder.record(SyncTextDiagnosticEventType.ResultPublished, taskId = "task", outcome = "Final")

        val events = database.syncTextDao().diagnosticEventsForTask("task")
        assertEquals(2, events.size)
        assertEquals("diagnostic-0", events.first().processSessionId)
        assertEquals(240, events.first().reason?.length)
        assertEquals(42L, events.first().valueMs)
        assertEquals(100L, events.last().elapsedRealtimeMs - events.first().elapsedRealtimeMs)

        recorder.prune(beforeMs = 150L)
        val retained = database.syncTextDao().diagnosticEventsForTask("task")
        assertEquals(1, retained.size)
        assertTrue(retained.single().eventType == SyncTextDiagnosticEventType.ResultPublished.name)
    }
}
