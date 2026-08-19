package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.util.AtomicFile
import io.github.zvensmoluya.vortexcue.subtitle.NormalizedSubtitleWriter
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.WebVttParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredTextAsset(
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class StoredGeneratedAssets(
    val source: StoredTextAsset,
    val target: StoredTextAsset?,
    val cueCount: Int,
)

interface GeneratedTextAssetStore {
    suspend fun writeResult(
        resultId: String,
        sourceCues: List<SubtitleCue>,
        targetCues: List<SubtitleCue>?,
    ): StoredGeneratedAssets

    suspend fun readTextAsset(fileName: String): List<SubtitleCue>

    suspend fun writeCheckpoint(fileName: String, bytes: ByteArray): StoredTextAsset

    suspend fun readCheckpoint(fileName: String): ByteArray

    suspend fun writeRawResponse(fileName: String, bytes: ByteArray): String

    suspend fun delete(fileName: String)

    suspend fun pruneExcept(retainedFileNames: Set<String>)
}

class PrivateGeneratedTextAssetStore(context: Context) : GeneratedTextAssetStore {
    private val root = File(context.noBackupFilesDir, RootDirectory).apply(File::mkdirs)
    private val results = File(root, ResultsDirectory).apply(File::mkdirs)
    private val checkpoints = File(root, CheckpointsDirectory).apply(File::mkdirs)
    private val rawResponses = File(root, RawResponsesDirectory).apply(File::mkdirs)

    override suspend fun writeResult(
        resultId: String,
        sourceCues: List<SubtitleCue>,
        targetCues: List<SubtitleCue>?,
    ): StoredGeneratedAssets =
        withContext(Dispatchers.IO) {
            requireSafeStem(resultId)
            val normalizedSource = normalizeSourceCues(sourceCues)
            val normalizedTarget = targetCues?.let { normalizeTargetCues(normalizedSource, it) }
            val source = writeAsset(results, "$resultId.source.vtt", NormalizedSubtitleWriter.encode(normalizedSource))
            val target =
                normalizedTarget?.let {
                    runCatching {
                        writeAsset(results, "$resultId.target.vtt", NormalizedSubtitleWriter.encode(it))
                    }.getOrElse { error ->
                        File(results, source.fileName).delete()
                        throw error
                    }
                }
            StoredGeneratedAssets(source, target, normalizedSource.size)
        }

    override suspend fun readTextAsset(fileName: String): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            val file = resolveKnownFile(fileName)
            WebVttParser.parse(file.readBytes())
        }

    override suspend fun writeCheckpoint(fileName: String, bytes: ByteArray): StoredTextAsset =
        withContext(Dispatchers.IO) {
            require(bytes.size <= MaxCheckpointBytes) { "Checkpoint is too large" }
            writeAsset(checkpoints, requireSafeFileName(fileName), bytes)
        }

    override suspend fun readCheckpoint(fileName: String): ByteArray =
        withContext(Dispatchers.IO) {
            val file = File(checkpoints, requireSafeFileName(fileName))
            if (!file.isFile || file.length() > MaxCheckpointBytes) throw IOException("Checkpoint is unavailable")
            file.readBytes()
        }

    override suspend fun writeRawResponse(fileName: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            require(bytes.size <= MaxRawResponseBytes) { "Raw response is too large" }
            writeAsset(rawResponses, requireSafeFileName(fileName), bytes).fileName
        }

    override suspend fun delete(fileName: String) {
        withContext(Dispatchers.IO) {
            listOf(results, checkpoints, rawResponses).forEach { directory ->
                File(directory, requireSafeFileName(fileName)).delete()
            }
        }
    }

    override suspend fun pruneExcept(retainedFileNames: Set<String>) {
        withContext(Dispatchers.IO) {
            val retained = retainedFileNames.map(::requireSafeFileName).toSet()
            listOf(results, checkpoints, rawResponses).forEach { directory ->
                directory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                    if (file.name !in retained) file.delete()
                }
            }
        }
    }

    private fun resolveKnownFile(fileName: String): File {
        val name = requireSafeFileName(fileName)
        return listOf(results, checkpoints, rawResponses)
            .map { File(it, name) }
            .firstOrNull(File::isFile)
            ?: throw IOException("Generated text asset is unavailable")
    }

    private fun writeAsset(directory: File, fileName: String, bytes: ByteArray): StoredTextAsset {
        val file = File(directory, requireSafeFileName(fileName))
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        return StoredTextAsset(file.name, sha256(bytes), bytes.size.toLong())
    }

    private fun normalizeSourceCues(cues: List<SubtitleCue>): List<SubtitleCue> {
        require(cues.isNotEmpty()) { "Source cues must not be empty" }
        val sorted = cues.sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
        val used = mutableSetOf<String>()
        return sorted.mapIndexed { index, cue ->
            val supplied = cue.stableId?.takeIf { SafeCueId.matches(it) && used.add(it) }
            val stableId = supplied ?: deterministicCueId(index, cue).also(used::add)
            cue.copy(text = cue.text.trim(), stableId = stableId).also {
                require(it.text.isNotBlank()) { "Source cue text must not be blank" }
            }
        }
    }

    private fun normalizeTargetCues(
        source: List<SubtitleCue>,
        target: List<SubtitleCue>,
    ): List<SubtitleCue> {
        require(source.size == target.size) { "Source and target cue counts differ" }
        val byId = target.associateBy { it.stableId }
        require(byId.size == target.size && null !in byId) { "Target cue IDs must be unique" }
        return source.map { sourceCue ->
            val targetCue = requireNotNull(byId[sourceCue.stableId]) { "Target cue is missing" }
            require(targetCue.text.isNotBlank()) { "Target cue text must not be blank" }
            targetCue.copy(
                startMs = sourceCue.startMs,
                endMs = sourceCue.endMs,
                text = targetCue.text.trim(),
                stableId = sourceCue.stableId,
            )
        }
    }

    private fun deterministicCueId(index: Int, cue: SubtitleCue): String {
        val input = "$index|${cue.startMs}|${cue.endMs}|${cue.text}".toByteArray(Charsets.UTF_8)
        return "cue-${sha256(input).take(20)}"
    }

    private fun requireSafeStem(value: String): String {
        require(SafeStem.matches(value)) { "Unsafe generated asset identifier" }
        return value
    }

    private fun requireSafeFileName(value: String): String {
        require(SafeFileName.matches(value)) { "Unsafe generated asset file name" }
        return value
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val RootDirectory = "sync_text"
        private const val ResultsDirectory = "results"
        private const val CheckpointsDirectory = "checkpoints"
        private const val RawResponsesDirectory = "raw_responses"
        private const val MaxCheckpointBytes = 16 * 1024 * 1024
        private const val MaxRawResponseBytes = 8 * 1024 * 1024
        private val SafeStem = Regex("[A-Za-z0-9_-]{1,128}")
        private val SafeFileName = Regex("[A-Za-z0-9_.-]{1,180}")
        private val SafeCueId = Regex("[A-Za-z0-9_.:-]{1,128}")
    }
}
