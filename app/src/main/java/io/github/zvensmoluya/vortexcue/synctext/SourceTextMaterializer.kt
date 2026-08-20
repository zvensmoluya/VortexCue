package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.net.Uri
import io.github.zvensmoluya.vortexcue.data.db.GenerationTaskSnapshotEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.zvensmoluya.vortexcue.text.CanonicalTextNormalizer
import io.github.zvensmoluya.vortexcue.text.RevisionArtifactRole
import io.github.zvensmoluya.vortexcue.text.TextArtifactKind
import io.github.zvensmoluya.vortexcue.text.TextStorageRepository
import io.github.zvensmoluya.vortexcue.text.TimelineCompleteness
import io.github.zvensmoluya.vortexcue.text.TimelineRevisionReader
import io.github.zvensmoluya.vortexcue.text.TimelineRole
import io.github.zvensmoluya.vortexcue.text.canonicalContentHash

data class SourceTextMaterialization(
    val cues: List<SubtitleCue>,
    val language: String?,
    val revisionId: String,
)

class EmbeddedTextMaterializationUnsupportedException :
    IOException("This embedded text format cannot be materialized; choose its audio track for ASR")

class SourceTextUnavailableException(
    val safeToFallback: Boolean,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

interface SourceTextMaterializer {
    suspend fun materialize(contentId: String, snapshot: GenerationTaskSnapshotEntity): SourceTextMaterialization
}

class AndroidSourceTextMaterializer(
    context: Context,
    database: VortexDatabase,
    private val storage: TextStorageRepository,
    private val reader: TimelineRevisionReader,
) : SourceTextMaterializer {
    private val embeddedTextExtractor = Media3EmbeddedTextExtractor(context.applicationContext)
    private val contentDao = database.contentDao()
    private val syncTextDao = database.syncTextDao()

    override suspend fun materialize(
        contentId: String,
        snapshot: GenerationTaskSnapshotEntity,
    ): SourceTextMaterialization =
        withContext(Dispatchers.IO) {
            val trackId = requireNotNull(snapshot.sourceTrackId) { "Text input did not preserve a track id" }
            val track = contentDao.textTracks(contentId).firstOrNull { it.trackId == trackId }
                ?: throw SourceTextUnavailableException(true, "The selected text track is no longer available")
            var resolvedRevisionId: String? = null
            val cues =
                when {
                    track.generatedSlotId != null -> {
                        val result = syncTextDao.currentGeneratedResult(track.generatedSlotId)
                            ?: throw SourceTextUnavailableException(true, "Generated text result is unavailable")
                        resolvedRevisionId = result.sourceRevisionId
                        reader.read(result.sourceRevisionId).toSubtitleCues()
                    }
                    track.revisionId != null -> {
                        resolvedRevisionId = track.revisionId
                        reader.read(track.revisionId).toSubtitleCues()
                    }
                    track.embeddedTrackId != null -> {
                        val active = contentDao.activeLocator(contentId)
                            ?: throw SourceTextUnavailableException(true, "Active media locator is unavailable")
                        val media = contentDao.locator(active.locatorId)
                            ?: throw SourceTextUnavailableException(true, "Active media is unavailable")
                        try {
                            embeddedTextExtractor.extract(
                                uri = Uri.parse(media.uriValue),
                                selector =
                                    EmbeddedTextSelector(
                                        identity = track.embeddedTrackId,
                                        mimeType = track.mimeType,
                                        language = track.language,
                                        label = track.label,
                                    ),
                            )
                        } catch (error: EmbeddedTextMaterializationUnsupportedException) {
                            throw SourceTextUnavailableException(true, requireNotNull(error.message), error)
                        }
                    }
                    else -> throw SourceTextUnavailableException(true, "Text track has no readable locator")
                }
            SourceTextMaterialization(
                cues = CueIdNormalizer.normalize(cues),
                language = track.language?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() },
                revisionId = resolvedRevisionId ?: publishEmbeddedRevision(contentId, cues),
            )
        }

    private suspend fun publishEmbeddedRevision(
        contentId: String,
        cues: List<SubtitleCue>,
    ): String {
        val timeline = CanonicalTextNormalizer.normalizeGenerated(cues)
        val artifact = storage.publishTimelineArtifact(TextArtifactKind.FinalTimeline, timeline)
        return storage.createRevision(
            contentId = contentId,
            role = TimelineRole.Source,
            completeness = TimelineCompleteness.Final,
            artifacts = listOf(RevisionArtifactRole.Timeline to artifact),
            contentHash = canonicalContentHash(timeline),
            cueCount = timeline.cues.size,
            coveredEndMs = timeline.cues.maxOf { it.endMs },
        ).revisionId
    }
}
