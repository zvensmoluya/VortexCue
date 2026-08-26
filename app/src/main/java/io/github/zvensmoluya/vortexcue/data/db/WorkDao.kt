package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkDao {
    @Query("SELECT * FROM work ORDER BY COALESCE(last_played_at_ms, created_at_ms) DESC, work_id")
    fun observeWorks(): Flow<List<WorkEntity>>

    @Query("SELECT * FROM work WHERE work_id = :workId")
    suspend fun work(workId: String): WorkEntity?

    @Query("SELECT * FROM work WHERE work_id = :workId")
    fun observeWork(workId: String): Flow<WorkEntity?>

    @Query("SELECT * FROM work WHERE source_key = :sourceKey LIMIT 1")
    suspend fun workBySourceKey(sourceKey: String): WorkEntity?

    @Query("SELECT * FROM work WHERE source_key = :sourceKey LIMIT 1")
    fun observeWorkBySourceKey(sourceKey: String): Flow<WorkEntity?>

    @Query("SELECT * FROM work_part WHERE work_id = :workId ORDER BY sort_position, content_id")
    suspend fun parts(workId: String): List<WorkPartEntity>

    @Query("SELECT * FROM work_part WHERE work_id = :workId ORDER BY sort_position, content_id")
    fun observeParts(workId: String): Flow<List<WorkPartEntity>>

    @Query(WORK_PART_SELECT + " WHERE wp.work_id = :workId ORDER BY wp.sort_position, wp.content_id")
    suspend fun partRows(workId: String): List<WorkPartRow>

    @Query(WORK_PART_SELECT + " WHERE wp.work_id = :workId ORDER BY wp.sort_position, wp.content_id")
    fun observePartRows(workId: String): Flow<List<WorkPartRow>>

    @Query(WORK_PART_SELECT + " ORDER BY wp.work_id, wp.sort_position, wp.content_id")
    fun observeAllPartRows(): Flow<List<WorkPartRow>>

    @Query("SELECT * FROM work_part WHERE content_id = :contentId ORDER BY work_id")
    suspend fun partsForContent(contentId: String): List<WorkPartEntity>

    @Query("SELECT * FROM work_progress WHERE work_id = :workId")
    suspend fun progress(workId: String): WorkProgressEntity?

    @Query("SELECT * FROM work_progress WHERE content_id = :contentId")
    suspend fun progressForContent(contentId: String): List<WorkProgressEntity>

    @Query("SELECT * FROM work_progress WHERE work_id = :workId")
    fun observeProgress(workId: String): Flow<WorkProgressEntity?>

    @Query("SELECT * FROM work_progress ORDER BY updated_at_ms DESC")
    fun observeAllProgress(): Flow<List<WorkProgressEntity>>

    @Upsert
    suspend fun upsertWork(entity: WorkEntity)

    @Upsert
    suspend fun upsertParts(entities: List<WorkPartEntity>)

    @Upsert
    suspend fun upsertPart(entity: WorkPartEntity)

    @Upsert
    suspend fun upsertProgress(entity: WorkProgressEntity)

    @Query("DELETE FROM work_part WHERE work_id = :workId")
    suspend fun clearParts(workId: String)

    @Query("DELETE FROM work_part WHERE work_id = :workId AND content_id = :contentId")
    suspend fun deletePart(workId: String, contentId: String)

    @Query("DELETE FROM work WHERE work_id = :workId")
    suspend fun deleteWork(workId: String)

    @Query(
        "UPDATE work SET last_played_at_ms = :playedAtMs, updated_at_ms = :playedAtMs " +
            "WHERE work_id = :workId",
    )
    suspend fun markPlayed(workId: String, playedAtMs: Long): Int

    companion object {
        const val WORK_PART_SELECT =
            "SELECT wp.work_id AS workId, wp.content_id AS contentId, wp.section_name AS sectionName, " +
                "wp.variant_key AS variantKey, wp.sort_position AS sortPosition, wp.is_included AS isIncluded, " +
                "l.locator_id AS locatorId, l.uri_value AS uriValue, l.display_name AS displayName, " +
                "l.mime_type AS mimeType, l.title AS title, l.artist AS artist, l.album AS album, " +
                "l.duration_ms AS durationMs, l.folder AS folder, l.date_added_seconds AS dateAddedSeconds, " +
                "l.origin AS origin, l.source_id AS sourceId, l.kind AS kind, l.size_bytes AS sizeBytes, " +
                "l.video_width AS videoWidth, l.video_height AS videoHeight, l.availability AS availability, " +
                "d.is_collected AS isCollected, r.last_opened_at_ms AS lastOpenedAtMs " +
                "FROM work_part wp " +
                "INNER JOIN active_media_locator a ON a.content_id = wp.content_id " +
                "INNER JOIN media_locator l ON l.locator_id = a.locator_id " +
                "LEFT JOIN content_disposition d ON d.content_id = wp.content_id " +
                "LEFT JOIN recent_entry r ON r.content_id = wp.content_id"
    }
}
