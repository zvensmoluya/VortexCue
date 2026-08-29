package io.github.zvensmoluya.vortexcue.text

import androidx.room3.withWriteTransaction
import io.github.zvensmoluya.vortexcue.data.db.TextArtifactEntity
import io.github.zvensmoluya.vortexcue.data.db.TimelineRevisionArtifactEntity
import io.github.zvensmoluya.vortexcue.data.db.TimelineRevisionEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.synctext.AsrTimelineMerger
import io.github.zvensmoluya.vortexcue.synctext.AsrTimestampedToken
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.LinkedHashMap

enum class TimelineRole {
    Source,
    Target,
}

class TextStorageIntegrityException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

enum class TimelineCompleteness {
    Partial,
    Final,
}

enum class RevisionArtifactRole {
    Timeline,
    AsrWorkUnit,
    TranslationDelta,
}

data class StoredTimelineRevision(
    val revisionId: String,
    val contentId: String,
    val role: TimelineRole,
    val sourceRevisionId: String?,
    val sourceRevisionHash: String?,
    val completeness: TimelineCompleteness,
    val cueCount: Int,
    val coveredEndMs: Long?,
    val contentHash: String,
    val assemblerVersion: Int,
    val createdAtMs: Long,
)

internal data class StoredTimelineRevisionRead(
    val revision: StoredTimelineRevision,
    val artifacts: List<Pair<RevisionArtifactRole, StoredTextArtifact>>,
)

