package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class EmbeddedTextSelector(
    val identity: String,
    val mimeType: String?,
    val language: String?,
    val label: String?,
)

@OptIn(UnstableApi::class)
class Media3EmbeddedTextExtractor(context: Context) {
    private val appContext = context.applicationContext

    suspend fun extract(
        uri: Uri,
        selector: EmbeddedTextSelector,
    ): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            val factory = DefaultExtractorsFactory()
            val candidates = factory.createExtractors(uri, emptyMap())
            val extractor = candidates.firstOrNull { candidate -> sniff(candidate, uri) }
                ?: throw EmbeddedTextMaterializationUnsupportedException()
            candidates.filterNot { it === extractor }.forEach(Extractor::release)
            val output = CollectingExtractorOutput()
            var source: ContentDataSource? = null
            try {
                extractor.init(output)
                var position = 0L
                var opened = open(uri, position).also { source = it.first }
                var input = opened.second
                val holder = PositionHolder()
                while (true) {
                    coroutineContext.ensureActive()
                    when (extractor.read(input, holder)) {
                        Extractor.RESULT_CONTINUE -> Unit
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> {
                            source?.close()
                            position = holder.position
                            opened = open(uri, position).also { source = it.first }
                            input = opened.second
                        }
                        else -> throw IOException("Embedded text extractor returned an unknown state")
                    }
                }
            } catch (error: EmbeddedSampleException) {
                throw EmbeddedTextMaterializationUnsupportedException()
            } catch (error: EmbeddedTextMaterializationUnsupportedException) {
                throw error
            } catch (error: Throwable) {
                throw IOException("Unable to materialize the embedded text track; choose audio ASR", error)
            } finally {
                runCatching { source?.close() }
                extractor.release()
            }

            val selected = output.select(selector)
                ?: throw EmbeddedTextMaterializationUnsupportedException()
            val cues =
                selected.cues
                    .distinctBy { Triple(it.startMs, it.endMs, it.text) }
                    .sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
            if (cues.isEmpty()) throw EmbeddedTextMaterializationUnsupportedException()
            CueIdNormalizer.normalize(cues)
        }

    private fun sniff(extractor: Extractor, uri: Uri): Boolean {
        var source: ContentDataSource? = null
        return try {
            val opened = open(uri, 0L).also { source = it.first }
            extractor.sniff(opened.second)
        } catch (_: IOException) {
            false
        } finally {
            runCatching { source?.close() }
        }
    }

    private fun open(uri: Uri, position: Long): Pair<ContentDataSource, DefaultExtractorInput> {
        val source = ContentDataSource(appContext)
        val requested = if (position == 0L) DataSpec(uri) else DataSpec(uri, position, C.LENGTH_UNSET.toLong())
        val remainingLength = source.open(requested)
        val absoluteLength =
            if (remainingLength == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + remainingLength
        return source to DefaultExtractorInput(source, position, absoluteLength)
    }
}

@OptIn(UnstableApi::class)
private class CollectingExtractorOutput : ExtractorOutput {
    private val textTracks = linkedMapOf<Int, CollectingTextTrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput =
        if (type == C.TRACK_TYPE_TEXT) {
            textTracks.getOrPut(id) { CollectingTextTrackOutput(textTracks.size) }
        } else {
            DiscardingTrackOutput()
        }

    override fun endTracks() = Unit

    override fun seekMap(seekMap: SeekMap) = Unit

    fun select(selector: EmbeddedTextSelector): CollectingTextTrackOutput? {
        val candidates = textTracks.values.filter(CollectingTextTrackOutput::hasDecodedFormat)
        candidates.singleOrNull { it.matchesIdentity(selector.identity) }?.let { return it }
        if (candidates.size == 1) return candidates.single()
        val scored = candidates.map { it to it.matchScore(selector) }
        val maximum = scored.maxOfOrNull(Pair<CollectingTextTrackOutput, Int>::second) ?: return null
        if (maximum <= 0) return null
        return scored.filter { it.second == maximum }.singleOrNull()?.first
    }
}

