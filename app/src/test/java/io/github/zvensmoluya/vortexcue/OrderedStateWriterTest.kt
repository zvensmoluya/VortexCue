package io.github.zvensmoluya.vortexcue.core.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderedStateWriterTest {
    @Test
    fun `writes complete in submission order`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val started = mutableListOf<Int>()
            val completed = mutableListOf<Int>()
            val writer =
                OrderedStateWriter<Int>(this) { value ->
                    started += value
                    if (value == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    completed += value
                }

            writer.submit(1)
            writer.submit(2)
            firstStarted.await()
            yield()

            assertEquals(listOf(1), started)
            releaseFirst.complete(Unit)
            writer.awaitIdle()

            assertEquals(listOf(1, 2), started)
            assertEquals(listOf(1, 2), completed)
        }
}
