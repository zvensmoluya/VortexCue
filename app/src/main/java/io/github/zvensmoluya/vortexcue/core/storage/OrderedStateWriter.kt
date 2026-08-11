package io.github.zvensmoluya.vortexcue.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class OrderedStateWriter<T>(
    private val coroutineScope: CoroutineScope,
    private val write: suspend (T) -> Unit,
) {
    private var tail: Job? = null

    fun submit(value: T) {
        val previous = tail
        tail =
            coroutineScope.launch {
                previous?.join()
                write(value)
            }
    }

    suspend fun awaitIdle() {
        while (true) {
            val pending = tail ?: return
            pending.join()
            if (pending === tail) return
        }
    }
}
