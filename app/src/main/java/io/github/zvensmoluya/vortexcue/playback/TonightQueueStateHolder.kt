package io.github.zvensmoluya.vortexcue.playback

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.storage.OrderedStateWriter
import kotlinx.coroutines.CoroutineScope

@Stable
internal class TonightQueueStateHolder(
    context: Context,
    coroutineScope: CoroutineScope,
    private val store: TonightQueueStore = TonightQueueStore(context),
) {
    private val writer = OrderedStateWriter(coroutineScope, store::save)

    var state by mutableStateOf(TonightQueue())
        private set

    suspend fun restore() {
        state = runCatching { store.load() }.getOrDefault(TonightQueue())
    }

    fun add(media: MediaSourceRef, contentId: String) {
        update(state.add(tonightEntry(media, contentId)))
    }

    fun add(entry: TonightQueueEntry) {
        update(state.add(entry))
    }

    fun addAll(items: List<Pair<MediaSourceRef, String>>) {
        update(
            items.fold(state) { queue, (media, contentId) ->
                queue.add(tonightEntry(media, contentId))
            },
        )
    }

    fun removeAt(index: Int) = update(state.removeAt(index))

    fun move(fromIndex: Int, toIndex: Int) = update(state.move(fromIndex, toIndex))

    fun startAt(index: Int = state.currentIndex) = update(state.startAt(index))

    fun updateProgress(index: Int, positionMs: Long, durationMs: Long) =
        update(state.updateProgress(index, positionMs, durationMs))

    fun completeAt(index: Int) = update(state.completeAt(index))

    fun clear() = update(state.clear())

    fun replace(queue: TonightQueue) = update(queue)

    suspend fun awaitPendingWrites() = writer.awaitIdle()

    private fun update(next: TonightQueue) {
        if (next == state) return
        state = next
        writer.submit(next)
    }
}
