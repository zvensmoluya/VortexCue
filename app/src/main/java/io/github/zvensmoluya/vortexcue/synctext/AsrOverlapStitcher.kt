package io.github.zvensmoluya.vortexcue.synctext

import kotlin.math.abs
import kotlin.math.max

internal data class AsrStitchWindow(
    val plan: AsrWindowPlan,
    val segments: List<AsrTimestampedToken>,
)

/** Reconciles independently recognized overlap regions without clipping a cue at the ownership boundary. */
internal object AsrOverlapStitcher {
    fun stitch(windows: List<AsrStitchWindow>): List<AsrTimestampedToken> {
        if (windows.isEmpty()) return emptyList()
        val ordered = windows.sortedWith(compareBy<AsrStitchWindow> { it.plan.ownedStartMs }.thenBy { it.plan.ordinal })
        ordered.zipWithNext().forEach { (left, right) ->
            require(left.plan.ownedEndMs == right.plan.ownedStartMs) { "ASR stitch inputs are not contiguous" }
        }

        val byWindow =
            ordered.map { window ->
                window.segments
                    .sortedWith(compareBy<AsrTimestampedToken> { it.startMs }.thenBy { it.endMs })
                    .map { segment ->
                        Candidate(
                            segment = segment,
                            keep = window.plan.owns(segment.midpointMs()),
                        )
                    }
            }
        val reconciled = mutableListOf<AsrTimestampedToken>()

        for (leftIndex in 0 until ordered.lastIndex) {
            val rightIndex = leftIndex + 1
            val leftPlan = ordered[leftIndex].plan
            val rightPlan = ordered[rightIndex].plan
            val overlapStartMs = maxOf(leftPlan.uploadStartMs, rightPlan.uploadStartMs)
            val overlapEndMs = minOf(leftPlan.uploadEndMs, rightPlan.uploadEndMs)
            if (overlapEndMs <= overlapStartMs) continue

            val leftCandidates = byWindow[leftIndex].filter { it.availableAt(overlapStartMs, overlapEndMs) }
            val rightCandidates = byWindow[rightIndex].filter { it.availableAt(overlapStartMs, overlapEndMs) }
            align(leftCandidates, rightCandidates).forEach { match ->
                reconciled += reconcile(match, leftPlan, rightPlan)
            }
            alignTemporalConflicts(
                leftCandidates.filterNot(Candidate::reconciled),
                rightCandidates.filterNot(Candidate::reconciled),
            ).forEach { match ->
                reconciled += reconcile(match, leftPlan, rightPlan)
            }
            val unresolvedLeft = leftCandidates.filterNot(Candidate::reconciled)
            val unresolvedRight = rightCandidates.filterNot(Candidate::reconciled)
            unresolvedLeft.filterNot(Candidate::keep).forEach { candidate ->
                if (unresolvedRight.none { other -> candidate.segment.overlaps(other.segment) }) candidate.keep = true
            }
            unresolvedRight.filterNot(Candidate::keep).forEach { candidate ->
                if (unresolvedLeft.none { other -> candidate.segment.overlaps(other.segment) }) candidate.keep = true
            }
            splitUnresolvedConflicts(unresolvedLeft, unresolvedRight, leftPlan.ownedEndMs)
        }

        return (byWindow.flatten().filter(Candidate::keep).map(Candidate::segment) + reconciled)
            .sortedWith(compareBy<AsrTimestampedToken> { it.startMs }.thenBy { it.endMs })
    }

