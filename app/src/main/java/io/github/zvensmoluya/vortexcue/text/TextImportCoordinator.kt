package io.github.zvensmoluya.vortexcue.text

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room3.withWriteTransaction
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.stableTimedTextTrackId
import io.github.zvensmoluya.vortexcue.core.storage.readSubtitleBytes
import io.github.zvensmoluya.vortexcue.data.db.DiscoveredTextCandidateEntity
import io.github.zvensmoluya.vortexcue.data.db.TextImportOperationEntity
import io.github.zvensmoluya.vortexcue.data.db.TextSelectionEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackRoleEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleParser
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TextImportState {
    Requested,
    Reading,
    Parsed,
    ArtifactPublished,
    Committed,
    Failed,
    Cancelled,
}

class TextImportCoordinator(
    context: Context,
    private val database: VortexDatabase,
    private val storage: TextStorageRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val inputReader: (suspend (Uri) -> ByteArray)? = null,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val contentDao = database.contentDao()
    private val textDao = database.textStorageDao()

    suspend fun importManual(
        contentId: String,
        slot: TextTrackSlot,
        source: SubtitleSourceRef,
    ): TimedTextTrackEntity {
        val timestamp = nowMs()
        val operation =
            TextImportOperationEntity(
                importId = newId(),
                contentId = contentId,
                candidateId = null,
                stagingUri = source.uri.toString(),
                releasePermissionOnCommit = true,
                sourceDisplayName = source.displayName,
                mimeType = source.mimeType,
                detectedFormat = source.displayName.substringAfterLast('.', "").lowercase().ifBlank { null },
                requestedSlot = slot.name,
                selectionMode = TextSelectionMode.Manual.name,
                state = TextImportState.Requested.name,
                artifactId = null,
                revisionId = null,
                trackId = null,
                failureCategory = null,
                failureMessage = null,
                createdAtMs = timestamp,
                updatedAtMs = timestamp,
            )
        textDao.upsertImportOperation(operation)
        return execute(operation)
    }

    suspend fun importCandidate(
        candidateId: String,
        slot: TextTrackSlot,
        selectionMode: TextSelectionMode = TextSelectionMode.Manual,
    ): TimedTextTrackEntity {
        textDao.latestCandidateImport(candidateId)
            ?.takeIf { it.state == TextImportState.Committed.name }
            ?.let { operation ->
                operation.trackId?.let { trackId ->
                    contentDao.textTracks(operation.contentId)
                        .firstOrNull { it.trackId == trackId }
                        ?.let { track ->
                            selectTrack(operation.contentId, slot, selectionMode, track.trackId)
                            return track
                        }
                }
            }
        val candidate = requireNotNull(textDao.candidate(candidateId)) { "Discovered text candidate is unavailable" }
        val source = requireNotNull(contentDao.source(candidate.sourceId)) { "Candidate source is unavailable" }
        val root = Uri.parse(requireNotNull(source.rootUri) { "Candidate source root is unavailable" })
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, candidate.documentId)
        val timestamp = nowMs()
        val operation =
            TextImportOperationEntity(
                importId = newId(),
                contentId = candidate.contentId,
                candidateId = candidate.candidateId,
                stagingUri = uri.toString(),
                releasePermissionOnCommit = false,
                sourceDisplayName = candidate.displayName,
                mimeType = candidate.mimeType,
                detectedFormat = candidate.format,
                requestedSlot = slot.name,
                selectionMode = selectionMode.name,
                state = TextImportState.Requested.name,
                artifactId = null,
                revisionId = null,
                trackId = null,
                failureCategory = null,
                failureMessage = null,
                createdAtMs = timestamp,
                updatedAtMs = timestamp,
            )
        textDao.upsertImportOperation(operation)
        return execute(operation)
    }

    private suspend fun selectTrack(
        contentId: String,
        slot: TextTrackSlot,
        selectionMode: TextSelectionMode,
        trackId: String,
    ) {
        contentDao.upsertTextSelection(
            TextSelectionEntity(
                contentId = contentId,
                slot = slot.name,
                selectedTrackId = trackId,
                mode = selectionMode.name,
            ),
        )
    }

    suspend fun recoverPending() {
        textDao.recoverableImports().forEach { operation ->
            runCatching { execute(operation) }
        }
    }

    private suspend fun execute(initial: TextImportOperationEntity): TimedTextTrackEntity {
        val uri = initial.stagingUri?.let(Uri::parse)
        return try {
            val timeline: CanonicalTimeline
            val artifact: StoredTextArtifact
            val publishedOperation: TextImportOperationEntity
            if (initial.state == TextImportState.ArtifactPublished.name) {
                val inspected =
                    storage.inspectPublishedArtifact(
                        artifactId = requireNotNull(initial.artifactId) { "Published import has no artifact id" },
                        expectedKind = TextArtifactKind.ImportedTimeline,
                    )
                timeline =
                    (inspected.decoded.payload as? TextArtifactPayload.Timeline)?.value
                        ?: throw IOException("Published import does not contain a timeline")
                artifact = inspected.metadata
                publishedOperation = initial
            } else {
                val sourceUri = requireNotNull(uri) { "Import source must be selected again" }
                val reading = initial.transition(TextImportState.Reading)
                textDao.upsertImportOperation(reading)
                val bytes =
                    inputReader?.invoke(sourceUri)
                        ?: withContext(Dispatchers.IO) { resolver.readSubtitleBytes(sourceUri) }
                val parsed = SubtitleParser.parse(initial.sourceDisplayName, bytes)
                val duration =
                    contentDao.activeLocator(initial.contentId)?.locatorId
                        ?.let { contentDao.locator(it)?.durationMs }
                        ?.takeIf { it > 0L }
                timeline = CanonicalTextNormalizer.normalizeImported(parsed, duration)
                val parsedOperation = reading.transition(TextImportState.Parsed)
                textDao.upsertImportOperation(parsedOperation)
                artifact = storage.publishTimelineArtifact(TextArtifactKind.ImportedTimeline, timeline)
                publishedOperation =
                    parsedOperation.transition(TextImportState.ArtifactPublished).copy(artifactId = artifact.artifactId)
                textDao.upsertImportOperation(publishedOperation)
            }
            val candidate = initial.candidateId?.let { textDao.candidate(it) }
            val track =
                database.withWriteTransaction {
                    val revision =
                        storage.createRevision(
                            contentId = initial.contentId,
                            role = TimelineRole.Source,
                            completeness = TimelineCompleteness.Final,
                            artifacts = listOf(RevisionArtifactRole.Timeline to artifact),
                            contentHash = canonicalContentHash(timeline),
                            cueCount = timeline.cues.size,
                            coveredEndMs = timeline.cues.maxOf(CanonicalTextCue::endMs),
                        )
                    commit(publishedOperation, revision, candidate)
                }
            if (initial.releasePermissionOnCommit && uri != null) releasePermission(uri)
            track
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            textDao.upsertImportOperation(
                initial.copy(
                    stagingUri = null,
                    state = TextImportState.Failed.name,
                    failureCategory = error.importFailureCategory(),
                    failureMessage = error.message?.take(600) ?: "Timed text import failed",
                    updatedAtMs = nowMs(),
                ),
            )
            initial.candidateId?.let { id ->
                textDao.candidate(id)?.let { candidate ->
                    textDao.upsertCandidates(listOf(candidate.copy(failureMessage = error.message?.take(600))))
                }
            }
            if (initial.releasePermissionOnCommit && uri != null) releasePermission(uri)
            throw error
        }
    }

    private suspend fun commit(
        operation: TextImportOperationEntity,
        revision: StoredTimelineRevision,
        candidate: DiscoveredTextCandidateEntity?,
    ): TimedTextTrackEntity {
        val existing = contentDao.textTrackByRevision(operation.contentId, revision.revisionId)
        val format = operation.detectedFormat
        val origin = if (format == "lrc") TimedTextOrigin.Lyrics else TimedTextOrigin.Sidecar
        val locatorIdentity = "revision:${revision.revisionId}"
        val track =
            existing ?: TimedTextTrackEntity(
                trackId = stableTimedTextTrackId(operation.contentId, origin, locatorIdentity),
                contentId = operation.contentId,
                origin = origin.name,
                locatorIdentity = locatorIdentity,
                revisionId = revision.revisionId,
                embeddedTrackId = null,
                displayName = operation.sourceDisplayName,
                mimeType = operation.mimeType,
                format = format,
                language = candidate?.language,
                label = candidate?.label,
                matchBasis = candidate?.matchBasis ?: TimedTextMatchBasis.UserSelected.name,
                readiness = TimedTextReadiness.Ready.name,
                failureMessage = null,
                isRejected = false,
                generatedSlotId = null,
            ).also { contentDao.upsertTextTrack(it) }
        if (existing == null) {
            val roles = candidate?.rolesMask?.toRoles().orEmpty().ifEmpty { inferRoles(operation.sourceDisplayName) }
            contentDao.insertTextRoles(roles.map { TimedTextTrackRoleEntity(track.trackId, it.name) })
        }
        operation.requestedSlot?.let { slot ->
            contentDao.upsertTextSelection(
                TextSelectionEntity(
                    contentId = operation.contentId,
                    slot = slot,
                    selectedTrackId = track.trackId,
                    mode = operation.selectionMode ?: TextSelectionMode.Manual.name,
                ),
            )
        }
        textDao.upsertImportOperation(
            operation.copy(
                stagingUri = null,
                state = TextImportState.Committed.name,
                revisionId = revision.revisionId,
                trackId = track.trackId,
                updatedAtMs = nowMs(),
            ),
        )
        candidate?.let { textDao.deleteCandidate(it.candidateId) }
        return track
    }

    private fun releasePermission(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun TextImportOperationEntity.transition(state: TextImportState): TextImportOperationEntity =
        copy(state = state.name, updatedAtMs = nowMs(), failureCategory = null, failureMessage = null)

    private fun Throwable.importFailureCategory(): String =
        when (this) {
            is SecurityException -> "Permission"
            is IOException -> "Io"
            is IllegalArgumentException -> "Parse"
            else -> "Unknown"
        }
}

private fun Int.toRoles(): List<io.github.zvensmoluya.vortexcue.core.model.TimedTextRole> =
    io.github.zvensmoluya.vortexcue.core.model.TimedTextRole.entries.filterIndexed { index, _ -> this and (1 shl index) != 0 }

private fun inferRoles(displayName: String): List<io.github.zvensmoluya.vortexcue.core.model.TimedTextRole> =
    displayName.substringBeforeLast('.').split('.').mapNotNull { value ->
        when (value.lowercase()) {
            "default" -> io.github.zvensmoluya.vortexcue.core.model.TimedTextRole.Default
            "forced" -> io.github.zvensmoluya.vortexcue.core.model.TimedTextRole.Forced
            "sdh" -> io.github.zvensmoluya.vortexcue.core.model.TimedTextRole.Sdh
            "cc" -> io.github.zvensmoluya.vortexcue.core.model.TimedTextRole.ClosedCaptions
            else -> null
        }
    }.distinct()
