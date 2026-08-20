package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncTextDao {
    @Query("SELECT * FROM sync_text_credential ORDER BY name, credential_id")
    fun observeCredentials(): Flow<List<CredentialMetadataEntity>>

    @Query("SELECT * FROM sync_text_credential ORDER BY name, credential_id")
    suspend fun credentials(): List<CredentialMetadataEntity>

    @Query("SELECT * FROM sync_text_credential WHERE credential_id = :credentialId")
    suspend fun credential(credentialId: String): CredentialMetadataEntity?

    @Query("SELECT credential_id FROM sync_text_credential")
    suspend fun credentialIds(): List<String>

    @Upsert
    suspend fun upsertCredential(entity: CredentialMetadataEntity)

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM sync_text_scheme WHERE asr_credential_id = :credentialId OR translation_credential_id = :credentialId) + " +
            "(SELECT COUNT(*) FROM generation_attempt WHERE asr_credential_id = :credentialId OR translation_credential_id = :credentialId)",
    )
    suspend fun credentialReferenceCount(credentialId: String): Int

    @Query("DELETE FROM sync_text_credential WHERE credential_id = :credentialId")
    suspend fun deleteCredential(credentialId: String): Int

    @Query("SELECT * FROM sync_text_scheme ORDER BY is_default DESC, name, scheme_id")
    fun observeSchemes(): Flow<List<SyncTextSchemeEntity>>

    @Query("SELECT * FROM sync_text_scheme ORDER BY is_default DESC, name, scheme_id")
    suspend fun schemes(): List<SyncTextSchemeEntity>

    @Query("SELECT * FROM sync_text_scheme WHERE scheme_id = :schemeId")
    suspend fun scheme(schemeId: String): SyncTextSchemeEntity?

    @Query("SELECT * FROM sync_text_scheme WHERE is_default = 1 LIMIT 1")
    suspend fun defaultScheme(): SyncTextSchemeEntity?

    @Upsert
    suspend fun upsertScheme(entity: SyncTextSchemeEntity)

    @Query("UPDATE sync_text_scheme SET is_default = 0 WHERE scheme_id != :schemeId")
    suspend fun clearOtherDefaultSchemes(schemeId: String)

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM generation_task WHERE scheme_id = :schemeId) + " +
            "(SELECT COUNT(*) FROM generation_attempt WHERE scheme_id = :schemeId)",
    )
    suspend fun schemeReferenceCount(schemeId: String): Int

    @Query("DELETE FROM sync_text_scheme WHERE scheme_id = :schemeId")
    suspend fun deleteScheme(schemeId: String): Int

    @Query("SELECT * FROM generation_task WHERE state NOT IN ('Completed', 'Deleted') ORDER BY queue_position, created_at_ms")
    fun observeActiveTasks(): Flow<List<GenerationTaskEntity>>

    @Query("SELECT * FROM generation_task WHERE state NOT IN ('Completed', 'Deleted') ORDER BY queue_position, created_at_ms")
    suspend fun activeTasks(): List<GenerationTaskEntity>

    @Query("SELECT * FROM generation_task WHERE task_id = :taskId")
    fun observeTask(taskId: String): Flow<GenerationTaskEntity?>

    @Query("SELECT * FROM generation_task WHERE task_id = :taskId")
    suspend fun task(taskId: String): GenerationTaskEntity?

    @Query("SELECT * FROM generation_task WHERE active_dedupe_key = :dedupeKey LIMIT 1")
    suspend fun activeTaskByDedupeKey(dedupeKey: String): GenerationTaskEntity?

    @Query("SELECT * FROM generation_task WHERE scheme_id = :schemeId AND state NOT IN ('Completed', 'Deleted') ORDER BY queue_position")
    suspend fun activeTasksForScheme(schemeId: String): List<GenerationTaskEntity>

    @Query("SELECT * FROM generation_task WHERE content_id = :contentId AND state NOT IN ('Completed', 'Deleted') ORDER BY queue_position")
    suspend fun activeTasksForContent(contentId: String): List<GenerationTaskEntity>

    @Query("SELECT * FROM generation_task WHERE waiting_for_network = 1 AND state NOT IN ('Completed', 'Deleted') ORDER BY queue_position")
    suspend fun networkWaitingTasks(): List<GenerationTaskEntity>

    @Query("SELECT * FROM generation_task WHERE state = 'Waiting' AND paused = 0 AND needs_attention = 0 AND scheme_blocked = 0 AND waiting_for_network = 0 ORDER BY queue_position, created_at_ms LIMIT 1")
    suspend fun nextRunnableTask(): GenerationTaskEntity?

    @Query("UPDATE generation_task SET state = 'Waiting', waiting_for_network = 0 WHERE state = 'Running' AND paused = 0 AND needs_attention = 0")
    suspend fun recoverInterruptedTasks(): Int

    @Query("SELECT COALESCE(MAX(queue_position), -1) FROM generation_task WHERE state NOT IN ('Completed', 'Deleted')")
    suspend fun maxQueuePosition(): Int

    @Insert
    suspend fun insertTask(entity: GenerationTaskEntity)

    @Upsert
    suspend fun upsertTask(entity: GenerationTaskEntity)

    @Query("DELETE FROM generation_task WHERE task_id = :taskId")
    suspend fun deleteTask(taskId: String): Int

    @Query("DELETE FROM generation_task WHERE state IN ('Completed', 'Deleted') AND updated_at_ms < :beforeMs")
    suspend fun pruneTerminalTasks(beforeMs: Long): Int

    @Query("SELECT * FROM generation_task_snapshot WHERE task_id = :taskId")
    suspend fun taskSnapshot(taskId: String): GenerationTaskSnapshotEntity?

    @Insert
    suspend fun insertTaskSnapshot(entity: GenerationTaskSnapshotEntity)

    @Query("SELECT * FROM generation_attempt WHERE attempt_id = :attemptId")
    suspend fun attempt(attemptId: String): GenerationAttemptEntity?

    @Query("SELECT * FROM generation_attempt WHERE task_id = :taskId ORDER BY ordinal")
    suspend fun attemptsForTask(taskId: String): List<GenerationAttemptEntity>

    @Insert
    suspend fun insertAttempt(entity: GenerationAttemptEntity)

    @Upsert
    suspend fun upsertAttempt(entity: GenerationAttemptEntity)

    @Query("DELETE FROM translation_batch WHERE attempt_id = :attemptId")
    suspend fun deleteTranslationBatches(attemptId: String)

    @Query("SELECT * FROM asr_window WHERE attempt_id = :attemptId ORDER BY ordinal")
    suspend fun asrWindows(attemptId: String): List<AsrWindowEntity>

    @Query("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM asr_window WHERE attempt_id = :attemptId")
    suspend fun nextAsrWindowOrdinal(attemptId: String): Int

    @Upsert
    suspend fun upsertAsrWindows(entities: List<AsrWindowEntity>)

    @Upsert
    suspend fun upsertAsrWindow(entity: AsrWindowEntity)

    @Query("SELECT * FROM translation_batch WHERE attempt_id = :attemptId ORDER BY ordinal")
    suspend fun translationBatches(attemptId: String): List<TranslationBatchEntity>

    @Query("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM translation_batch WHERE attempt_id = :attemptId")
    suspend fun nextTranslationBatchOrdinal(attemptId: String): Int

    @Upsert
    suspend fun upsertTranslationBatches(entities: List<TranslationBatchEntity>)

    @Upsert
    suspend fun upsertTranslationBatch(entity: TranslationBatchEntity)

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM sync_text_request_attempt WHERE attempt_id = :attemptId")
    suspend fun nextRequestSequence(attemptId: String): Int

    @Upsert
    suspend fun upsertRequestAttempt(entity: RequestAttemptEntity)

    @Query("DELETE FROM sync_text_request_attempt WHERE retain_until_ms IS NOT NULL AND retain_until_ms < :nowMs")
    suspend fun pruneExpiredRequestAttempts(nowMs: Long): Int

    @Query("SELECT raw_response_file_name FROM sync_text_request_attempt WHERE raw_response_file_name IS NOT NULL")
    suspend fun referencedRawResponseFiles(): List<String>

    @Query("SELECT * FROM generated_text_slot WHERE slot_id = :slotId")
    suspend fun generatedSlot(slotId: String): GeneratedTextSlotEntity?

    @Query("SELECT * FROM generated_text_slot WHERE slot_id = :slotId")
    fun observeGeneratedSlot(slotId: String): Flow<GeneratedTextSlotEntity?>

    @Query("SELECT * FROM generated_text_slot WHERE content_id = :contentId AND target_key = :targetKey")
    suspend fun generatedSlot(contentId: String, targetKey: String): GeneratedTextSlotEntity?

    @Query("SELECT * FROM generated_text_slot WHERE content_id = :contentId ORDER BY target_key")
    fun observeGeneratedSlots(contentId: String): Flow<List<GeneratedTextSlotEntity>>

    @Query("SELECT * FROM generated_text_slot ORDER BY content_id, target_key")
    suspend fun generatedSlots(): List<GeneratedTextSlotEntity>

    @Upsert
    suspend fun upsertGeneratedSlot(entity: GeneratedTextSlotEntity)

    @Query("SELECT * FROM generated_text_result WHERE result_id = :resultId")
    suspend fun generatedResult(resultId: String): GeneratedTextResultEntity?

    @Query("SELECT * FROM generated_text_result ORDER BY created_at_ms, result_id")
    suspend fun generatedResults(): List<GeneratedTextResultEntity>

    @Query("SELECT r.* FROM generated_text_result r INNER JOIN generated_text_slot s ON s.current_result_id = r.result_id WHERE s.slot_id = :slotId")
    suspend fun currentGeneratedResult(slotId: String): GeneratedTextResultEntity?

    @Query("SELECT r.* FROM generated_text_result r INNER JOIN generated_text_slot s ON s.current_result_id = r.result_id WHERE s.slot_id = :slotId")
    fun observeCurrentGeneratedResult(slotId: String): Flow<GeneratedTextResultEntity?>

    @Insert
    suspend fun insertGeneratedResult(entity: GeneratedTextResultEntity)

    @Query("DELETE FROM generated_text_result WHERE result_id = :resultId")
    suspend fun deleteGeneratedResult(resultId: String): Int

    @Query("SELECT COUNT(*) FROM generation_task WHERE source_result_id = :resultId AND state NOT IN ('Completed', 'Deleted')")
    suspend fun activeSourceResultReferenceCount(resultId: String): Int

    @Query(
        "SELECT w.transcript_artifact_id FROM asr_window w " +
            "INNER JOIN generation_attempt a ON a.attempt_id = w.attempt_id " +
            "INNER JOIN generation_task t ON t.task_id = a.task_id " +
            "WHERE w.transcript_artifact_id IS NOT NULL AND t.state NOT IN ('Completed', 'Deleted') " +
            "UNION SELECT b.result_artifact_id FROM translation_batch b " +
            "INNER JOIN generation_attempt a ON a.attempt_id = b.attempt_id " +
            "INNER JOIN generation_task t ON t.task_id = a.task_id " +
            "WHERE b.result_artifact_id IS NOT NULL AND t.state NOT IN ('Completed', 'Deleted')",
    )
    suspend fun recoverableWorkArtifactIds(): List<String>

    @Query(
        "UPDATE asr_window SET transcript_artifact_id = NULL " +
            "WHERE transcript_artifact_id = :artifactId AND attempt_id IN (" +
            "SELECT a.attempt_id FROM generation_attempt a INNER JOIN generation_task t ON t.task_id = a.task_id " +
            "WHERE t.state IN ('Completed', 'Deleted'))",
    )
    suspend fun clearTerminalAsrArtifactReference(artifactId: String): Int

    @Query(
        "UPDATE translation_batch SET result_artifact_id = NULL " +
            "WHERE result_artifact_id = :artifactId AND attempt_id IN (" +
            "SELECT a.attempt_id FROM generation_attempt a INNER JOIN generation_task t ON t.task_id = a.task_id " +
            "WHERE t.state IN ('Completed', 'Deleted'))",
    )
    suspend fun clearTerminalTranslationArtifactReference(artifactId: String): Int

    @Query(
        "UPDATE generation_task SET state = 'NeedsAttention', needs_attention = 1, waiting_for_network = 0, " +
            "error_category = 'Storage', error_message = :message, updated_at_ms = :updatedAtMs " +
            "WHERE task_id = :taskId AND state != 'Deleted'",
    )
    suspend fun markTaskStorageNeedsAttention(taskId: String, message: String, updatedAtMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRunnerLease(entity: SyncTextRunnerLeaseEntity): Long

    @Query(
        "UPDATE sync_text_runner_lease SET owner_id = :ownerId, expires_at_ms = :expiresAtMs, generation = generation + 1 " +
            "WHERE lease_key = :leaseKey AND (owner_id = :ownerId OR expires_at_ms <= :nowMs)",
    )
    suspend fun tryAcquireRunnerLease(
        leaseKey: String,
        ownerId: String,
        nowMs: Long,
        expiresAtMs: Long,
    ): Int

    @Query("UPDATE sync_text_runner_lease SET expires_at_ms = :nowMs WHERE lease_key = :leaseKey AND owner_id = :ownerId")
    suspend fun releaseRunnerLease(leaseKey: String, ownerId: String, nowMs: Long): Int

    @Query("SELECT * FROM sync_text_queue_control WHERE id = 1")
    fun observeQueueControl(): Flow<SyncTextQueueControlEntity?>

    @Query("SELECT * FROM sync_text_queue_control WHERE id = 1")
    suspend fun queueControl(): SyncTextQueueControlEntity?

    @Upsert
    suspend fun upsertQueueControl(entity: SyncTextQueueControlEntity)
}