    private fun align(
        left: List<Candidate>,
        right: List<Candidate>,
    ): List<SpanMatch> {
        if (left.isEmpty() || right.isEmpty()) return emptyList()
        val scores = Array(left.size + 1) { DoubleArray(right.size + 1) { Double.NEGATIVE_INFINITY } }
        val paths = Array(left.size + 1) { arrayOfNulls<List<SpanMatch>>(right.size + 1) }
        scores[0][0] = 0.0
        paths[0][0] = emptyList()

        fun update(
            leftIndex: Int,
            rightIndex: Int,
            score: Double,
            path: List<SpanMatch>,
        ) {
            if (score > scores[leftIndex][rightIndex] + ScoreEpsilon) {
                scores[leftIndex][rightIndex] = score
                paths[leftIndex][rightIndex] = path
            }
        }

        for (leftIndex in 0..left.size) {
            for (rightIndex in 0..right.size) {
                val path = paths[leftIndex][rightIndex] ?: continue
                val score = scores[leftIndex][rightIndex]
                if (leftIndex < left.size) update(leftIndex + 1, rightIndex, score, path)
                if (rightIndex < right.size) update(leftIndex, rightIndex + 1, score, path)
                for (leftCount in 1..minOf(MaxSpanCues, left.size - leftIndex)) {
                    for (rightCount in 1..minOf(MaxSpanCues, right.size - rightIndex)) {
                        val leftSpan = CandidateSpan(left.subList(leftIndex, leftIndex + leftCount))
                        val rightSpan = CandidateSpan(right.subList(rightIndex, rightIndex + rightCount))
                        val matchScore = matchScore(leftSpan, rightSpan) ?: continue
                        val match = SpanMatch(leftSpan.candidates, rightSpan.candidates)
                        update(
                            leftIndex + leftCount,
                            rightIndex + rightCount,
                            score + matchScore,
                            path + match,
                        )
                    }
                }
            }
        }
        return paths[left.size][right.size].orEmpty()
    }

    private fun matchScore(
        left: CandidateSpan,
        right: CandidateSpan,
    ): Double? {
        if (left.normalizedText.isEmpty() || right.normalizedText.isEmpty()) return null
        val gapMs = max(left.startMs, right.startMs) - minOf(left.endMs, right.endMs)
        if (gapMs > MaxTimestampGapMs) return null
        val similarity = normalizedSimilarity(left.normalizedText, right.normalizedText)
        val boundaryOverlapLength = boundaryTextOverlapLength(left.normalizedText, right.normalizedText)
        val boundarySimilarity =
            boundaryOverlapLength.toDouble() / minOf(left.normalizedText.length, right.normalizedText.length)
        val hasBoundaryMatch =
            boundaryOverlapLength >= MinBoundaryTextOverlapLength &&
                boundarySimilarity >= MinBoundaryTextSimilarity
        if (left.normalizedText.length < MinFuzzyTextLength || right.normalizedText.length < MinFuzzyTextLength) {
            if (similarity < 1.0 && !hasBoundaryMatch) return null
        } else if (similarity < MinTextSimilarity && !hasBoundaryMatch) {
            return null
        }
        val intersectionMs = (minOf(left.endMs, right.endMs) - max(left.startMs, right.startMs)).coerceAtLeast(0L)
        val unionMs = maxOf(left.endMs, right.endMs) - minOf(left.startMs, right.startMs)
        val temporalScore = if (unionMs > 0L) intersectionMs.toDouble() / unionMs else 0.0
        val groupingPenalty = (left.candidates.size + right.candidates.size - 2) * GroupingPenalty
        return maxOf(similarity, boundarySimilarity) * TextScoreWeight +
            temporalScore * TimeScoreWeight -
            groupingPenalty
    }

    private fun alignTemporalConflicts(
        left: List<Candidate>,
        right: List<Candidate>,
    ): List<SpanMatch> {
        data class PairScore(
            val leftIndex: Int,
            val rightIndex: Int,
            val score: Double,
        )

        val pairs =
            buildList {
                left.forEachIndexed { leftIndex, leftCandidate ->
                    right.forEachIndexed { rightIndex, rightCandidate ->
                        val score = overlapScore(leftCandidate.segment, rightCandidate.segment)
                        if (
                            score >= MinTemporalConflictOverlap &&
                            similarTemporalExtent(leftCandidate.segment, rightCandidate.segment)
                        ) {
                            add(PairScore(leftIndex, rightIndex, score))
                        }
                    }
                }
            }.sortedWith(
                compareByDescending<PairScore> { it.score }
                    .thenBy { it.leftIndex }
                    .thenBy { it.rightIndex },
            )
        val usedLeft = BooleanArray(left.size)
        val usedRight = BooleanArray(right.size)
        return buildList {
            pairs.forEach { pair ->
                if (usedLeft[pair.leftIndex] || usedRight[pair.rightIndex]) return@forEach
                usedLeft[pair.leftIndex] = true
                usedRight[pair.rightIndex] = true
                add(SpanMatch(listOf(left[pair.leftIndex]), listOf(right[pair.rightIndex])))
            }
        }.sortedBy { match -> match.left.first().segment.startMs }
    }

