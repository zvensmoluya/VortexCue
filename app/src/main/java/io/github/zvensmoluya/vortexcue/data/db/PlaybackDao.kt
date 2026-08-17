package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM current_playback_session WHERE singleton_id = 1")
    suspend fun session(): CurrentPlaybackSessionEntity?

    @Query("SELECT * FROM current_playback_session WHERE singleton_id = 1")
    fun observeSession(): Flow<CurrentPlaybackSessionEntity?>

    @Upsert
    suspend fun upsertSession(entity: CurrentPlaybackSessionEntity)

    @Query("SELECT * FROM current_queue_entry ORDER BY position")
    suspend fun queueEntries(): List<CurrentQueueEntryEntity>

    @Query("SELECT * FROM current_queue_entry ORDER BY position")
    fun observeQueueEntries(): Flow<List<CurrentQueueEntryEntity>>

    @Query(QUEUE_SELECT + " ORDER BY q.position")
    suspend fun queueItems(): List<QueueItemRow>

    @Query(QUEUE_SELECT + " ORDER BY q.position")
    fun observeQueueItems(): Flow<List<QueueItemRow>>

    @Upsert
    suspend fun upsertQueueEntries(entries: List<CurrentQueueEntryEntity>)

    @Query("DELETE FROM current_queue_entry")
    suspend fun clearQueueEntries()

    @Query(
        "UPDATE current_playback_session SET position_ms = :positionMs, duration_ms = :durationMs, " +
            "playback_speed = :playbackSpeed, updated_at_ms = :updatedAtMs " +
            "WHERE singleton_id = 1 AND queue_revision = :expectedRevision " +
            "AND ((current_content_id IS NULL AND :contentId IS NULL) OR current_content_id = :contentId)",
    )
    suspend fun updateProgress(
        contentId: String?,
        positionMs: Long,
        durationMs: Long,
        playbackSpeed: Float,
        expectedRevision: Long,
        updatedAtMs: Long,
    ): Int

    companion object {
        const val QUEUE_SELECT =
            "SELECT q.position AS position, c.content_id AS contentId, l.locator_id AS locatorId, " +
                "l.uri_value AS uriValue, l.display_name AS displayName, l.mime_type AS mimeType, " +
                "l.title AS title, l.artist AS artist, l.album AS album, l.duration_ms AS durationMs, " +
                "l.folder AS folder, l.date_added_seconds AS dateAddedSeconds, l.origin AS origin, " +
                "l.source_id AS sourceId, l.kind AS kind, l.size_bytes AS sizeBytes, " +
                "l.video_width AS videoWidth, l.video_height AS videoHeight, " +
                "l.availability AS availability " +
                "FROM current_queue_entry q " +
                "INNER JOIN content c ON c.content_id = q.content_id " +
                "INNER JOIN active_media_locator a ON a.content_id = c.content_id " +
                "INNER JOIN media_locator l ON l.locator_id = a.locator_id"
    }
}
