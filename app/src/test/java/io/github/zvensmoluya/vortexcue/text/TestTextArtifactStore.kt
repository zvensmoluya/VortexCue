package io.github.zvensmoluya.vortexcue.text

import java.io.IOException

internal class TestTextArtifactStore : TextArtifactStore {
    private val values = mutableMapOf<String, ByteArray>()

    override suspend fun publish(
        kind: TextArtifactKind,
        payload: TextArtifactPayload,
        canonicalizerVersion: Int,
    ): StoredTextArtifact {
        val encoded = TextArtifactCodec.encode(kind, payload, canonicalizerVersion)
        values.putIfAbsent(encoded.artifactId, encoded.bytes)
        return StoredTextArtifact(
            artifactId = encoded.artifactId,
            kind = encoded.kind,
            schemaVersion = encoded.schemaVersion,
            codecVersion = encoded.codecVersion,
            canonicalizerVersion = encoded.canonicalizerVersion,
            payloadSha256 = encoded.artifactId,
            byteSize = encoded.bytes.size.toLong(),
            cueCount = encoded.cueCount,
        )
    }

    override suspend fun read(metadata: StoredTextArtifact): DecodedTextArtifact {
        val bytes = values[metadata.artifactId] ?: throw IOException("Artifact is unavailable")
        if (bytes.size.toLong() != metadata.byteSize) throw IOException("Artifact size does not match")
        if (TextArtifactCodec.sha256(bytes) != metadata.artifactId) throw IOException("Artifact hash does not match")
        return TextArtifactCodec.decode(bytes)
    }

    override suspend fun inspectPublished(
        artifactId: String,
        expectedKind: TextArtifactKind,
    ): InspectedTextArtifact {
        val bytes = values[artifactId] ?: throw IOException("Artifact is unavailable")
        if (TextArtifactCodec.sha256(bytes) != artifactId) throw IOException("Artifact hash does not match")
        val decoded = TextArtifactCodec.decode(bytes)
        if (decoded.kind != expectedKind) throw IOException("Artifact kind does not match")
        val cueCount =
            when (val payload = decoded.payload) {
                is TextArtifactPayload.Timeline -> payload.value.cues.size
                is TextArtifactPayload.Delta -> payload.value.entries.size
                is TextArtifactPayload.Preparation -> payload.value.patches.size
            }
        return InspectedTextArtifact(
            StoredTextArtifact(
                artifactId,
                decoded.kind,
                decoded.schemaVersion,
                decoded.codecVersion,
                decoded.canonicalizerVersion,
                artifactId,
                bytes.size.toLong(),
                cueCount,
            ),
            decoded,
        )
    }

    override suspend fun exists(metadata: StoredTextArtifact): Boolean = runCatching { read(metadata) }.isSuccess

    override suspend fun listFiles(): List<TextArtifactFile> = emptyList()

    override suspend fun delete(metadata: StoredTextArtifact): Boolean {
        values.remove(metadata.artifactId)
        return true
    }

    override suspend fun deleteUnregistered(file: TextArtifactFile): Boolean = true

    fun contains(artifactId: String): Boolean = artifactId in values

    fun corrupt(artifactId: String) {
        values[artifactId] = "corrupt".encodeToByteArray()
    }
}
