package io.github.zvensmoluya.vortexcue.text

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class CanonicalTextCue(
    val cueId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    init {
        require(SafeCueId.matches(cueId)) { "Invalid canonical cue id" }
        require(startMs >= 0L) { "startMs must not be negative" }
        require(endMs > startMs) { "endMs must be greater than startMs" }
        require(text.isNotBlank()) { "Canonical cue text must not be blank" }
    }

    fun toSubtitleCue(): SubtitleCue =
        SubtitleCue(
            startMs = startMs,
            endMs = endMs,
            text = text,
            stableId = cueId,
        )

    private companion object {
        val SafeCueId = Regex("[A-Za-z0-9_.:-]{1,128}")
    }
}

data class CanonicalTimeline(
    val cues: List<CanonicalTextCue>,
) {
    init {
        require(cues.map(CanonicalTextCue::cueId).distinct().size == cues.size) {
            "Canonical cue ids must be unique"
        }
        require(cues.zipWithNext().all { (first, second) -> first.startMs <= second.startMs }) {
            "Canonical timeline must be sorted"
        }
    }

    fun toSubtitleCues(): List<SubtitleCue> = cues.map(CanonicalTextCue::toSubtitleCue)
}

data class TranslationDeltaEntry(
    val cueId: String,
    val text: String,
) {
    init {
        require(cueId.isNotBlank())
        require(text.isNotBlank())
    }
}

data class TranslationDelta(
    val sourceRevisionId: String,
    val sourceRevisionHash: String,
    val entries: List<TranslationDeltaEntry>,
) {
    init {
        require(sourceRevisionId.isNotBlank())
        require(Sha256.matches(sourceRevisionHash))
        require(entries.isNotEmpty())
        require(entries.map(TranslationDeltaEntry::cueId).distinct().size == entries.size)
    }

    private companion object {
        val Sha256 = Regex("[0-9a-f]{64}")
    }
}

object CanonicalTextNormalizer {
    const val Version = 1
    const val DefaultLrcTailMs = 5_000L

    fun normalizeImported(
        cues: List<SubtitleCue>,
        mediaDurationMs: Long?,
    ): CanonicalTimeline = normalize(cues, mediaDurationMs, preserveStableIds = false)

    fun normalizeGenerated(cues: List<SubtitleCue>): CanonicalTimeline =
        normalize(cues, mediaDurationMs = null, preserveStableIds = true, allowEmpty = false)

    fun normalizeAsrWorkUnit(cues: List<SubtitleCue>): CanonicalTimeline =
        normalize(cues, mediaDurationMs = null, preserveStableIds = true, allowEmpty = true)

    fun alignTranslation(
        source: CanonicalTimeline,
        target: List<SubtitleCue>,
    ): CanonicalTimeline {
        val byId = target.associateBy(SubtitleCue::stableId)
        require(byId.size == target.size && null !in byId) { "Target cue ids must be unique" }
        val aligned =
            source.cues.map { sourceCue ->
                val targetCue = requireNotNull(byId[sourceCue.cueId]) { "Target cue is missing" }
                CanonicalTextCue(
                    cueId = sourceCue.cueId,
                    startMs = sourceCue.startMs,
                    endMs = sourceCue.endMs,
                    text = normalizeText(targetCue.text).also { require(it.isNotBlank()) },
                )
            }
        require(byId.keys.filterNotNull().toSet() == source.cues.map(CanonicalTextCue::cueId).toSet()) {
            "Target contains unknown cue ids"
        }
        return CanonicalTimeline(aligned)
    }

    private fun normalize(
        cues: List<SubtitleCue>,
        mediaDurationMs: Long?,
        preserveStableIds: Boolean,
        allowEmpty: Boolean = false,
    ): CanonicalTimeline {
        val duration = mediaDurationMs?.takeIf { it > 0L }
        val normalized =
            cues.mapIndexedNotNull { index, cue ->
                val text = normalizeText(cue.text)
                if (text.isBlank()) return@mapIndexedNotNull null
                if (duration != null && cue.startMs >= duration) {
                    throw IllegalArgumentException("Subtitle cue starts after the media duration")
                }
                val inferredEnd =
                    when {
                        cue.endMs == Long.MAX_VALUE && duration != null -> duration
                        cue.endMs == Long.MAX_VALUE -> safeAdd(cue.startMs, DefaultLrcTailMs)
                        duration != null -> minOf(cue.endMs, duration)
                        else -> cue.endMs
                    }
                require(inferredEnd > cue.startMs) { "Subtitle cue has no valid time range" }
                IndexedCue(index, cue.startMs, inferredEnd, text, cue.stableId)
            }.sortedWith(compareBy<IndexedCue> { it.startMs }.thenBy { it.originalIndex })
        require(allowEmpty || normalized.isNotEmpty()) { "Subtitle contains no visible timed text" }

        val occurrences = mutableMapOf<CueIdentityInput, Int>()
        val usedIds = mutableSetOf<String>()
        return CanonicalTimeline(
            normalized.map { cue ->
                val key = CueIdentityInput(cue.startMs, cue.endMs, cue.text)
                val occurrence = occurrences.getOrDefault(key, 0)
                occurrences[key] = occurrence + 1
                val supplied =
                    cue.stableId
                        ?.takeIf { preserveStableIds && SafeCueId.matches(it) && usedIds.add(it) }
                val cueId = supplied ?: deterministicCueId(key, occurrence).also(usedIds::add)
                CanonicalTextCue(cueId, cue.startMs, cue.endMs, cue.text)
            },
        )
    }

    private fun normalizeText(value: String): String {
        val lines =
            value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .map(String::trimEnd)
                .toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    private fun deterministicCueId(
        input: CueIdentityInput,
        occurrence: Int,
    ): String {
        val bytes =
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeLong(input.startMs)
                    output.writeLong(input.endMs)
                    val text = input.text.toByteArray(Charsets.UTF_8)
                    output.writeInt(text.size)
                    output.write(text)
                    output.writeInt(occurrence)
                }
                buffer.toByteArray()
            }
        return "cue-${sha256(bytes).take(24)}"
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class IndexedCue(
        val originalIndex: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val stableId: String?,
    )

    private data class CueIdentityInput(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    private val SafeCueId = Regex("[A-Za-z0-9_.:-]{1,128}")
}
