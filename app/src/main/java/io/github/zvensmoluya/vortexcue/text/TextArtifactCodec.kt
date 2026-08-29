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
    SourcePreparation(6, "source_preparation"),
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

    data class Preparation(
        val value: SourcePreparationArtifact,
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
                        is TextArtifactPayload.Preparation -> writePreparation(output, payload.value)
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
                is TextArtifactPayload.Preparation -> payload.value.patches.size
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
                                TextArtifactKind.SourcePreparation ->
                                    TextArtifactPayload.Preparation(readPreparation(payloadInput))
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

    private fun writePreparation(output: DataOutputStream, value: SourcePreparationArtifact) {
        writeString(output, value.inputSourceHash)
        output.writeInt(value.policyVersion)
        output.writeInt(value.patches.size)
        value.patches.forEach { patch ->
            writeString(output, patch.cueId)
            output.writeByte(patch.operation.ordinal)
            writeNullableString(output, patch.replacementText)
        }
        writeStyleProfile(output, value.sourceContext.styleProfile)
        output.writeInt(value.sourceContext.entityMentions.size)
        value.sourceContext.entityMentions.forEach { entity ->
            writeString(output, entity.entityId)
            writeString(output, entity.kind)
            writeStrings(output, entity.observedForms)
            writeNullableString(output, entity.spellingStatus)
            writeStrings(output, entity.evidenceCueIds)
        }
        output.writeInt(value.sourceContext.aliasClusters.size)
        value.sourceContext.aliasClusters.forEach { cluster ->
            writeString(output, cluster.entityId)
            writeStrings(output, cluster.forms)
            writeStrings(output, cluster.evidenceCueIds)
        }
        output.writeInt(value.sourceContext.addressingFunctions.size)
        value.sourceContext.addressingFunctions.forEach { addressing ->
            writeString(output, addressing.sourceForm)
            writeString(output, addressing.function)
            writeStrings(output, addressing.evidenceCueIds)
        }
    }

    private fun readPreparation(input: DataInputStream): SourcePreparationArtifact {
        val inputSourceHash = readString(input)
        val policyVersion = input.readInt()
        val patches =
            List(readCount(input)) {
                val cueId = readString(input)
                val operationOrdinal = input.readUnsignedByte()
                val operation = SourcePatchOperation.entries.getOrNull(operationOrdinal)
                    ?: throw TextArtifactCodecException("Unknown source patch operation")
                SourceTextPatch(cueId, operation, readNullableString(input))
            }
        val styleProfile = readStyleProfile(input)
        val entities =
            List(readCount(input)) {
                SourceEntityMention(
                    entityId = readString(input),
                    kind = readString(input),
                    observedForms = readStrings(input),
                    spellingStatus = readNullableString(input),
                    evidenceCueIds = readStrings(input),
                )
            }
        val aliases =
            List(readCount(input)) {
                SourceAliasCluster(
                    entityId = readString(input),
                    forms = readStrings(input),
                    evidenceCueIds = readStrings(input),
                )
            }
        val addressing =
            List(readCount(input)) {
                SourceAddressingFunction(
                    sourceForm = readString(input),
                    function = readString(input),
                    evidenceCueIds = readStrings(input),
                )
            }
        return SourcePreparationArtifact(
            inputSourceHash = inputSourceHash,
            policyVersion = policyVersion,
            patches = patches,
            sourceContext = SourceContext(styleProfile, entities, aliases, addressing),
        )
    }

    private fun writeStyleProfile(output: DataOutputStream, value: SourceStyleProfile) {
        writeNullableString(output, value.contentMode)
        writeNullableString(output, value.register)
        writeNullableString(output, value.politeness)
        writeNullableString(output, value.utteranceShape)
        writeStrings(output, value.preserveTraits)
    }

    private fun readStyleProfile(input: DataInputStream): SourceStyleProfile =
        SourceStyleProfile(
            contentMode = readNullableString(input),
            register = readNullableString(input),
            politeness = readNullableString(input),
            utteranceShape = readNullableString(input),
            preserveTraits = readStrings(input),
        )

    private fun writeStrings(output: DataOutputStream, values: List<String>) {
        output.writeInt(values.size)
        values.forEach { writeString(output, it) }
    }

    private fun readStrings(input: DataInputStream): List<String> = List(readCount(input)) { readString(input) }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        value?.let { writeString(output, it) }
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

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
            TextArtifactKind.SourcePreparation -> require(payload is TextArtifactPayload.Preparation)
            else -> require(payload is TextArtifactPayload.Timeline)
        }
    }
}