class TextStorageRepository(
    private val database: VortexDatabase,
    private val store: TextArtifactStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.textStorageDao()

    suspend fun publishTimelineArtifact(
        kind: TextArtifactKind,
        timeline: CanonicalTimeline,
    ): StoredTextArtifact =
        store.publish(kind, TextArtifactPayload.Timeline(timeline))

    suspend fun publishTranslationDelta(delta: TranslationDelta): StoredTextArtifact =
        store.publish(TextArtifactKind.TranslationDelta, TextArtifactPayload.Delta(delta))

    suspend fun publishSourcePreparation(value: SourcePreparationArtifact): StoredTextArtifact =
        store.publish(TextArtifactKind.SourcePreparation, TextArtifactPayload.Preparation(value))

    suspend fun registerArtifact(metadata: StoredTextArtifact) {
        val existing = dao.artifact(metadata.artifactId)
        if (existing != null) {
            require(existing.toStored() == metadata) { "Artifact metadata identity collision" }
            if (existing.gcCandidateSinceMs != null) dao.markArtifactForGc(metadata.artifactId, null)
            return
        }
        val inserted = dao.insertArtifact(metadata.toEntity(nowMs()))
        if (inserted == -1L) {
            require(dao.artifact(metadata.artifactId)?.toStored() == metadata) {
                "Artifact metadata identity collision"
            }
        }
    }

    suspend fun createRevision(
        contentId: String,
        role: TimelineRole,
        completeness: TimelineCompleteness,
        artifacts: List<Pair<RevisionArtifactRole, StoredTextArtifact>>,
        contentHash: String,
        cueCount: Int,
        coveredEndMs: Long?,
        sourceRevision: StoredTimelineRevision? = null,
        assemblerVersion: Int = TimelineAssembler.Version,
    ): StoredTimelineRevision {
        require(artifacts.isNotEmpty())
        require(Sha256.matches(contentHash))
        if (role == TimelineRole.Target) requireNotNull(sourceRevision)
        val revisionId =
            deterministicRevisionId(
                contentId = contentId,
                role = role,
                completeness = completeness,
                artifacts = artifacts,
                sourceRevision = sourceRevision,
                assemblerVersion = assemblerVersion,
            )
        val timestamp = nowMs()
        val entity =
            TimelineRevisionEntity(
                revisionId = revisionId,
                contentId = contentId,
                role = role.name,
                sourceRevisionId = sourceRevision?.revisionId,
                sourceRevisionHash = sourceRevision?.contentHash,
                completeness = completeness.name,
                coveredEndMs = coveredEndMs,
                cueCount = cueCount,
                contentHash = contentHash,
                assemblerVersion = assemblerVersion,
                createdAtMs = timestamp,
            )
        var persistedEntity = entity
        database.withWriteTransaction {
            artifacts.forEach { (_, artifact) -> registerArtifact(artifact) }
            val inserted = dao.insertRevision(entity)
            if (inserted == -1L) {
                val existing = requireNotNull(dao.revision(revisionId))
                require(existing.sameIdentityAs(entity)) { "Revision identity collision" }
                persistedEntity = existing
            }
            dao.insertRevisionArtifacts(
                artifacts.mapIndexed { ordinal, (artifactRole, artifact) ->
                    TimelineRevisionArtifactEntity(
                        revisionId = revisionId,
                        artifactId = artifact.artifactId,
                        ordinal = ordinal,
                        role = artifactRole.name,
                    )
                },
            )
        }
        return persistedEntity.toStored()
    }

    suspend fun revision(revisionId: String): StoredTimelineRevision? = dao.revision(revisionId)?.toStored()

    suspend fun artifact(artifactId: String): StoredTextArtifact? = dao.artifact(artifactId)?.toStored()

    suspend fun readArtifact(artifactId: String): DecodedTextArtifact {
        val metadata = dao.artifact(artifactId)?.toStored() ?: throw IOException("Text artifact metadata is unavailable")
        return store.read(metadata)
    }

    internal suspend fun readArtifact(metadata: StoredTextArtifact): DecodedTextArtifact = store.read(metadata)

    suspend fun inspectPublishedArtifact(
        artifactId: String,
        expectedKind: TextArtifactKind,
    ): InspectedTextArtifact = store.inspectPublished(artifactId, expectedKind)

    internal suspend fun revisionRead(revisionId: String): StoredTimelineRevisionRead? {
        val rows = dao.revisionReadRows(revisionId)
        if (rows.isEmpty()) return null
        val revision = rows.first().revision
        require(rows.all { it.revision == revision }) { "Revision read returned inconsistent metadata" }
        return StoredTimelineRevisionRead(
            revision = revision.toStored(),
            artifacts =
                rows.map { row ->
                    enumValueOf<RevisionArtifactRole>(row.relation.role) to row.artifact.toStored()
                },
        )
    }

    private fun deterministicRevisionId(
        contentId: String,
        role: TimelineRole,
        completeness: TimelineCompleteness,
        artifacts: List<Pair<RevisionArtifactRole, StoredTextArtifact>>,
        sourceRevision: StoredTimelineRevision?,
        assemblerVersion: Int,
    ): String {
        val bytes =
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeUTF(contentId)
                    output.writeUTF(role.name)
                    output.writeUTF(completeness.name)
                    output.writeInt(assemblerVersion)
                    output.writeUTF(sourceRevision?.revisionId.orEmpty())
                    output.writeUTF(sourceRevision?.contentHash.orEmpty())
                    output.writeInt(artifacts.size)
                    artifacts.forEach { (artifactRole, artifact) ->
                        output.writeUTF(artifactRole.name)
                        output.writeUTF(artifact.artifactId)
                    }
                }
                buffer.toByteArray()
            }
        return "revision-${sha256(bytes).take(32)}"
    }

    companion object {
        private val Sha256 = Regex("[0-9a-f]{64}")
    }
}

