package io.github.zvensmoluya.vortexcue.text

import android.content.Context
import androidx.room3.withWriteTransaction
import io.github.zvensmoluya.vortexcue.data.db.GeneratedTextResultEntity
import io.github.zvensmoluya.vortexcue.data.db.TextArtifactEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TextStorageMaintenance(
    context: Context,
    private val database: VortexDatabase,
    private val storage: TextStorageRepository,
    private val store: TextArtifactStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val contentDao = database.contentDao()
    private val syncDao = database.syncTextDao()
    private val textDao = database.textStorageDao()

    suspend fun recoverAuditAndCollect() {
        auditReachableArtifacts()
        collectUnreachableArtifacts()
        removeLegacyOwnedDirectories()
    }

    private suspend fun auditReachableArtifacts() {
        val graph = loadReachability()
        val invalidArtifactIds = mutableSetOf<String>()
        graph.reachableArtifactIds.forEach { artifactId ->
            if (runCatching { storage.readArtifact(artifactId) }.isFailure) invalidArtifactIds += artifactId
        }
        if (invalidArtifactIds.isEmpty()) return

        val invalidRevisionIds =
            graph.relations
                .filter { it.artifactId in invalidArtifactIds }
                .mapTo(mutableSetOf()) { it.revisionId }
        var changed: Boolean
        do {
            changed = false
            graph.revisions.forEach { revision ->
                if (revision.sourceRevisionId in invalidRevisionIds && invalidRevisionIds.add(revision.revisionId)) {
                    changed = true
                }
            }
        } while (changed)

        val message = "文字资产缺失、损坏或版本不受支持"
        val tracks = contentDao.allTextTracks()
        tracks.filter { it.revisionId in invalidRevisionIds }.forEach { track ->
            contentDao.markTextTrackMissingArtifact(track.trackId, message)
        }

        val resultsById = graph.results.associateBy(GeneratedTextResultEntity::resultId)
        val invalidResultIds =
            graph.results
                .filter { it.sourceRevisionId in invalidRevisionIds || it.targetRevisionId in invalidRevisionIds }
                .mapTo(mutableSetOf(), GeneratedTextResultEntity::resultId)
        val invalidCurrentResultIds = mutableSetOf<String>()
        syncDao.generatedSlots().filter { it.currentResultId in invalidResultIds }.forEach { slot ->
            slot.currentResultId?.let(invalidCurrentResultIds::add)
            tracks.filter { it.generatedSlotId == slot.slotId }.forEach { track ->
                contentDao.markTextTrackMissingArtifact(track.trackId, message)
            }
        }

        val timestamp = nowMs()
        invalidCurrentResultIds.mapNotNull(resultsById::get).mapNotNull(GeneratedTextResultEntity::taskId)
            .forEach { taskId -> syncDao.markTaskStorageNeedsAttention(taskId, message, timestamp) }
        syncDao.activeTasks().forEach { task ->
            val hasInvalidSource = task.sourceResultId?.let(resultsById::get)?.resultId in invalidResultIds
            val snapshot = syncDao.taskSnapshot(task.taskId)
            val sourceTrackId = snapshot?.sourceTrackId
            val hasInvalidInputRevision =
                tracks.firstOrNull { it.trackId == sourceTrackId }?.revisionId in invalidRevisionIds ||
                    snapshot?.inputSourceRevisionId in invalidRevisionIds
            val hasInvalidWorkArtifact =
                syncDao.attemptsForTask(task.taskId).any { attempt ->
                    syncDao.asrWindows(attempt.attemptId).any { it.transcriptArtifactId in invalidArtifactIds } ||
                        syncDao.translationBatches(attempt.attemptId).any { it.resultArtifactId in invalidArtifactIds } ||
                        syncDao.sourcePreparation(attempt.attemptId)?.outputArtifactId in invalidArtifactIds
                }
            if (hasInvalidSource || hasInvalidInputRevision || hasInvalidWorkArtifact) {
                syncDao.markTaskStorageNeedsAttention(task.taskId, message, timestamp)
            }
        }
    }

    private suspend fun collectUnreachableArtifacts() {
        syncDao.pruneObsoleteGeneratedResults()
        val graph = loadReachability()
        val timestamp = nowMs()
        graph.artifacts.forEach { entity ->
            if (entity.artifactId in graph.reachableArtifactIds) {
                if (entity.gcCandidateSinceMs != null) textDao.markArtifactForGc(entity.artifactId, null)
            } else if (entity.gcCandidateSinceMs == null) {
                textDao.markArtifactForGc(entity.artifactId, timestamp)
            }
        }

        val expired =
            textDao.artifacts().filter { entity ->
                entity.artifactId !in graph.reachableArtifactIds &&
                    entity.gcCandidateSinceMs?.let { it <= timestamp - GcGraceMs } == true
            }
        val expiredIds = expired.mapTo(mutableSetOf(), TextArtifactEntity::artifactId)
        database.withWriteTransaction {
            graph.revisions
                .filterNot { it.revisionId in graph.reachableRevisionIds }
                .sortedBy { if (it.sourceRevisionId != null) 0 else 1 }
                .forEach { revision ->
                    val revisionArtifactIds =
                        graph.relations.filter { it.revisionId == revision.revisionId }.map { it.artifactId }
                    if (revisionArtifactIds.isNotEmpty() && revisionArtifactIds.all(expiredIds::contains)) {
                        textDao.deleteRevision(revision.revisionId)
                    }
                }
        }
        expired.forEach { entity ->
            val metadata = entity.toStoredOrNull()
            val deletedMetadata =
                database.withWriteTransaction {
                    syncDao.clearTerminalAsrArtifactReference(entity.artifactId)
                    syncDao.clearTerminalTranslationArtifactReference(entity.artifactId)
                    syncDao.clearTerminalSourcePreparationArtifactReference(entity.artifactId)
                    textDao.deleteArtifact(entity.artifactId) == 1
                }
            if (deletedMetadata && metadata != null) runCatching { store.delete(metadata) }
        }

        val registeredIds = textDao.artifacts().mapTo(mutableSetOf(), TextArtifactEntity::artifactId)
        store.listFiles()
            .filter { !it.isPublished || it.artifactId !in registeredIds }
            .forEach { artifactFile ->
                if (artifactFile.file.lastModified() <= timestamp - GcGraceMs) {
                    runCatching { store.deleteUnregistered(artifactFile) }
                }
            }
    }

    private suspend fun loadReachability(): Reachability {
        val revisions = textDao.revisions()
        val relations = textDao.revisionArtifacts()
        val artifacts = textDao.artifacts()
        val tracks = contentDao.allTextTracks()
        val currentResultIds = syncDao.generatedSlots().mapNotNullTo(mutableSetOf()) { it.currentResultId }
        val pinnedResultIds = syncDao.activeTasks().mapNotNullTo(mutableSetOf()) { it.sourceResultId }
        val results =
            syncDao.generatedResults().filter { result ->
                result.resultId in currentResultIds || result.resultId in pinnedResultIds
            }
        val reachableRevisionIds =
            buildSet {
                tracks.mapNotNullTo(this) { it.revisionId }
                syncDao.activeTasks().mapNotNullTo(this) { task ->
                    syncDao.taskSnapshot(task.taskId)?.inputSourceRevisionId
                }
                results.forEach { result ->
                    add(result.sourceRevisionId)
                    result.targetRevisionId?.let(::add)
                }
            }.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            revisions.filter { it.revisionId in reachableRevisionIds }.forEach { revision ->
                revision.sourceRevisionId?.let { sourceId ->
                    if (reachableRevisionIds.add(sourceId)) changed = true
                }
            }
        } while (changed)
        val reachableArtifactIds =
            relations.filter { it.revisionId in reachableRevisionIds }
                .mapTo(mutableSetOf()) { it.artifactId }
        reachableArtifactIds += syncDao.recoverableWorkArtifactIds()
        textDao.recoverableImports().mapNotNullTo(reachableArtifactIds) { it.artifactId }
        return Reachability(
            revisions = revisions,
            relations = relations,
            artifacts = artifacts,
            results = results,
            reachableRevisionIds = reachableRevisionIds,
            reachableArtifactIds = reachableArtifactIds,
        )
    }

    private suspend fun removeLegacyOwnedDirectories() {
        withContext(Dispatchers.IO) {
            deleteOwnedDirectory(appContext.filesDir, "subtitle_assets")
            deleteOwnedDirectory(appContext.filesDir, "session_subtitles")
            val syncTextRoot = File(appContext.noBackupFilesDir, "sync_text")
            deleteOwnedDirectory(syncTextRoot, "results")
            deleteOwnedDirectory(syncTextRoot, "checkpoints")
        }
    }

    private fun deleteOwnedDirectory(parent: File, childName: String) {
        val canonicalParent = runCatching { parent.canonicalFile }.getOrNull() ?: return
        val target = runCatching { File(canonicalParent, childName).canonicalFile }.getOrNull() ?: return
        if (target.parentFile != canonicalParent || !target.isDirectory) return
        target.deleteRecursively()
    }

    private data class Reachability(
        val revisions: List<io.github.zvensmoluya.vortexcue.data.db.TimelineRevisionEntity>,
        val relations: List<io.github.zvensmoluya.vortexcue.data.db.TimelineRevisionArtifactEntity>,
        val artifacts: List<TextArtifactEntity>,
        val results: List<GeneratedTextResultEntity>,
        val reachableRevisionIds: Set<String>,
        val reachableArtifactIds: Set<String>,
    )

    companion object {
        const val GcGraceMs = 24L * 60L * 60L * 1_000L
    }
}

private fun TextArtifactEntity.toStoredOrNull(): StoredTextArtifact? {
    val parsedKind = runCatching { enumValueOf<TextArtifactKind>(kind) }.getOrNull() ?: return null
    val parsedCueCount = cueCount ?: return null
    return StoredTextArtifact(
        artifactId = artifactId,
        kind = parsedKind,
        schemaVersion = schemaVersion,
        codecVersion = codecVersion,
        canonicalizerVersion = canonicalizerVersion,
        payloadSha256 = payloadSha256,
        byteSize = byteSize,
        cueCount = parsedCueCount,
    )
}
