package io.github.zvensmoluya.vortexcue.core.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class OrderedStateWriter<T>(
    private val coroutineScope: CoroutineScope,
    private val write: suspend (T) -> Unit,
) {
    private var tail: Job? = null

    fun submit(value: T) {
        enqueue(value)
    }

    suspend fun submitAndAwait(value: T) {
        enqueue(value).await()
    }

    private fun enqueue(value: T): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        val previous = tail
        val job =
            coroutineScope.launch {
                previous?.join()
                try {
                    write(value)
                    completion.complete(Unit)
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                    throw error
                }
            }
        tail = job
        job.invokeOnCompletion { error ->
            if (error != null && !completion.isCompleted) {
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    suspend fun awaitIdle() {
        while (true) {
            val pending = tail ?: return
            pending.join()
            if (pending === tail) return
        }
    }
}
