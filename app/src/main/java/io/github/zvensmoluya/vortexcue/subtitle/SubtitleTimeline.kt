package io.github.zvensmoluya.vortexcue.subtitle

class SubtitleTimeline(cues: List<SubtitleCue>) {
    val cues: List<SubtitleCue> =
        cues.sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
    private val maxEndTree = LongArray(maxOf(1, this.cues.size * 4))

    init {
        if (this.cues.isNotEmpty()) buildMaxEndTree(node = 1, left = 0, right = this.cues.lastIndex)
    }

    fun resolve(positionMs: Long): SubtitleCue? {
        if (positionMs < 0 || cues.isEmpty()) return null

        var low = 0
        var high = cues.lastIndex
        var latestStartedIndex = -1

        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (cues[middle].startMs <= positionMs) {
                latestStartedIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        val activeIndex =
            findRightmostActiveCue(
                node = 1,
                left = 0,
                right = cues.lastIndex,
                queryRight = latestStartedIndex,
                positionMs = positionMs,
            )
        return activeIndex.takeIf { it >= 0 }?.let(cues::get)
    }

    private fun buildMaxEndTree(node: Int, left: Int, right: Int): Long {
        if (left == right) {
            maxEndTree[node] = cues[left].endMs
            return maxEndTree[node]
        }

        val middle = (left + right).ushr(1)
        maxEndTree[node] =
            maxOf(
                buildMaxEndTree(node * 2, left, middle),
                buildMaxEndTree(node * 2 + 1, middle + 1, right),
            )
        return maxEndTree[node]
    }

    private fun findRightmostActiveCue(
        node: Int,
        left: Int,
        right: Int,
        queryRight: Int,
        positionMs: Long,
    ): Int {
        if (left > queryRight || maxEndTree[node] <= positionMs) return -1
        if (left == right) return left

        val middle = (left + right).ushr(1)
        val rightResult =
            findRightmostActiveCue(
                node = node * 2 + 1,
                left = middle + 1,
                right = right,
                queryRight = queryRight,
                positionMs = positionMs,
            )
        return if (rightResult >= 0) {
            rightResult
        } else {
            findRightmostActiveCue(
                node = node * 2,
                left = left,
                right = middle,
                queryRight = queryRight,
                positionMs = positionMs,
            )
        }
    }

    companion object {
        val Empty = SubtitleTimeline(emptyList())
    }
}
