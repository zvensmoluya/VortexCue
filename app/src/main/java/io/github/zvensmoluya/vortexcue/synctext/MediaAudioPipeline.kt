package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class AudioTrackInfo(
    val selectionId: String,
    val extractorTrackIndex: Int,
    val mimeType: String,
    val language: String?,
    val label: String?,
    val channelCount: Int?,
    val sampleRate: Int?,
    val durationMs: Long?,
)

class AudioTrackSelectionRequiredException(val tracks: List<AudioTrackInfo>) :
    IllegalArgumentException("Media has multiple audio tracks; choose one explicitly")

class AudioDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ExtractedAudioWindow(
    val file: File,
    val track: AudioTrackInfo,
    val pcmFrames: Long,
)

private data class ClippedOutput(
    val timelineFrames: Long,
    val decodedFrames: Long,
)

interface AudioWindowExtractor {
    suspend fun inspect(uri: Uri): List<AudioTrackInfo>

    suspend fun extract(
        uri: Uri,
        audioTrackId: String?,
        plan: AsrWindowPlan,
        outputFile: File,
    ): ExtractedAudioWindow
}

class BoundedPcmSpool(context: Context) {
    private val root = File(context.noBackupFilesDir, "sync_text/pcm").apply(File::mkdirs)
    private val mutex = Mutex()
    private val reserved = mutableSetOf<String>()

    suspend fun reserve(taskId: String, windowId: String): File =
        mutex.withLock {
            val task = safeId(taskId)
            val window = safeId(windowId)
            val directory = File(root, task).apply(File::mkdirs)
            val activeForTask = reserved.count { it.startsWith("$task/") }
            require(activeForTask < MaxRetainedWindows) { "PCM spool reached its bounded window limit" }
            val key = "$task/$window.wav"
            require(reserved.add(key)) { "PCM window is already reserved" }
            File(directory, "$window.wav")
        }

    suspend fun release(file: File, delete: Boolean = true) {
        mutex.withLock {
            val relative = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return@withLock
            reserved -= relative
            if (delete) file.delete()
            file.parentFile?.takeIf { it != root && it.listFiles().isNullOrEmpty() }?.delete()
        }
    }

    suspend fun clearTask(taskId: String) {
        mutex.withLock {
            val task = safeId(taskId)
            reserved.removeAll { it.startsWith("$task/") }
            File(root, task).deleteRecursively()
        }
    }

