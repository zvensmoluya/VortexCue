package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import kotlin.math.roundToLong

data class AsrWindowPlan(
    val windowId: String,
    val ordinal: Int,
    val ownedStartMs: Long,
    val ownedEndMs: Long,
    val uploadStartMs: Long,
    val uploadEndMs: Long,
    val pcmStartFrame: Long,
    val pcmEndFrame: Long,
    val cutReason: String,
    val plannerVersion: String = FixedAsrWindowPlanner.PlannerVersion,
    val parentWindowId: String? = null,
    val isLast: Boolean,
) {
    val ownedDurationMs: Long
        get() = ownedEndMs - ownedStartMs

    val uploadDurationMs: Long
        get() = uploadEndMs - uploadStartMs

    fun owns(midpointMs: Long): Boolean =
        midpointMs >= ownedStartMs && (midpointMs < ownedEndMs || isLast && midpointMs <= ownedEndMs)
}

class FixedAsrWindowPlanner(
    private val targetUploadMs: Long = 300_000L,
    private val overlapMs: Long = 3_000L,
    private val sampleRate: Int = 16_000,
    private val maxEncodedBytes: Long = 24L * 1024L * 1024L,
    private val minimumSplitMs: Long = 8_000L,
) {
    fun plan(durationMs: Long): List<AsrWindowPlan> {
        require(durationMs > 0L) { "Media duration must be positive" }
        require(overlapMs in 0 until targetUploadMs)
        val uploads = mutableListOf<Pair<Long, Long>>()
        var start = 0L
        val step = targetUploadMs - overlapMs
        while (start < durationMs) {
            val end = minOf(durationMs, start + targetUploadMs)
            require(encodedWavSize(start, end) <= maxEncodedBytes) { "ASR window exceeds encoded byte limit" }
            uploads += start to end
            if (end == durationMs) break
            start += step
        }
        val boundaries = LongArray(uploads.size + 1)
        boundaries[0] = 0L
        boundaries[uploads.size] = durationMs
        for (index in 0 until uploads.lastIndex) {
            val previousEnd = uploads[index].second
            val nextStart = uploads[index + 1].first
            boundaries[index + 1] = nextStart + (previousEnd - nextStart) / 2L
        }
        return uploads.mapIndexed { index, (uploadStart, uploadEnd) ->
            AsrWindowPlan(
                windowId = "window-${index.toString().padStart(5, '0')}",
                ordinal = index,
                ownedStartMs = boundaries[index],
                ownedEndMs = boundaries[index + 1],
                uploadStartMs = uploadStart,
                uploadEndMs = uploadEnd,
                pcmStartFrame = framesAt(uploadStart),
                pcmEndFrame = framesAt(uploadEnd),
                cutReason = "fixed_v1",
                isLast = index == uploads.lastIndex,
            )
        }.also { validateCoverage(it, durationMs) }
    }

    fun split(
        parent: AsrWindowPlan,
        mediaDurationMs: Long,
        childOrdinalStart: Int,
    ): Pair<AsrWindowPlan, AsrWindowPlan> {
        require(parent.ownedDurationMs >= minimumSplitMs * 2) { "ASR window reached minimum split duration" }
        val midpoint = parent.ownedStartMs + parent.ownedDurationMs / 2L
        val children =
            listOf(parent.ownedStartMs to midpoint, midpoint to parent.ownedEndMs).mapIndexed { index, (start, end) ->
                val uploadStart = maxOf(0L, start - overlapMs / 2L)
                val uploadEnd = minOf(mediaDurationMs, end + (overlapMs - overlapMs / 2L))
                require(encodedWavSize(uploadStart, uploadEnd) <= maxEncodedBytes) {
                    "Split ASR window exceeds encoded byte limit"
                }
                AsrWindowPlan(
                    windowId = "${parent.windowId}-s${index + 1}",
                    ordinal = childOrdinalStart + index,
                    ownedStartMs = start,
                    ownedEndMs = end,
                    uploadStartMs = uploadStart,
                    uploadEndMs = uploadEnd,
                    pcmStartFrame = framesAt(uploadStart),
                    pcmEndFrame = framesAt(uploadEnd),
                    cutReason = "retry_split_v1",
                    parentWindowId = parent.windowId,
                    isLast = parent.isLast && index == 1,
                )
            }
        require(children[0].ownedEndMs == children[1].ownedStartMs)
        return children[0] to children[1]
    }

    fun encodedWavSize(startMs: Long, endMs: Long): Long {
        val pcmBytes = (framesAt(endMs) - framesAt(startMs)) * BytesPerFrame
        val wavBytes = WavHeaderBytes + pcmBytes
        return ((wavBytes + 2L) / 3L) * 4L
    }

    private fun framesAt(milliseconds: Long): Long = milliseconds * sampleRate / 1_000L

    companion object {
        const val PlannerVersion = "fixed_v1"
        private const val BytesPerFrame = 2L
        private const val WavHeaderBytes = 44L

        fun validateCoverage(windows: List<AsrWindowPlan>, durationMs: Long) {
            require(windows.isNotEmpty())
            require(windows.first().ownedStartMs == 0L)
            require(windows.last().ownedEndMs == durationMs)
            windows.zipWithNext().forEach { (first, second) ->
                require(first.ownedEndMs == second.ownedStartMs) { "ASR ownership has a gap or overlap" }
                require(first.uploadEndMs > second.uploadStartMs) { "ASR upload overlap is missing" }
            }
        }
    }
}

