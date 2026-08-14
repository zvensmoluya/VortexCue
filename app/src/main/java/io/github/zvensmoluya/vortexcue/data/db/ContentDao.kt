package io.github.zvensmoluya.vortexcue.data.db

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import kotlinx.coroutines.flow.Flow

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface ContentDao {
    @Query("SELECT * FROM content WHERE content_id = :contentId")
    suspend fun content(contentId: String): ContentEntity?

    @Query("SELECT * FROM content WHERE content_id = :contentId")
    fun observeContent(contentId: String): Flow<ContentEntity?>

    @Query("SELECT content_id FROM content_identity WHERE identity_key = :identityKey")
    suspend fun contentIdForIdentity(identityKey: String): String?

    @Query("SELECT canonical_content_id FROM content_redirect WHERE alias_content_id = :contentId")
    suspend fun redirectTarget(contentId: String): String?

    @Query("SELECT * FROM content_redirect WHERE canonical_content_id = :contentId")
    suspend fun redirectsTo(contentId: String): List<ContentRedirectEntity>

    @Query("SELECT * FROM content_identity WHERE content_id = :contentId")
    suspend fun identities(contentId: String): List<ContentIdentityEntity>

    @Query("SELECT * FROM media_locator WHERE content_id = :contentId ORDER BY updated_at_ms DESC")
    suspend fun locators(contentId: String): List<MediaLocatorEntity>

    @Query("SELECT * FROM media_locator WHERE content_id = :contentId ORDER BY updated_at_ms DESC")
    fun observeLocators(contentId: String): Flow<List<MediaLocatorEntity>>

    @Query("SELECT * FROM media_locator WHERE locator_id = :locatorId")
    suspend fun locator(locatorId: String): MediaLocatorEntity?

    @Query("SELECT * FROM active_media_locator WHERE content_id = :contentId")
    suspend fun activeLocator(contentId: String): ActiveMediaLocatorEntity?

    @Query("SELECT * FROM active_media_locator WHERE content_id = :contentId")
    fun observeActiveLocator(contentId: String): Flow<ActiveMediaLocatorEntity?>

    @Query("SELECT * FROM timed_text_track WHERE content_id = :contentId ORDER BY track_id")
    suspend fun textTracks(contentId: String): List<TimedTextTrackEntity>

    @Query("SELECT * FROM timed_text_track WHERE content_id = :contentId ORDER BY track_id")
    fun observeTextTracks(contentId: String): Flow<List<TimedTextTrackEntity>>

    @Query(
        "SELECT timed_text_track_role.* FROM timed_text_track_role " +
            "INNER JOIN timed_text_track ON timed_text_track.track_id = timed_text_track_role.track_id " +
            "WHERE timed_text_track.content_id = :contentId ORDER BY timed_text_track_role.track_id, role",
    )
    suspend fun textRoles(contentId: String): List<TimedTextTrackRoleEntity>

    @Query(
        "SELECT timed_text_track_role.* FROM timed_text_track_role " +
            "INNER JOIN timed_text_track ON timed_text_track.track_id = timed_text_track_role.track_id " +
            "WHERE timed_text_track.content_id = :contentId ORDER BY timed_text_track_role.track_id, role",
    )
    fun observeTextRoles(contentId: String): Flow<List<TimedTextTrackRoleEntity>>

    @Query("SELECT * FROM text_selection WHERE content_id = :contentId ORDER BY slot")
    suspend fun textSelections(contentId: String): List<TextSelectionEntity>

    @Query("SELECT * FROM text_selection WHERE content_id = :contentId ORDER BY slot")
    fun observeTextSelections(contentId: String): Flow<List<TextSelectionEntity>>

    @Query("SELECT * FROM content_disposition WHERE content_id = :contentId")
    suspend fun disposition(contentId: String): ContentDispositionEntity?

    @Query("SELECT * FROM content_disposition WHERE content_id = :contentId")
    fun observeDisposition(contentId: String): Flow<ContentDispositionEntity?>

    @Query("SELECT * FROM recent_entry WHERE content_id = :contentId")
    suspend fun recent(contentId: String): RecentEntryEntity?

    @Query("SELECT * FROM playlist_entry WHERE content_id = :contentId ORDER BY playlist_id")
    suspend fun playlistEntriesForContent(contentId: String): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist ORDER BY created_at_ms, playlist_id")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist ORDER BY created_at_ms, playlist_id")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_entry WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun playlistEntries(playlistId: String): List<PlaylistEntryEntity>

    @Query("SELECT * FROM home_shortcut ORDER BY position")
    fun observeHomeShortcuts(): Flow<List<HomeShortcutEntity>>

    @Query("SELECT * FROM source WHERE removed_at_ms IS NULL ORDER BY kind, display_name")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE source_id = :sourceId")
    suspend fun source(sourceId: String): SourceEntity?

    @Query("SELECT * FROM source WHERE root_uri = :rootUri LIMIT 1")
    suspend fun sourceByRootUri(rootUri: String): SourceEntity?

    @Query("SELECT * FROM source WHERE removed_at_ms IS NULL ORDER BY kind, display_name")
    suspend fun sources(): List<SourceEntity>

    @Query(
        "SELECT s.source_id AS sourceId, s.kind AS kind, s.root_uri AS rootUri, " +
            "s.display_name AS displayName, s.has_access AS hasAccess, s.generation AS generation, " +
        "s.last_indexed_at_ms AS lastIndexedAtMs, s.last_error AS lastError, COUNT(a.content_id) AS itemCount " +
        "FROM source s LEFT JOIN media_locator ml ON ml.source_id = s.source_id " +
            "AND ml.availability = 'Available' " +
            "LEFT JOIN active_media_locator a ON a.locator_id = ml.locator_id " +
            "AND a.content_id = ml.content_id " +
            "WHERE s.removed_at_ms IS NULL GROUP BY s.source_id ORDER BY s.kind, s.display_name",
    )
    fun observeSourceSummaries(): Flow<List<SourceSummaryRow>>

    @Query("SELECT COUNT(*) FROM content_disposition WHERE is_collected = 1")
    fun observeCollectedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM content_disposition WHERE is_collected = 0")
    fun observeIgnoredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM content c WHERE NOT EXISTS (SELECT 1 FROM content_disposition d WHERE d.content_id = c.content_id)")
    fun observeDiscoveryCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM recent_entry")
    fun observeRecentCount(): Flow<Int>

    @Query(SUMMARY_SELECT + " WHERE d.is_collected = 1 ORDER BY l.date_added_seconds DESC, c.content_id LIMIT :limit")
    fun observeCollectedPreview(limit: Int): Flow<List<ContentSummaryRow>>

    @Query(SUMMARY_SELECT + " WHERE r.content_id IS NOT NULL ORDER BY r.last_opened_at_ms DESC, c.content_id LIMIT :limit")
    fun observeRecentPreview(limit: Int): Flow<List<ContentSummaryRow>>

    @Query(SUMMARY_SELECT + " WHERE d.is_collected = 1 ORDER BY l.date_added_seconds DESC, c.content_id")
    fun pagedCollected(): PagingSource<Int, ContentSummaryRow>

    @Query(SUMMARY_SELECT + " WHERE r.content_id IS NOT NULL ORDER BY r.last_opened_at_ms DESC, c.content_id")
    fun pagedRecent(): PagingSource<Int, ContentSummaryRow>

    @Query(SUMMARY_SELECT + " WHERE d.content_id IS NULL ORDER BY l.date_added_seconds DESC, c.content_id")
    fun pagedDiscovery(): PagingSource<Int, ContentSummaryRow>

    @Query(SUMMARY_SELECT + " WHERE d.is_collected = 0 ORDER BY d.decided_at_ms DESC, c.content_id")
    fun pagedIgnored(): PagingSource<Int, ContentSummaryRow>

    @Query(SUMMARY_SELECT + " WHERE l.source_id = :sourceId ORDER BY l.date_added_seconds DESC, c.content_id")
    fun pagedSource(sourceId: String): PagingSource<Int, ContentSummaryRow>

    @Query(
        SUMMARY_SELECT +
            " INNER JOIN playlist_entry pe ON pe.content_id = c.content_id " +
            "WHERE pe.playlist_id = :playlistId ORDER BY pe.position",
    )
    fun pagedPlaylist(playlistId: String): PagingSource<Int, ContentSummaryRow>

    @Query(
        SUMMARY_SELECT +
            " WHERE l.display_name LIKE '%' || :query || '%' COLLATE NOCASE " +
            "OR l.title LIKE '%' || :query || '%' COLLATE NOCASE " +
            "OR l.artist LIKE '%' || :query || '%' COLLATE NOCASE " +
            "OR l.album LIKE '%' || :query || '%' COLLATE NOCASE " +
            "ORDER BY l.date_added_seconds DESC, c.content_id",
    )
    fun pagedSearch(query: String): PagingSource<Int, ContentSummaryRow>

    @Query(SUMMARY_SELECT + " WHERE c.content_id = :contentId")
    suspend fun summary(contentId: String): ContentSummaryRow?

    @Query(SUMMARY_SELECT + " WHERE c.content_id = :contentId")
    fun observeSummary(contentId: String): Flow<ContentSummaryRow?>

    @Upsert
    suspend fun upsertContent(entity: ContentEntity)

    @Upsert
    suspend fun upsertContents(entities: List<ContentEntity>)

    @Upsert
    suspend fun upsertIdentity(entity: ContentIdentityEntity)

    @Upsert
    suspend fun upsertIdentities(entities: List<ContentIdentityEntity>)

    @Upsert
    suspend fun upsertRedirect(entity: ContentRedirectEntity)

    @Upsert
    suspend fun upsertSource(entity: SourceEntity)

    @Upsert
    suspend fun upsertLocator(entity: MediaLocatorEntity)

    @Upsert
    suspend fun upsertLocators(entities: List<MediaLocatorEntity>)

    @Upsert
    suspend fun upsertActiveLocator(entity: ActiveMediaLocatorEntity)

    @Upsert
    suspend fun upsertTextTrack(entity: TimedTextTrackEntity)

    @Upsert
    suspend fun upsertTextTracks(entities: List<TimedTextTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTextRoles(entities: List<TimedTextTrackRoleEntity>)

    @Upsert
    suspend fun upsertTextSelection(entity: TextSelectionEntity)

    @Upsert
    suspend fun upsertDisposition(entity: ContentDispositionEntity)

    @Query("DELETE FROM content_disposition WHERE content_id = :contentId")
    suspend fun deleteDisposition(contentId: String)

    @Upsert
    suspend fun upsertRecent(entity: RecentEntryEntity)

    @Query(
        "DELETE FROM recent_entry WHERE content_id IN " +
            "(SELECT content_id FROM recent_entry ORDER BY last_opened_at_ms DESC, content_id LIMIT -1 OFFSET :limit)",
    )
    suspend fun trimRecent(limit: Int)

    @Upsert
    suspend fun upsertPlaylist(entity: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(entity: PlaylistEntity)

    @Upsert
    suspend fun upsertPlaylistEntry(entity: PlaylistEntryEntity)

    @Query("DELETE FROM playlist_entry WHERE playlist_id = :playlistId AND content_id = :contentId")
    suspend fun deletePlaylistEntry(playlistId: String, contentId: String)

    @Query("DELETE FROM playlist_entry WHERE playlist_id = :playlistId")
    suspend fun clearPlaylistEntries(playlistId: String)

    @Upsert
    suspend fun upsertHomeShortcuts(entities: List<HomeShortcutEntity>)

    @Query("DELETE FROM home_shortcut")
    suspend fun clearHomeShortcuts()

    @Upsert
    suspend fun upsertDraft(entity: CurrentDraftEntity)

    @Query("SELECT * FROM current_draft WHERE singleton_id = 1")
    fun observeDraft(): Flow<CurrentDraftEntity?>

    @Query("SELECT * FROM current_draft WHERE singleton_id = 1")
    suspend fun draft(): CurrentDraftEntity?

    @Query("SELECT cached_file_name FROM timed_text_track WHERE cached_file_name IS NOT NULL")
    suspend fun referencedSubtitleCacheFiles(): List<String>

    @Query("DELETE FROM current_draft")
    suspend fun clearDraft()

    @Query("DELETE FROM current_draft WHERE content_id = :contentId")
    suspend fun clearDraftIfContent(contentId: String)

    @Query("DELETE FROM timed_text_track_role WHERE track_id = :trackId")
    suspend fun clearTextRoles(trackId: String)

    @Query("UPDATE text_selection SET selected_track_id = NULL WHERE selected_track_id = :trackId")
    suspend fun clearTextSelectionsForTrack(trackId: String)

    @Query("DELETE FROM timed_text_track WHERE track_id = :trackId")
    suspend fun deleteTextTrack(trackId: String)

    @Query("DELETE FROM text_selection WHERE content_id = :contentId")
    suspend fun clearTextSelections(contentId: String)

    @Query(
        "UPDATE media_locator SET availability = 'Unavailable', updated_at_ms = :updatedAtMs " +
            "WHERE source_id = :sourceId AND seen_generation < :generation",
    )
    suspend fun markOlderSourceLocatorsUnavailable(
        sourceId: String,
        generation: Long,
        updatedAtMs: Long,
    )

    @Query("SELECT DISTINCT content_id FROM media_locator WHERE source_id = :sourceId")
    suspend fun contentIdsForSource(sourceId: String): List<String>

    @Query(
        "UPDATE media_locator SET availability = 'Unavailable', updated_at_ms = :updatedAtMs " +
            "WHERE source_id = :sourceId",
    )
    suspend fun markSourceLocatorsUnavailable(sourceId: String, updatedAtMs: Long)

    @Query("DELETE FROM active_media_locator WHERE content_id = :contentId")
    suspend fun clearActiveLocator(contentId: String)

    @Query("DELETE FROM content WHERE content_id = :contentId")
    suspend fun deleteContent(contentId: String)

    @Query(
        "SELECT c.content_id FROM content c " +
            "WHERE NOT EXISTS (SELECT 1 FROM media_locator l LEFT JOIN source s ON s.source_id = l.source_id " +
            "  WHERE l.content_id = c.content_id AND l.availability = 'Available' " +
            "  AND (l.source_id IS NULL OR s.removed_at_ms IS NULL)) " +
            "AND NOT EXISTS (SELECT 1 FROM content_disposition d WHERE d.content_id = c.content_id) " +
            "AND NOT EXISTS (SELECT 1 FROM recent_entry r WHERE r.content_id = c.content_id) " +
            "AND NOT EXISTS (SELECT 1 FROM playlist_entry p WHERE p.content_id = c.content_id) " +
            "AND NOT EXISTS (SELECT 1 FROM current_queue_entry q WHERE q.content_id = c.content_id) " +
            "AND NOT EXISTS (SELECT 1 FROM current_draft d WHERE d.content_id = c.content_id) " +
            "AND NOT EXISTS (SELECT 1 FROM text_selection x WHERE x.content_id = c.content_id AND x.mode = 'Manual') " +
            "AND NOT EXISTS (SELECT 1 FROM timed_text_track t WHERE t.content_id = c.content_id " +
            "  AND t.match_basis = 'UserSelected')",
    )
    suspend fun garbageCollectableContentIds(): List<String>

    companion object {
        const val SUMMARY_SELECT =
            "SELECT c.content_id AS contentId, l.locator_id AS locatorId, " +
                "l.uri_value AS uriValue, l.display_name AS displayName, l.mime_type AS mimeType, " +
                "l.title AS title, l.artist AS artist, l.album AS album, l.duration_ms AS durationMs, " +
                "l.folder AS folder, l.date_added_seconds AS dateAddedSeconds, l.origin AS origin, " +
                "l.source_id AS sourceId, l.kind AS kind, l.size_bytes AS sizeBytes, " +
                "l.video_width AS videoWidth, l.video_height AS videoHeight, " +
                "l.availability AS availability, d.is_collected AS isCollected, " +
                "r.last_opened_at_ms AS lastOpenedAtMs " +
                "FROM content c " +
                "INNER JOIN active_media_locator a ON a.content_id = c.content_id " +
                "INNER JOIN media_locator l ON l.locator_id = a.locator_id " +
                "LEFT JOIN content_disposition d ON d.content_id = c.content_id " +
                "LEFT JOIN recent_entry r ON r.content_id = c.content_id"
    }
}
