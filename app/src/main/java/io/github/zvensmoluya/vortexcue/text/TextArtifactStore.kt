package io.github.zvensmoluya.vortexcue.text

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredTextArtifact(
    val artifactId: String,
    val kind: TextArtifactKind,
    val schemaVersion: Int,
    val codecVersion: Int,
    val canonicalizerVersion: Int,
    val payloadSha256: String,
    val byteSize: Long,
    val cueCount: Int,
)

data class TextArtifactFile(
    val artifactId: String,
    val kind: TextArtifactKind,
    val file: File,
    val isPublished: Boolean = true,
)

data class InspectedTextArtifact(
    val metadata: StoredTextArtifact,
    val decoded: DecodedTextArtifact,
)

interface TextArtifactStore {
    suspend fun publish(
        kind: TextArtifactKind,
        payload: TextArtifactPayload,
        canonicalizerVersion: Int = CanonicalTextNormalizer.Version,
    ): StoredTextArtifact

    suspend fun read(metadata: StoredTextArtifact): DecodedTextArtifact

    suspend fun inspectPublished(
        artifactId: String,
        expectedKind: TextArtifactKind,
    ): InspectedTextArtifact

    suspend fun exists(metadata: StoredTextArtifact): Boolean

    suspend fun listFiles(): List<TextArtifactFile>

    suspend fun delete(metadata: StoredTextArtifact): Boolean

    suspend fun deleteUnregistered(file: TextArtifactFile): Boolean
}

class PrivateTextArtifactStore(context: Context) : TextArtifactStore {
    private val root = File(context.noBackupFilesDir, RootDirectory).apply(File::mkdirs)

    override suspend fun publish(
        kind: TextArtifactKind,
        payload: TextArtifactPayload,
        canonicalizerVersion: Int,
    ): StoredTextArtifact =
        withContext(Dispatchers.IO) {
            val encoded = TextArtifactCodec.encode(kind, payload, canonicalizerVersion)
            val metadata = encoded.toStored()
            val file = resolve(metadata)
            if (file.isFile) {
                verifyFile(file, metadata)
                return@withContext metadata
            }
            val parent = requireNotNull(file.parentFile)
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create text artifact directory")
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(encoded.bytes)
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            verifyFile(file, metadata)
            metadata
        }

    override suspend fun read(metadata: StoredTextArtifact): DecodedTextArtifact =
        withContext(Dispatchers.IO) {
            val inspected = inspectFile(resolve(metadata), metadata.artifactId, metadata.kind)
            if (inspected.metadata != metadata) {
                throw IOException("Text artifact metadata does not match its payload")
            }
            inspected.decoded
        }

    override suspend fun inspectPublished(
        artifactId: String,
        expectedKind: TextArtifactKind,
    ): InspectedTextArtifact =
        withContext(Dispatchers.IO) { inspectFile(resolve(artifactId, expectedKind), artifactId, expectedKind) }

    override suspend fun exists(metadata: StoredTextArtifact): Boolean =
        withContext(Dispatchers.IO) { runCatching { verifyFile(resolve(metadata), metadata) }.isSuccess }

    override suspend fun listFiles(): List<TextArtifactFile> =
        withContext(Dispatchers.IO) {
            TextArtifactKind.entries.flatMap { kind ->
                val directory = File(root, kind.directoryName)
                directory.listFiles().orEmpty().flatMap { prefix ->
                    if (!prefix.isDirectory) return@flatMap emptyList()
                    prefix.listFiles().orEmpty().mapNotNull { file ->
                        val match = file.takeIf(File::isFile)?.let { OwnedFileName.matchEntire(it.name) }
                            ?: return@mapNotNull null
                        TextArtifactFile(
                            artifactId = match.groupValues[1],
                            kind = kind,
                            file = file,
                            isPublished = match.groupValues[2].isEmpty(),
                        )
                    }
                }
            }
        }

    override suspend fun delete(metadata: StoredTextArtifact): Boolean =
        withContext(Dispatchers.IO) {
            val file = resolve(metadata)
            !file.exists() || file.delete()
        }

    override suspend fun deleteUnregistered(file: TextArtifactFile): Boolean =
        withContext(Dispatchers.IO) {
            val published = resolve(file.artifactId, file.kind)
            val allowed =
                setOf(
                    published,
                    File(published.path + ".new"),
                    File(published.path + ".bak"),
                )
            require(file.file in allowed) { "Artifact file is outside the owned store" }
            !file.file.exists() || file.file.delete()
        }

    private fun verifyFile(file: File, metadata: StoredTextArtifact): ByteArray {
        if (!file.isFile) throw IOException("Text artifact is unavailable")
        if (file.length() != metadata.byteSize || file.length() > TextArtifactCodec.MaxArtifactBytes) {
            throw IOException("Text artifact size does not match")
        }
        val bytes = file.readBytes()
        val hash = TextArtifactCodec.sha256(bytes)
        if (hash != metadata.artifactId || hash != metadata.payloadSha256) {
            throw IOException("Text artifact hash does not match")
        }
        return bytes
    }

    private fun inspectFile(
        file: File,
        artifactId: String,
        expectedKind: TextArtifactKind,
    ): InspectedTextArtifact {
        if (!file.isFile) throw IOException("Text artifact is unavailable")
        if (file.length() > TextArtifactCodec.MaxArtifactBytes) throw IOException("Text artifact is too large")
        val bytes = file.readBytes()
        val hash = TextArtifactCodec.sha256(bytes)
        if (hash != artifactId) throw IOException("Text artifact hash does not match")
        val decoded = TextArtifactCodec.decode(bytes)
        if (decoded.kind != expectedKind) throw IOException("Text artifact kind does not match")
        val cueCount =
            when (val payload = decoded.payload) {
                is TextArtifactPayload.Timeline -> payload.value.cues.size
                is TextArtifactPayload.Delta -> payload.value.entries.size
            }
        return InspectedTextArtifact(
            metadata =
                StoredTextArtifact(
                    artifactId = artifactId,
                    kind = decoded.kind,
                    schemaVersion = decoded.schemaVersion,
                    codecVersion = decoded.codecVersion,
                    canonicalizerVersion = decoded.canonicalizerVersion,
                    payloadSha256 = hash,
                    byteSize = bytes.size.toLong(),
                    cueCount = cueCount,
                ),
            decoded = decoded,
        )
    }

    private fun resolve(metadata: StoredTextArtifact): File {
        return resolve(metadata.artifactId, metadata.kind)
    }

    private fun resolve(artifactId: String, kind: TextArtifactKind): File {
        require(SafeArtifactId.matches(artifactId)) { "Invalid text artifact id" }
        val prefix = artifactId.take(2)
        return File(File(File(root, kind.directoryName), prefix), "$artifactId$Extension")
    }

    private fun EncodedTextArtifact.toStored(): StoredTextArtifact =
        StoredTextArtifact(
            artifactId = artifactId,
            kind = kind,
            schemaVersion = schemaVersion,
            codecVersion = codecVersion,
            canonicalizerVersion = canonicalizerVersion,
            payloadSha256 = artifactId,
            byteSize = bytes.size.toLong(),
            cueCount = cueCount,
        )

    companion object {
        const val RootDirectory = "text_artifacts"
        private const val Extension = ".bin"
        private val SafeArtifactId = Regex("[0-9a-f]{64}")
        private val OwnedFileName = Regex("([0-9a-f]{64})\\.bin(?:\\.(new|bak))?")
    }
}
