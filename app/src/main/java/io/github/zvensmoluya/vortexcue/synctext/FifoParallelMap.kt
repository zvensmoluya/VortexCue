package io.github.zvensmoluya.vortexcue.synctext

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal suspend fun <T, R : Any> fifoParallelMap(
    items: List<T>,
    parallelism: Int,
    transform: suspend (T) -> R,
): List<R> {
    require(parallelism > 0) { "Parallelism must be positive" }
    if (items.isEmpty()) return emptyList()

    val nextIndex = AtomicInteger(0)
    val results = MutableList<R?>(items.size) { null }
    coroutineScope {
        List(minOf(parallelism, items.size)) {
            async {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= items.size) break
                    results[index] = transform(items[index])
                }
            }
        }.awaitAll()
    }
    return results.mapIndexed { index, result ->
        requireNotNull(result) { "Parallel worker did not produce result $index" }
    }
}