    private fun reconcile(
        match: SpanMatch,
        leftPlan: AsrWindowPlan,
        rightPlan: AsrWindowPlan,
    ): List<AsrTimestampedToken> {
        val leftSpan = CandidateSpan(match.left)
        val rightSpan = CandidateSpan(match.right)
        match.left.forEach { candidate ->
            candidate.keep = false
            candidate.reconciled = true
        }
        match.right.forEach { candidate ->
            candidate.keep = false
            candidate.reconciled = true
        }
        return resolve(leftSpan, rightSpan, leftPlan, rightPlan)
    }

    private fun resolve(
        left: CandidateSpan,
        right: CandidateSpan,
        leftPlan: AsrWindowPlan,
        rightPlan: AsrWindowPlan,
    ): List<AsrTimestampedToken> {
        val chosen =
            when {
                left.normalizedText.contains(right.normalizedText) &&
                    left.normalizedText.length > right.normalizedText.length -> left
                right.normalizedText.contains(left.normalizedText) &&
                    right.normalizedText.length > left.normalizedText.length -> right
                left.trust(leftPlan) >= right.trust(rightPlan) -> left
                else -> right
            }
        val unionStartMs = minOf(left.startMs, right.startMs)
        val unionEndMs = maxOf(left.endMs, right.endMs)
        val boundaryOverlapLength = boundaryTextOverlapLength(left.normalizedText, right.normalizedText)
        if (
            left.candidates.size == 1 &&
            right.candidates.size == 1 &&
            left.normalizedText != right.normalizedText &&
            boundaryOverlapLength >= MinBoundaryTextOverlapLength &&
            boundaryOverlapLength.toDouble() / minOf(left.normalizedText.length, right.normalizedText.length) >=
            MinBoundaryTextSimilarity
        ) {
            return listOf(
                AsrTimestampedToken(
                    startMs = unionStartMs,
                    endMs = unionEndMs,
                    text = mergeBoundaryText(left.candidates.single().segment.text, right.candidates.single().segment.text),
                ),
            )
        }
        return chosen.candidates.mapIndexed { index, candidate ->
            candidate.segment.copy(
                startMs = if (index == 0) unionStartMs else candidate.segment.startMs,
                endMs = if (index == chosen.candidates.lastIndex) unionEndMs else candidate.segment.endMs,
            )
        }
    }

