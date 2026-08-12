package io.github.zvensmoluya.vortexcue.playback

import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef

/**
 * The user's current listening plan.  This is deliberately separate from the
 * Media3 runtime queue: a plan can be edited before playback starts and can be
 * restored after the player process has been rebuilt.
 */
data class TonightQueueEntry(
    val contentId: String,
    /** Snapshot used to rebuild the runtime queue. */
    val media: MediaSourceRef,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
) {
    init {
        require(contentId.isNotBlank())
    }

    val progressMs: Long
        get() = positionMs.coerceAtLeast(0L)
}

data class TonightQueue(
    val entries: List<TonightQueueEntry> = emptyList(),
    val currentIndex: Int = 0,
    val started: Boolean = false,
) {
    val isEmpty: Boolean
        get() = entries.isEmpty()

    val current: TonightQueueEntry?
        get() =
            if (entries.isEmpty()) null
            else entries[currentIndex.coerceIn(entries.indices)]

    val remainingCount: Int
        get() = entries.count { !it.completed }

    fun add(entry: TonightQueueEntry): TonightQueue =
        if (entries.any { it.contentId == entry.contentId }) {
            this
        } else {
            copy(entries = entries + entry.copy(completed = false))
        }

    fun addAll(newEntries: List<TonightQueueEntry>): TonightQueue =
        newEntries.fold(this, TonightQueue::add)

    fun removeAt(index: Int): TonightQueue {
        if (index !in entries.indices) return this
        val nextEntries = entries.toMutableList().also { it.removeAt(index) }
        if (nextEntries.isEmpty()) return TonightQueue()
        val nextIndex = removeTonightIndex(entries.size, currentIndex, index)
        return copy(entries = nextEntries, currentIndex = nextIndex)
    }

    fun move(fromIndex: Int, toIndex: Int): TonightQueue {
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) {
            return this
        }
        val moved = entries.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        val nextCurrentIndex =
            moveTonightIndex(
                itemCount = entries.size,
                currentIndex = currentIndex,
                fromIndex = fromIndex,
                toIndex = toIndex,
                preserveCurrent = started,
            )
        return copy(entries = moved, currentIndex = nextCurrentIndex)
    }

    fun startAt(index: Int = currentIndex): TonightQueue {
        if (entries.isEmpty()) return this
        return copy(
            currentIndex = index.coerceIn(entries.indices),
            started = true,
        )
    }

    fun updateProgress(
        index: Int,
        positionMs: Long,
        durationMs: Long,
    ): TonightQueue {
        val entry = entries.getOrNull(index) ?: return this
        val updated =
            entry.copy(
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = maxOf(entry.durationMs, durationMs.coerceAtLeast(0L)),
            )
        return copy(
            entries = entries.toMutableList().also { it[index] = updated },
        )
    }

    /** Marks one item complete and clears the plan when all items are done. */
    fun completeAt(index: Int): TonightQueue {
        val entry = entries.getOrNull(index) ?: return this
        val updatedEntries =
            entries.toMutableList().also { it[index] = entry.copy(completed = true) }
        if (updatedEntries.all(TonightQueueEntry::completed)) return TonightQueue()
        val nextIndex =
            updatedEntries.indices.firstOrNull { it > index && !updatedEntries[it].completed }
                ?: updatedEntries.indices.firstOrNull { !updatedEntries[it].completed }
                ?: index.coerceAtMost(updatedEntries.lastIndex)
        return copy(
            entries = updatedEntries,
            currentIndex = nextIndex,
            started = started,
        )
    }

    fun clear(): TonightQueue = TonightQueue()

    fun compactCompleted(): TonightQueue {
        if (entries.none(TonightQueueEntry::completed)) return this
        val currentId = current?.contentId
        val remaining = entries.filterNot(TonightQueueEntry::completed)
        if (remaining.isEmpty()) return TonightQueue()
        val nextIndex =
            currentId
                ?.let { id -> remaining.indexOfFirst { it.contentId == id } }
                ?.takeIf { it >= 0 }
                ?: 0
        return copy(entries = remaining, currentIndex = nextIndex)
    }
}

/** Pure index bookkeeping used by the Android-backed queue model and JVM tests. */
internal fun moveTonightIndex(
    itemCount: Int,
    currentIndex: Int,
    fromIndex: Int,
    toIndex: Int,
    preserveCurrent: Boolean = true,
): Int {
    if (
        itemCount <= 0 ||
            currentIndex !in 0 until itemCount ||
            fromIndex !in 0 until itemCount ||
            toIndex !in 0 until itemCount ||
            fromIndex == toIndex
    ) {
        return currentIndex.coerceAtLeast(0)
    }
    if (!preserveCurrent) return 0
    return when {
        currentIndex == fromIndex -> toIndex
        fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
        fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
        else -> currentIndex
    }.coerceIn(0 until itemCount)
}

internal fun resolveTonightStartPosition(
    positionMs: Long,
    durationMs: Long,
): Long {
    val position = positionMs.coerceAtLeast(0L)
    val duration = durationMs.coerceAtLeast(0L)
    if (duration == 0L) return position
    val completionWindowMs = tonightCompletionWindowMs(duration)
    return if (position >= duration - completionWindowMs) 0L else position
}

internal fun isConfirmedTonightEnd(
    positionMs: Long,
    durationMs: Long,
): Boolean {
    val duration = durationMs.coerceAtLeast(0L)
    if (duration == 0L) return true
    return positionMs.coerceAtLeast(0L) >= duration - tonightCompletionWindowMs(duration)
}

private fun tonightCompletionWindowMs(durationMs: Long): Long =
    minOf(5_000L, (durationMs / 100L).coerceAtLeast(1L))

internal fun removeTonightIndex(
    itemCount: Int,
    currentIndex: Int,
    removedIndex: Int,
): Int {
    if (itemCount <= 1) return 0
    if (removedIndex !in 0 until itemCount) return currentIndex.coerceIn(0 until itemCount)
    return when {
        removedIndex < currentIndex -> currentIndex - 1
        removedIndex == currentIndex -> currentIndex.coerceAtMost(itemCount - 2)
        else -> currentIndex.coerceAtMost(itemCount - 2)
    }.coerceAtLeast(0)
}

fun tonightEntry(
    media: MediaSourceRef,
    contentId: String = "uri:${media.uri}",
): TonightQueueEntry =
    TonightQueueEntry(
        contentId = contentId,
        media = media,
    )
