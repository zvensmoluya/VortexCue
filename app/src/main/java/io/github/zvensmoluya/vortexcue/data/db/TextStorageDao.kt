package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TextStorageDao {
    @Query("SELECT * FROM text_artifact WHERE artifact_id = :artifactId")
    suspend fun artifact(artifactId: String): TextArtifactEntity?

    @Query("SELECT * FROM text_artifact ORDER BY artifact_id")
    suspend fun artifacts(): List<TextArtifactEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtifact(entity: TextArtifactEntity): Long

    @Query("UPDATE text_artifact SET gc_candidate_since_ms = :timestamp WHERE artifact_id = :artifactId")
    suspend fun markArtifactForGc(artifactId: String, timestamp: Long?): Int

    @Query("DELETE FROM text_artifact WHERE artifact_id = :artifactId")
    suspend fun deleteArtifact(artifactId: String): Int

    @Query("SELECT * FROM timeline_revision WHERE revision_id = :revisionId")
    suspend fun revision(revisionId: String): TimelineRevisionEntity?

    @Query("SELECT * FROM timeline_revision ORDER BY created_at_ms, revision_id")
    suspend fun revisions(): List<TimelineRevisionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRevision(entity: TimelineRevisionEntity): Long

    @Query("DELETE FROM timeline_revision WHERE revision_id = :revisionId")
    suspend fun deleteRevision(revisionId: String): Int

    @Query("SELECT * FROM timeline_revision_artifact WHERE revision_id = :revisionId ORDER BY role, ordinal")
    suspend fun revisionArtifacts(revisionId: String): List<TimelineRevisionArtifactEntity>

    @Query(
        """
        SELECT
            revision.revision_id AS revision_revision_id,
            revision.content_id AS revision_content_id,
            revision.role AS revision_role,
            revision.source_revision_id AS revision_source_revision_id,
            revision.source_revision_hash AS revision_source_revision_hash,
            revision.completeness AS revision_completeness,
            revision.covered_end_ms AS revision_covered_end_ms,
            revision.cue_count AS revision_cue_count,
            revision.content_hash AS revision_content_hash,
            revision.assembler_version AS revision_assembler_version,
            revision.created_at_ms AS revision_created_at_ms,
            relation.revision_id AS relation_revision_id,
            relation.artifact_id AS relation_artifact_id,
            relation.ordinal AS relation_ordinal,
            relation.role AS relation_role,
            artifact.artifact_id AS artifact_artifact_id,
            artifact.kind AS artifact_kind,
            artifact.schema_version AS artifact_schema_version,
            artifact.codec_version AS artifact_codec_version,
            artifact.canonicalizer_version AS artifact_canonicalizer_version,
            artifact.payload_sha256 AS artifact_payload_sha256,
            artifact.byte_size AS artifact_byte_size,
            artifact.cue_count AS artifact_cue_count,
            artifact.created_at_ms AS artifact_created_at_ms,
            artifact.gc_candidate_since_ms AS artifact_gc_candidate_since_ms
        FROM timeline_revision AS revision
        INNER JOIN timeline_revision_artifact AS relation
            ON relation.revision_id = revision.revision_id
        INNER JOIN text_artifact AS artifact
            ON artifact.artifact_id = relation.artifact_id
        WHERE revision.revision_id = :revisionId
        ORDER BY relation.role, relation.ordinal
        """,
    )
    suspend fun revisionReadRows(revisionId: String): List<TimelineRevisionReadRow>

    @Query("SELECT * FROM timeline_revision_artifact ORDER BY revision_id, role, ordinal")
    suspend fun revisionArtifacts(): List<TimelineRevisionArtifactEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRevisionArtifacts(entities: List<TimelineRevisionArtifactEntity>)

    @Query("SELECT * FROM discovered_text_candidate WHERE content_id = :contentId ORDER BY candidate_id")
    suspend fun candidates(contentId: String): List<DiscoveredTextCandidateEntity>

    @Query("SELECT * FROM discovered_text_candidate WHERE content_id = :contentId ORDER BY candidate_id")
    fun observeCandidates(contentId: String): Flow<List<DiscoveredTextCandidateEntity>>

    @Query("SELECT * FROM discovered_text_candidate WHERE candidate_id = :candidateId")
    suspend fun candidate(candidateId: String): DiscoveredTextCandidateEntity?

    @Upsert
    suspend fun upsertCandidates(entities: List<DiscoveredTextCandidateEntity>)

    @Query("DELETE FROM discovered_text_candidate WHERE source_id = :sourceId AND source_generation < :generation")
    suspend fun deleteOlderCandidates(sourceId: String, generation: Long): Int

    @Query("DELETE FROM discovered_text_candidate WHERE candidate_id = :candidateId")
    suspend fun deleteCandidate(candidateId: String): Int

    @Query("SELECT * FROM text_import_operation WHERE import_id = :importId")
    suspend fun importOperation(importId: String): TextImportOperationEntity?

    @Query("SELECT * FROM text_import_operation WHERE candidate_id = :candidateId ORDER BY created_at_ms DESC LIMIT 1")
    suspend fun latestCandidateImport(candidateId: String): TextImportOperationEntity?

    @Query(
        "SELECT * FROM text_import_operation " +
            "WHERE state IN ('Requested', 'Reading', 'Parsed', 'ArtifactPublished') " +
            "ORDER BY created_at_ms, import_id",
    )
    suspend fun recoverableImports(): List<TextImportOperationEntity>

    @Query("SELECT * FROM text_import_operation WHERE content_id = :contentId ORDER BY created_at_ms DESC")
    suspend fun importsForContent(contentId: String): List<TextImportOperationEntity>

    @Upsert
    suspend fun upsertImportOperation(entity: TextImportOperationEntity)
}
