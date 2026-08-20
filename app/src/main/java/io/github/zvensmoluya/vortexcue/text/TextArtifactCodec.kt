package io.github.zvensmoluya.vortexcue.text

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest

enum class TextArtifactKind(
    val wireId: Int,
    val directoryName: String,
) {
    ImportedTimeline(1, "imported_timeline"),
    AsrWorkUnitOutput(2, "asr_work_unit"),
    TranslationWorkUnitOutput(3, "translation_work_unit"),
    FinalTimeline(4, "final_timeline"),
    TranslationDelta(5, "translation_delta"),
    ;

    companion object {
        fun fromWireId(value: Int): TextArtifactKind =
            entries.firstOrNull { it.wireId == value }
                ?: throw TextArtifactCodecException("Unknown text artifact kind")
    }
}

sealed interface TextArtifactPayload {
    data class Timeline(
        val value: CanonicalTimeline,
    ) : TextArtifactPayload

    data class Delta(
        val value: TranslationDelta,
    ) : TextArtifactPayload
}

data class DecodedTextArtifact(
    val kind: TextArtifactKind,
    val schemaVersion: Int,
    val codecVersion: Int,
    val canonicalizerVersion: Int,
    val payload: TextArtifactPayload,
)

data class EncodedTextArtifact(
    val artifactId: String,
    val bytes: ByteArray,
    val kind: TextArtifactKind,
    val schemaVersion: Int,
    val codecVersion: Int,
    val canonicalizerVersion: Int,
    val cueCount: Int,
)

class TextArtifactCodecException(message: String, cause: Throwable? = null) : IOException(message, cause)

object TextArtifactCodec {
    const val SchemaVersion = 1
    const val CodecVersion = 1
    const val MaxArtifactBytes = 32 * 1024 * 1024
    private const val MaxCueCount = 1_000_000
    private const val MaxStringBytes = 4 * 1024 * 1024
    private val Magic = "VCTXTXT1".toByteArray(Charsets.US_ASCII)

    fun encode(
        kind: TextArtifactKind,
        payload: TextArtifactPayload,
        canonicalizerVersion: Int = CanonicalTextNormalizer.Version,
    ): EncodedTextArtifact {
        require(canonicalizerVersion > 0)
        requirePayloadMatchesKind(kind, payload)
        val payloadBytes =
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    when (payload) {
                        is TextArtifactPayload.Timeline -> writeTimeline(output, payload.value)
                        is TextArtifactPayload.Delta -> writeDelta(output, payload.value)
                    }
                }
                buffer.toByteArray()
            }
        val bytes =
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.write(Magic)
                    output.writeInt(SchemaVersion)
                    output.writeInt(CodecVersion)
                    output.writeInt(canonicalizerVersion)
                    output.writeByte(kind.wireId)
                    output.writeInt(payloadBytes.size)
                    output.write(payloadBytes)
                }
                buffer.toByteArray()
            }
        require(bytes.size <= MaxArtifactBytes) { "Text artifact is too large" }
        val cueCount =
            when (payload) {
                is TextArtifactPayload.Timeline -> payload.value.cues.size
                is TextArtifactPayload.Delta -> payload.value.entries.size
            }
        return EncodedTextArtifact(
            artifactId = sha256(bytes),
            bytes = bytes,
            kind = kind,
            schemaVersion = SchemaVersion,
            codecVersion = CodecVersion,
            canonicalizerVersion = canonicalizerVersion,
            cueCount = cueCount,
        )
    }

    fun decode(bytes: ByteArray): DecodedTextArtifact {
        if (bytes.size > MaxArtifactBytes) throw TextArtifactCodecException("Text artifact is too large")
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(Magic.size).also(input::readFully)
                if (!magic.contentEquals(Magic)) throw TextArtifactCodecException("Invalid text artifact magic")
                val schemaVersion = input.readInt()
                val codecVersion = input.readInt()
                val canonicalizerVersion = input.readInt()
                if (schemaVersion != SchemaVersion) throw TextArtifactCodecException("Unsupported text artifact schema")
                if (codecVersion != CodecVersion) throw TextArtifactCodecException("Unsupported text artifact codec")
                if (canonicalizerVersion <= 0) throw TextArtifactCodecException("Invalid canonicalizer version")
                val kind = TextArtifactKind.fromWireId(input.readUnsignedByte())
                val payloadLength = input.readInt()
                if (payloadLength < 0 || payloadLength > MaxArtifactBytes) {
                    throw TextArtifactCodecException("Invalid text artifact payload length")
                }
                val payloadBytes = ByteArray(payloadLength).also(input::readFully)
                val payload =
                    DataInputStream(ByteArrayInputStream(payloadBytes)).use { payloadInput ->
                        val decoded =
                            when (kind) {
                                TextArtifactKind.TranslationDelta -> TextArtifactPayload.Delta(readDelta(payloadInput))
                                else -> TextArtifactPayload.Timeline(readTimeline(payloadInput))
                            }
                        if (payloadInput.read() != -1) {
                            throw TextArtifactCodecException("Text artifact payload has trailing bytes")
                        }
                        decoded
                    }
                if (input.read() != -1) throw TextArtifactCodecException("Text artifact has trailing bytes")
                DecodedTextArtifact(kind, schemaVersion, codecVersion, canonicalizerVersion, payload)
            }
        } catch (error: TextArtifactCodecException) {
            throw error
        } catch (error: EOFException) {
            throw TextArtifactCodecException("Text artifact is truncated", error)
        } catch (error: IllegalArgumentException) {
            throw TextArtifactCodecException("Text artifact payload is invalid", error)
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun writeTimeline(output: DataOutputStream, timeline: CanonicalTimeline) {
        output.writeInt(timeline.cues.size)
        timeline.cues.forEach { cue ->
            writeString(output, cue.cueId)
            output.writeLong(cue.startMs)
            output.writeLong(cue.endMs)
            writeString(output, cue.text)
        }
    }

    private fun readTimeline(input: DataInputStream): CanonicalTimeline {
        val count = readCount(input)
        return CanonicalTimeline(
            List(count) {
                CanonicalTextCue(
                    cueId = readString(input),
                    startMs = input.readLong(),
                    endMs = input.readLong(),
                    text = readString(input),
                )
            },
        )
    }

    private fun writeDelta(output: DataOutputStream, delta: TranslationDelta) {
        writeString(output, delta.sourceRevisionId)
        writeString(output, delta.sourceRevisionHash)
        output.writeInt(delta.entries.size)
        delta.entries.forEach { entry ->
            writeString(output, entry.cueId)
            writeString(output, entry.text)
        }
    }

    private fun readDelta(input: DataInputStream): TranslationDelta {
        val sourceRevisionId = readString(input)
        val sourceRevisionHash = readString(input)
        val count = readCount(input)
        return TranslationDelta(
            sourceRevisionId = sourceRevisionId,
            sourceRevisionHash = sourceRevisionHash,
            entries = List(count) { TranslationDeltaEntry(readString(input), readString(input)) },
        )
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxStringBytes) { "Text artifact string is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0 || length > MaxStringBytes) throw TextArtifactCodecException("Invalid string length")
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun readCount(input: DataInputStream): Int {
        val count = input.readInt()
        if (count < 0 || count > MaxCueCount) throw TextArtifactCodecException("Invalid cue count")
        return count
    }

    private fun requirePayloadMatchesKind(kind: TextArtifactKind, payload: TextArtifactPayload) {
        when (kind) {
            TextArtifactKind.TranslationDelta -> require(payload is TextArtifactPayload.Delta)
            else -> require(payload is TextArtifactPayload.Timeline)
        }
    }
}