    private fun normalizedSimilarity(
        left: String,
        right: String,
    ): Double {
        if (left == right) return 1.0
        val longest = maxOf(left.length, right.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshteinDistance(left, right).toDouble() / longest
    }

    private fun boundaryTextOverlapLength(
        left: String,
        right: String,
    ): Int {
        for (length in minOf(left.length, right.length) downTo 1) {
            if (left.regionMatches(left.length - length, right, 0, length)) return length
        }
        return 0
    }

    private fun mergeBoundaryText(
        left: String,
        right: String,
    ): String {
        val overlapLength = boundaryTextOverlapLength(normalizeForMatch(left), normalizeForMatch(right))
        var normalizedConsumed = 0
        var suffixStart = right.length
        right.forEachIndexed { index, character ->
            if (character.isLetterOrDigit()) normalizedConsumed += 1
            if (normalizedConsumed == overlapLength) {
                suffixStart = index + 1
                return@forEachIndexed
            }
        }
        val suffix = right.substring(suffixStart).dropWhile { !it.isLetterOrDigit() }.trim()
        if (suffix.isEmpty()) return left.trim()
        val prefix = left.trim()
        val separator =
            if (prefix.lastOrNull()?.isNonCjkLetterOrDigit() == true && suffix.first().isNonCjkLetterOrDigit()) " " else ""
        return prefix + separator + suffix
    }

    private fun levenshteinDistance(
        left: String,
        right: String,
    ): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] =
                    minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                    )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun overlapScore(
        left: AsrTimestampedToken,
        right: AsrTimestampedToken,
    ): Double {
        val intersectionMs = (minOf(left.endMs, right.endMs) - max(left.startMs, right.startMs)).coerceAtLeast(0L)
        val shorterDurationMs = minOf(left.endMs - left.startMs, right.endMs - right.startMs)
        return if (shorterDurationMs > 0L) intersectionMs.toDouble() / shorterDurationMs else 0.0
    }

    private fun similarTemporalExtent(
        left: AsrTimestampedToken,
        right: AsrTimestampedToken,
    ): Boolean {
        val leftDurationMs = left.endMs - left.startMs
        val rightDurationMs = right.endMs - right.startMs
        val durationRatio = minOf(leftDurationMs, rightDurationMs).toDouble() / maxOf(leftDurationMs, rightDurationMs)
        return durationRatio >= MinTemporalConflictDurationRatio &&
            abs(left.startMs - right.startMs) <= MaxTemporalConflictEndpointDeltaMs &&
            abs(left.endMs - right.endMs) <= MaxTemporalConflictEndpointDeltaMs
    }

    private fun splitUnresolvedConflicts(
        left: List<Candidate>,
        right: List<Candidate>,
        ownershipBoundaryMs: Long,
    ) {
        left.filter(Candidate::keep).forEach { leftCandidate ->
            right.filter(Candidate::keep).forEach { rightCandidate ->
                if (!leftCandidate.segment.overlaps(rightCandidate.segment)) return@forEach
                leftCandidate.segment =
                    leftCandidate.segment.copy(endMs = minOf(leftCandidate.segment.endMs, ownershipBoundaryMs))
                rightCandidate.segment =
                    rightCandidate.segment.copy(startMs = maxOf(rightCandidate.segment.startMs, ownershipBoundaryMs))
            }
        }
    }

    private data class Candidate(
        var segment: AsrTimestampedToken,
        var keep: Boolean,
        var reconciled: Boolean = false,
    ) {
        fun availableAt(
            overlapStartMs: Long,
            overlapEndMs: Long,
        ): Boolean =
            !reconciled &&
                segment.endMs >= overlapStartMs - TimestampGuardMs &&
                segment.startMs <= overlapEndMs + TimestampGuardMs
    }

    private data class CandidateSpan(
        val candidates: List<Candidate>,
    ) {
        val startMs = candidates.minOf { it.segment.startMs }
        val endMs = candidates.maxOf { it.segment.endMs }
        val normalizedText = candidates.joinToString(separator = "") { normalizeForMatch(it.segment.text) }

        fun trust(plan: AsrWindowPlan): Long {
            val midpointMs = startMs + (endMs - startMs) / 2L
            return minOf(midpointMs - plan.uploadStartMs, plan.uploadEndMs - midpointMs).coerceAtLeast(0L)
        }
    }

    private data class SpanMatch(
        val left: List<Candidate>,
        val right: List<Candidate>,
    )

    private fun AsrTimestampedToken.midpointMs(): Long = startMs + (endMs - startMs) / 2L

    private fun AsrTimestampedToken.overlaps(other: AsrTimestampedToken): Boolean =
        startMs < other.endMs && other.startMs < endMs

    private fun normalizeForMatch(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private fun Char.isNonCjkLetterOrDigit(): Boolean =
        isLetterOrDigit() &&
            this !in '\u3040'..'\u30ff' &&
            this !in '\u3400'..'\u4dbf' &&
            this !in '\u4e00'..'\u9fff' &&
            this !in '\uac00'..'\ud7af'

    private const val MaxSpanCues = 3
    private const val TimestampGuardMs = 750L
    private const val MaxTimestampGapMs = 750L
    private const val MinFuzzyTextLength = 3
    private const val MinTextSimilarity = 0.72
    private const val MinBoundaryTextOverlapLength = 6
    private const val MinBoundaryTextSimilarity = 0.5
    private const val MinTemporalConflictOverlap = 0.5
    private const val MinTemporalConflictDurationRatio = 0.5
    private const val MaxTemporalConflictEndpointDeltaMs = 3_750L
    private const val TextScoreWeight = 10.0
    private const val TimeScoreWeight = 2.0
    private const val GroupingPenalty = 0.2
    private const val ScoreEpsilon = 0.000_001
}