@OptIn(UnstableApi::class)
private class CollectingTextTrackOutput(
    private val textTrackIndex: Int,
) : TrackOutput {
    private val pending = ByteArrayOutputStream()
    private val decoder = CueDecoder()
    private val formats = mutableListOf<Format>()
    val cues = mutableListOf<SubtitleCue>()

    val hasDecodedFormat: Boolean
        get() = formats.any { it.sampleMimeType == Media3CuesMimeType }

    override fun format(format: Format) {
        formats += format
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val bytes = ByteArray(minOf(length, ReadBufferBytes))
        val count = input.read(bytes, 0, bytes.size)
        if (count == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException("Unexpected end of embedded text sample")
        }
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) append(bytes, count)
        return count
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int,
    ) {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            data.skipBytes(length)
            return
        }
        val bytes = ByteArray(length)
        data.readBytes(bytes, 0, length)
        append(bytes, length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        val bytes = pending.toByteArray()
        val sampleEnd = bytes.size - offset
        val sampleStart = sampleEnd - size
        if (sampleStart < 0 || sampleEnd !in 0..bytes.size || size > MaxSampleBytes) {
            throw EmbeddedSampleException()
        }
        val trailing = bytes.copyOfRange(sampleEnd, bytes.size)
        pending.reset()
        pending.write(trailing)
        if (size == 0) return
        val format = formats.lastOrNull() ?: throw EmbeddedSampleException()
        if (format.sampleMimeType != Media3CuesMimeType) throw EmbeddedSampleException()
        val decoded = runCatching { decoder.decode(timeUs, bytes, sampleStart, size) }
            .getOrElse { throw EmbeddedSampleException(it) }
        val text =
            decoded.cues
                .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString("\n")
                .takeIf(String::isNotEmpty)
                ?: return
        val startUs = decoded.startTimeUs.takeUnless { it == C.TIME_UNSET } ?: timeUs
        val durationUs = decoded.durationUs.takeUnless { it == C.TIME_UNSET || it <= 0L } ?: DefaultCueDurationUs
        val startMs = (startUs / 1_000L).coerceAtLeast(0L)
        val endMs = ((startUs + durationUs) / 1_000L).coerceAtLeast(startMs + 1L)
        if (cues.size >= MaxCueCount) throw EmbeddedSampleException()
        cues += SubtitleCue(startMs, endMs, text)
    }

    fun matchesIdentity(identity: String): Boolean =
        formats.any { format ->
            format.id?.let { id -> identity.contains("id:${id.length}:$id") } == true
        }

    fun matchScore(selector: EmbeddedTextSelector): Int =
        formats.maxOfOrNull { format ->
            var score = 0
            if (selector.mimeType != null && selector.mimeType in setOf(format.sampleMimeType, format.containerMimeType, format.codecs)) score += 4
            if (selector.language != null && selector.language.equals(format.language, ignoreCase = true)) score += 3
            if (selector.label != null && selector.label == format.label) score += 2
            if (selector.identity.endsWith("/index:$textTrackIndex")) score += 1
            score
        } ?: 0

    private fun append(bytes: ByteArray, count: Int) {
        if (count <= 0) return
        if (pending.size() + count > MaxPendingBytes) throw EmbeddedSampleException()
        pending.write(bytes, 0, count)
    }
}

private class EmbeddedSampleException(cause: Throwable? = null) : RuntimeException(cause)

private const val Media3CuesMimeType = "application/x-media3-cues"
private const val ReadBufferBytes = 16 * 1_024
private const val MaxSampleBytes = 8 * 1_024 * 1_024
private const val MaxPendingBytes = MaxSampleBytes * 2
private const val MaxCueCount = 100_000
private const val DefaultCueDurationUs = 2_000_000L
