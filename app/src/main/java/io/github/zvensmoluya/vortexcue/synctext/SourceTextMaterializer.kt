package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.net.Uri
import io.github.zvensmoluya.vortexcue.core.storage.readSubtitleBytes
import io.github.zvensmoluya.vortexcue.data.db.GenerationTaskSnapshotEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleAssetStore
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleParser
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SourceTextMaterialization(
    val cues: List<SubtitleCue>,
    val language: String?,
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
    private val subtitleAssets: SubtitleAssetStore,
    private val generatedAssets: GeneratedTextAssetStore,
) : SourceTextMaterializer {
    private val contentResolver = context.applicationContext.contentResolver
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
            val cues =
                when {
                    track.generatedSlotId != null -> {
                        val result = syncTextDao.currentGeneratedResult(track.generatedSlotId)
                            ?: throw SourceTextUnavailableException(true, "Generated text result is unavailable")
                        generatedAssets.readTextAsset(result.sourceAssetFileName)
                    }
                    track.uriValue != null -> {
                        val provider = runCatching { contentResolver.readSubtitleBytes(Uri.parse(track.uriValue)) }
                        val bytes = provider.getOrElse { error ->
                            subtitleAssets.read(track.cachedFileName)
                                ?: throw SourceTextUnavailableException(false, "Text track access must be restored", error)
                        }
                        val parseName =
                            if (provider.isSuccess) track.displayName else track.cachedFileName ?: track.displayName
                        try {
                            SubtitleParser.parse(parseName, bytes)
                        } catch (error: IllegalArgumentException) {
                            throw SourceTextUnavailableException(true, "Text track could not be parsed", error)
                        }
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
            )
        }
}