class TimelineRevisionReader(
    private val storage: TextStorageRepository,
    private val cacheSize: Int = 8,
) {
    private val cache =
        object : LinkedHashMap<String, CanonicalTimeline>(cacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CanonicalTimeline>?): Boolean =
                size > cacheSize
        }

    suspend fun read(revisionId: String): CanonicalTimeline {
        synchronized(cache) { cache[revisionId] }?.let { return it }
        val revisionRead =
            try {
                storage.revisionRead(revisionId)
            } catch (error: IllegalArgumentException) {
                throw TextStorageIntegrityException("Timeline revision metadata is invalid", error)
            } ?: throw IOException("Timeline revision is unavailable")
        val timeline =
            try {
                readUncached(revisionRead)
            } catch (error: IllegalArgumentException) {
                throw TextStorageIntegrityException("Timeline revision payload is invalid", error)
            }
        if (timeline.cues.size != revisionRead.revision.cueCount) {
            throw TextStorageIntegrityException("Timeline revision cue count does not match")
        }
        val actualHash =
            try {
                canonicalContentHash(timeline)
            } catch (error: IllegalArgumentException) {
                throw TextStorageIntegrityException("Timeline revision payload is invalid", error)
            }
        if (actualHash != revisionRead.revision.contentHash) {
            throw TextStorageIntegrityException("Timeline revision hash does not match")
        }
        synchronized(cache) { cache[revisionId] = timeline }
        return timeline
    }

    private suspend fun readUncached(revisionRead: StoredTimelineRevisionRead): CanonicalTimeline {
        val revision = revisionRead.revision
        val relations = revisionRead.artifacts
        val deltaRelation = relations.singleOrNull { it.first == RevisionArtifactRole.TranslationDelta }
        if (deltaRelation != null) {
            val sourceId = requireNotNull(revision.sourceRevisionId)
            val source = read(sourceId)
            val decoded = storage.readArtifact(deltaRelation.second)
            val delta = (decoded.payload as? TextArtifactPayload.Delta)?.value
                ?: throw IOException("Target revision does not contain a translation delta")
            if (delta.sourceRevisionId != sourceId || delta.sourceRevisionHash != revision.sourceRevisionHash) {
                throw IOException("Translation delta source revision does not match")
            }
            val translated = delta.entries.associateBy(TranslationDeltaEntry::cueId)
            if (translated.keys != source.cues.map(CanonicalTextCue::cueId).toSet()) {
                throw IOException("Translation delta cue ids do not match its source")
            }
            return CanonicalTimeline(
                source.cues.map { cue -> cue.copy(text = requireNotNull(translated[cue.cueId]).text) },
            )
        }

        val timelines =
            relations.map { (_, metadata) ->
                val decoded = storage.readArtifact(metadata)
                (decoded.payload as? TextArtifactPayload.Timeline)?.value
                    ?: throw IOException("Revision contains a non-timeline artifact")
            }
        if (timelines.size == 1) return timelines.single()
        return TimelineAssembler.assemblePartial(timelines)
    }
}

object TimelineAssembler {
    const val Version = 1

    fun assemblePartial(timelines: List<CanonicalTimeline>): CanonicalTimeline {
        require(timelines.isNotEmpty())
        val merged =
            AsrTimelineMerger.mergeSegments(
                timelines.flatMap(CanonicalTimeline::cues).map { cue ->
                    AsrTimestampedToken(cue.startMs, cue.endMs, cue.text)
                },
            )
        return CanonicalTextNormalizer.normalizeGenerated(merged)
    }

    fun assembleFinal(cues: List<SubtitleCue>): CanonicalTimeline =
        CanonicalTextNormalizer.normalizeGenerated(cues)
}

fun canonicalContentHash(timeline: CanonicalTimeline): String =
    TextArtifactCodec.encode(
        TextArtifactKind.FinalTimeline,
        TextArtifactPayload.Timeline(timeline),
    ).artifactId

private fun TextArtifactEntity.toStored(): StoredTextArtifact =
    StoredTextArtifact(
        artifactId = artifactId,
        kind = enumValueOf(kind),
        schemaVersion = schemaVersion,
        codecVersion = codecVersion,
        canonicalizerVersion = canonicalizerVersion,
        payloadSha256 = payloadSha256,
        byteSize = byteSize,
        cueCount = requireNotNull(cueCount),
    )

private fun StoredTextArtifact.toEntity(createdAtMs: Long): TextArtifactEntity =
    TextArtifactEntity(
        artifactId = artifactId,
        kind = kind.name,
        schemaVersion = schemaVersion,
        codecVersion = codecVersion,
        canonicalizerVersion = canonicalizerVersion,
        payloadSha256 = payloadSha256,
        byteSize = byteSize,
        cueCount = cueCount,
        createdAtMs = createdAtMs,
    )

private fun TimelineRevisionEntity.toStored(): StoredTimelineRevision =
    StoredTimelineRevision(
        revisionId = revisionId,
        contentId = contentId,
        role = enumValueOf(role),
        sourceRevisionId = sourceRevisionId,
        sourceRevisionHash = sourceRevisionHash,
        completeness = enumValueOf(completeness),
        cueCount = cueCount,
        coveredEndMs = coveredEndMs,
        contentHash = contentHash,
        assemblerVersion = assemblerVersion,
        createdAtMs = createdAtMs,
    )

private fun TimelineRevisionEntity.sameIdentityAs(other: TimelineRevisionEntity): Boolean =
    copy(createdAtMs = other.createdAtMs) == other

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