    suspend fun pruneUnreserved() {
        mutex.withLock {
            root.walkTopDown().filter(File::isFile).forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative !in reserved) file.delete()
            }
            root.listFiles().orEmpty().filter(File::isDirectory).filter { it.listFiles().isNullOrEmpty() }.forEach(File::delete)
        }
    }

    private fun safeId(value: String): String {
        require(SafeId.matches(value)) { "Unsafe PCM spool identifier" }
        return value
    }

    companion object {
        const val MaxRetainedWindows = 3
        private val SafeId = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlatformAudioWindowExtractor(context: Context) : AudioWindowExtractor {
    private val appContext = context.applicationContext

    override suspend fun inspect(uri: Uri): List<AudioTrackInfo> =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(appContext, uri, emptyMap())
                buildList {
                    repeat(extractor.trackCount) { index ->
                        val format = extractor.getTrackFormat(index)
                        val mime = format.stringOrNull(MediaFormat.KEY_MIME) ?: return@repeat
                        if (!mime.startsWith("audio/")) return@repeat
                        add(format.toTrackInfo(index, mime))
                    }
                }
            } catch (error: Throwable) {
                throw AudioDecodeException("Unable to inspect media audio tracks", error)
            } finally {
                extractor.release()
            }
        }

    override suspend fun extract(
        uri: Uri,
        audioTrackId: String?,
        plan: AsrWindowPlan,
        outputFile: File,
    ): ExtractedAudioWindow =
        withContext(Dispatchers.IO) {
            val tracks = inspect(uri)
            val selected = selectTrack(tracks, audioTrackId)
            outputFile.parentFile?.mkdirs()
            decode(uri, selected, plan, outputFile)
            ExtractedAudioWindow(
                file = outputFile,
                track = selected,
                pcmFrames = plan.uploadDurationMs * OutputSampleRate / 1_000L,
            )
        }

    private fun selectTrack(tracks: List<AudioTrackInfo>, selectionId: String?): AudioTrackInfo {
        if (tracks.isEmpty()) throw AudioDecodeException("Media contains no decodable audio track")
        if (selectionId == null) {
            if (tracks.size > 1) throw AudioTrackSelectionRequiredException(tracks)
            return tracks.single()
        }
        return tracks.firstOrNull { it.selectionId == selectionId }
            ?: throw AudioDecodeException("Selected audio track is no longer available")
    }

    private suspend fun decode(
        uri: Uri,
        track: AudioTrackInfo,
        plan: AsrWindowPlan,
        outputFile: File,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var normalizer: Media3PcmNormalizer? = null
        val expectedOutputFrames = plan.uploadDurationMs * OutputSampleRate / 1_000L
        val writer = WavFileWriter(outputFile, expectedOutputFrames)
        try {
            extractor.setDataSource(appContext, uri, emptyMap())
            extractor.selectTrack(track.extractorTrackIndex)
            val format = extractor.getTrackFormat(track.extractorTrackIndex)
            extractor.seekTo(plan.uploadStartMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(track.mimeType)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val uploadStartUs = plan.uploadStartMs * 1_000L
            val uploadEndUs = plan.uploadEndMs * 1_000L
            var inputEnded = false
            var outputEnded = false
            var idleIterations = 0
            var sourceFormat: PcmFormat? = null
            var sourceTimelineFrames = 0L
            var decodedOutputFrames = 0L

            while (!outputEnded) {
                coroutineContext.ensureActive()
                var madeProgress = false
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CodecTimeoutUs)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime > uploadEndUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, maxOf(uploadEndUs, 0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, maxOf(sampleTime, 0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                        madeProgress = true
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CodecTimeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val pcm = codec.outputFormat.toPcmFormat()
                        if (sourceFormat != null && sourceFormat != pcm) {
                            throw AudioDecodeException("Decoder changed PCM format inside one ASR window")
                        }
                        sourceFormat = pcm
                        normalizer = Media3PcmNormalizer(pcm, writer)
                        madeProgress = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else ->
                        if (outputIndex >= 0) {
                            val output = requireNotNull(codec.getOutputBuffer(outputIndex))
                            val pcm = sourceFormat ?: codec.outputFormat.toPcmFormat().also { sourceFormat = it }
                            val activeNormalizer = normalizer ?: Media3PcmNormalizer(pcm, writer).also { normalizer = it }
                            if (info.size > 0) {
                                val clipped =
                                    queueClippedOutput(
                                        buffer = output,
                                        info = info,
                                        format = pcm,
                                        uploadStartUs = uploadStartUs,
                                        uploadEndUs = uploadEndUs,
                                        sourceTimelineFrames = sourceTimelineFrames,
                                        normalizer = activeNormalizer,
                                    )
                                sourceTimelineFrames = clipped.timelineFrames
                                decodedOutputFrames += clipped.decodedFrames
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            madeProgress = true
                        }
                }

                if (madeProgress) idleIterations = 0 else idleIterations += 1
                if (idleIterations > MaxIdleIterations) throw AudioDecodeException("Audio decoder stopped making progress")
            }

            val pcm = sourceFormat ?: throw AudioDecodeException("Audio decoder returned no PCM format")
            if (decodedOutputFrames == 0L) throw AudioDecodeException("Audio decoder returned no PCM samples")
            val expectedSourceFrames = plan.uploadDurationMs * pcm.sampleRate / 1_000L
            if (sourceTimelineFrames < expectedSourceFrames) {
                queueSilence(expectedSourceFrames - sourceTimelineFrames, pcm, requireNotNull(normalizer))
            }
            requireNotNull(normalizer).endOfStream()
            writer.finish()
        } catch (error: AudioDecodeException) {
            throw error
        } catch (error: Throwable) {
            throw AudioDecodeException("Unable to decode audio for ASR", error)
        } finally {
            runCatching { normalizer?.reset() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            writer.closeAfterFailureIfNeeded()
        }
    }

    private fun queueClippedOutput(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        format: PcmFormat,
        uploadStartUs: Long,
        uploadEndUs: Long,
        sourceTimelineFrames: Long,
        normalizer: Media3PcmNormalizer,
    ): ClippedOutput {
        val frameSize = format.frameSize
        val totalFrames = info.size / frameSize
        if (totalFrames <= 0) return ClippedOutput(sourceTimelineFrames, 0L)
        val bufferStartUs = info.presentationTimeUs
        val bufferEndUs = bufferStartUs + totalFrames * 1_000_000L / format.sampleRate
        val clippedStartUs = maxOf(bufferStartUs, uploadStartUs)
        val clippedEndUs = minOf(bufferEndUs, uploadEndUs)
        if (clippedEndUs <= clippedStartUs) return ClippedOutput(sourceTimelineFrames, 0L)

        var startFrame = ceilDiv((clippedStartUs - bufferStartUs) * format.sampleRate, 1_000_000L).toInt().coerceIn(0, totalFrames)
        val endFrame = ceilDiv((clippedEndUs - bufferStartUs) * format.sampleRate, 1_000_000L).toInt().coerceIn(startFrame, totalFrames)
        val absoluteStartFrame =
            ((bufferStartUs - uploadStartUs) * format.sampleRate / 1_000_000L + startFrame).coerceAtLeast(0L)
        var timeline = sourceTimelineFrames
        if (absoluteStartFrame > timeline) {
            queueSilence(absoluteStartFrame - timeline, format, normalizer)
            timeline = absoluteStartFrame
        } else if (absoluteStartFrame < timeline) {
            startFrame = (startFrame + (timeline - absoluteStartFrame)).coerceAtMost(endFrame.toLong()).toInt()
        }
        if (endFrame <= startFrame) return ClippedOutput(timeline, 0L)
        val slice = buffer.duplicate().order(ByteOrder.nativeOrder())
        slice.position(info.offset + startFrame * frameSize)
        slice.limit(info.offset + endFrame * frameSize)
        normalizer.queueInput(slice.slice().order(ByteOrder.nativeOrder()))
        val decodedFrames = (endFrame - startFrame).toLong()
        return ClippedOutput(timeline + decodedFrames, decodedFrames)
    }

    private fun queueSilence(frames: Long, format: PcmFormat, normalizer: Media3PcmNormalizer) {
        var remaining = frames
        val fill = if (format.encoding == C.ENCODING_PCM_8BIT) 0x80.toByte() else 0.toByte()
        while (remaining > 0L) {
            val count = minOf(remaining, SilenceFramesPerBuffer.toLong()).toInt()
            val bytes = ByteArray(count * format.frameSize) { fill }
            normalizer.queueInput(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()))
            remaining -= count
        }
    }

    private fun MediaFormat.toTrackInfo(index: Int, mime: String) =
        AudioTrackInfo(
            selectionId = "extractor:$index",
            extractorTrackIndex = index,
            mimeType = mime,
            language = stringOrNull(MediaFormat.KEY_LANGUAGE),
            label = stringOrNull("label"),
            channelCount = integerOrNull(MediaFormat.KEY_CHANNEL_COUNT),
            sampleRate = integerOrNull(MediaFormat.KEY_SAMPLE_RATE),
            durationMs = longOrNull(MediaFormat.KEY_DURATION)?.div(1_000L),
        )

    private fun MediaFormat.toPcmFormat(): PcmFormat {
        val sampleRate = integerOrNull(MediaFormat.KEY_SAMPLE_RATE)
            ?: throw AudioDecodeException("Decoder PCM sample rate is missing")
        val channelCount = integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
            ?: throw AudioDecodeException("Decoder PCM channel count is missing")
        val encoding = integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: C.ENCODING_PCM_16BIT
        return PcmFormat(sampleRate, channelCount, encoding)
    }

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    companion object {
        private const val OutputSampleRate = 16_000
        private const val CodecTimeoutUs = 10_000L
        private const val MaxIdleIterations = 500
        private const val SilenceFramesPerBuffer = 8_192

        private fun ceilDiv(value: Long, divisor: Long): Long =
            if (value <= 0L) 0L else (value + divisor - 1L) / divisor
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
) {
    val bytesPerSample: Int
        get() =
            when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
                C.ENCODING_PCM_DOUBLE -> 8
                else -> throw AudioDecodeException("Unsupported decoder PCM encoding: $encoding")
            }

    val frameSize: Int
        get() = bytesPerSample * channelCount
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class Media3PcmNormalizer(
    input: PcmFormat,
    private val writer: WavFileWriter,
) {
    private val processors: List<AudioProcessor>

    init {
        val configured = mutableListOf<AudioProcessor>()
        var format = AudioProcessor.AudioFormat(input.sampleRate, input.channelCount, input.encoding)
        if (input.encoding != C.ENCODING_PCM_16BIT) {
            val processor = ToInt16PcmAudioProcessor()
            format = processor.configure(format)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            configured += processor
        }
        if (format.channelCount != 1) {
            val processor = ChannelMixingAudioProcessor()
            processor.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(format.channelCount, 1))
            format = processor.configure(format)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            configured += processor
        }
        if (format.sampleRate != OutputSampleRate) {
            val processor = SonicAudioProcessor()
            processor.setOutputSampleRateHz(OutputSampleRate)
            format = processor.configure(format)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            configured += processor
        }
        require(format.sampleRate == OutputSampleRate && format.channelCount == 1 && format.encoding == C.ENCODING_PCM_16BIT) {
            "PCM normalization did not reach 16 kHz mono PCM16"
        }
        processors = configured
    }

    fun queueInput(buffer: ByteBuffer) {
        if (processors.isEmpty()) writer.write(buffer) else feed(0, buffer)
    }

    fun endOfStream() {
        processors.indices.forEach { index ->
            processors[index].queueEndOfStream()
            var guard = 0
            while (!processors[index].isEnded) {
                val drained = drain(index)
                if (!drained && ++guard > 10_000) throw AudioDecodeException("PCM processor failed to end")
            }
            drain(index)
        }
    }

    fun reset() {
        processors.forEach(AudioProcessor::reset)
    }

    private fun feed(index: Int, input: ByteBuffer) {
        if (index >= processors.size) {
            writer.write(input)
            return
        }
        val processor = processors[index]
        var guard = 0
        while (input.hasRemaining()) {
            val before = input.remaining()
            processor.queueInput(input)
            val drained = drain(index)
            if (input.remaining() == before && !drained && ++guard > 10_000) {
                throw AudioDecodeException("PCM processor stopped consuming input")
            }
        }
        drain(index)
    }

    private fun drain(index: Int): Boolean {
        var drained = false
        while (true) {
            val output = processors[index].output
            if (!output.hasRemaining()) break
            drained = true
            feed(index + 1, output)
        }
        return drained
    }

    companion object {
        private const val OutputSampleRate = 16_000
    }
}

private class WavFileWriter(
    file: File,
    private val expectedFrames: Long,
) {
    private val output = RandomAccessFile(file, "rw")
    private val maximumDataBytes = expectedFrames * BytesPerFrame
    private var dataBytes = 0L
    private var finished = false

    val writtenFrames: Long
        get() = dataBytes / BytesPerFrame

    init {
        output.setLength(0L)
        output.write(ByteArray(HeaderBytes))
    }

    fun write(buffer: ByteBuffer) {
        var allowed = minOf(buffer.remaining().toLong(), maximumDataBytes - dataBytes).coerceAtLeast(0L)
        val chunk = ByteArray(32 * 1024)
        while (allowed > 0L) {
            val count = minOf(allowed, chunk.size.toLong()).toInt()
            buffer.get(chunk, 0, count)
            output.write(chunk, 0, count)
            dataBytes += count
            allowed -= count
        }
        if (buffer.hasRemaining()) buffer.position(buffer.limit())
    }

    fun finish() {
        if (finished) return
        val zeros = ByteArray(32 * 1024)
        while (dataBytes < maximumDataBytes) {
            val count = minOf(maximumDataBytes - dataBytes, zeros.size.toLong()).toInt()
            output.write(zeros, 0, count)
            dataBytes += count
        }
        output.seek(0L)
        output.write(wavHeader(dataBytes))
        output.fd.sync()
        output.close()
        finished = true
    }

    fun closeAfterFailureIfNeeded() {
        if (!finished) runCatching { output.close() }
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        require(dataSize <= UInt.MAX_VALUE.toLong())
        return ByteBuffer.allocate(HeaderBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36L + dataSize).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SampleRate)
            putInt(SampleRate * BytesPerFrame)
            putShort(BytesPerFrame.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize.toInt())
        }.array()
    }

    companion object {
        private const val HeaderBytes = 44
        private const val SampleRate = 16_000
        private const val BytesPerFrame = 2
    }
}
