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
                ?: throw IOException("The selected text track is no longer available")
            val cues =
                when {
                    track.generatedSlotId != null -> {
                        val result = syncTextDao.currentGeneratedResult(track.generatedSlotId)
                            ?: throw IOException("Generated text result is unavailable")
                        generatedAssets.readTextAsset(result.sourceAssetFileName)
                    }
                    track.uriValue != null -> {
                        val provider = runCatching { contentResolver.readSubtitleBytes(Uri.parse(track.uriValue)) }
                        val bytes = provider.getOrElse { error ->
                            subtitleAssets.read(track.cachedFileName) ?: throw error
                        }
                        val parseName =
                            if (provider.isSuccess) track.displayName else track.cachedFileName ?: track.displayName
                        SubtitleParser.parse(parseName, bytes)
                    }
                    track.embeddedTrackId != null -> {
                        val active = contentDao.activeLocator(contentId)
                            ?: throw IOException("Active media locator is unavailable")
                        val media = contentDao.locator(active.locatorId)
                            ?: throw IOException("Active media is unavailable")
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
                    }
                    else -> throw IOException("Text track has no readable locator")
                }
            SourceTextMaterialization(
                cues = CueIdNormalizer.normalize(cues),
                language = track.language?.let { runCatching { LanguageTags.normalize(it) }.getOrNull() },
            )
        }
}