data class AsrTimestampedToken(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

object AsrTimelineMerger {
    fun ownedSegments(
        window: AsrWindowPlan,
        relativeSegments: List<AsrTimestampedToken>,
    ): List<AsrTimestampedToken> =
        relativeSegments.mapNotNull { segment ->
            val start = (window.uploadStartMs + segment.startMs).coerceAtLeast(0L)
            val end = (window.uploadStartMs + segment.endMs).coerceAtMost(window.uploadEndMs)
            if (end <= start || segment.text.isBlank()) return@mapNotNull null
            val midpoint = start + (end - start) / 2L
            if (!window.owns(midpoint)) return@mapNotNull null
            val ownedStart = maxOf(start, window.ownedStartMs)
            val ownedEnd = minOf(end, window.ownedEndMs)
            AsrTimestampedToken(ownedStart, ownedEnd, segment.text.trim())
        }

    fun mergeSegments(segments: List<AsrTimestampedToken>): List<SubtitleCue> {
        val sorted = segments.sortedWith(compareBy<AsrTimestampedToken> { it.startMs }.thenBy { it.endMs })
        val deduplicated = mutableListOf<AsrTimestampedToken>()
        sorted.forEach { candidate ->
            val previous = deduplicated.lastOrNull()
            if (previous != null && equivalentNearBoundary(previous, candidate)) {
                if (candidate.text.length > previous.text.length) deduplicated[deduplicated.lastIndex] = candidate
            } else {
                deduplicated += candidate
            }
        }
        return CueIdNormalizer.normalize(
            deduplicated.map { segment -> SubtitleCue(segment.startMs, segment.endMs, segment.text) },
        )
    }

    fun wordsToCues(words: List<AsrTimestampedToken>): List<SubtitleCue> {
        val sorted = words.sortedWith(compareBy<AsrTimestampedToken> { it.startMs }.thenBy { it.endMs })
        val groups = mutableListOf<MutableList<AsrTimestampedToken>>()
        sorted.forEach { word ->
            val current = groups.lastOrNull()
            val shouldBreak =
                current == null ||
                    word.startMs - current.last().endMs > 800L ||
                    word.endMs - current.first().startMs > 6_000L ||
                    current.sumOf { it.text.length } + word.text.length > 72
            if (shouldBreak) groups += mutableListOf(word) else current += word
        }
        return CueIdNormalizer.normalize(
            groups.map { group ->
                SubtitleCue(
                    startMs = group.first().startMs,
                    endMs = maxOf(group.last().endMs, group.first().startMs + 1L),
                    text = joinWords(group.map(AsrTimestampedToken::text)),
                )
            },
        )
    }

    private fun equivalentNearBoundary(first: AsrTimestampedToken, second: AsrTimestampedToken): Boolean {
        val timeNear = second.startMs <= first.endMs + 1_500L
        return timeNear && normalizeText(first.text) == normalizeText(second.text)
    }

    private fun normalizeText(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private fun joinWords(words: List<String>): String =
        buildString {
            words.forEach { word ->
                val value = word.trim()
                if (isNotEmpty() && value.firstOrNull()?.let { shouldSeparateWords(last(), it) } == true) {
                    append(' ')
                }
                append(value)
            }
        }.trim()

    private fun shouldSeparateWords(previous: Char, next: Char): Boolean =
        previous.isLetterOrDigit() && next.isLetterOrDigit() && !previous.isCjk() && !next.isCjk()

    private fun Char.isCjk(): Boolean =
        this in '\u3040'..'\u30ff' ||
            this in '\u3400'..'\u4dbf' ||
            this in '\u4e00'..'\u9fff' ||
            this in '\uac00'..'\ud7af'
}

internal fun secondsToMilliseconds(seconds: Double): Long =
    (seconds * 1_000.0).roundToLong()
