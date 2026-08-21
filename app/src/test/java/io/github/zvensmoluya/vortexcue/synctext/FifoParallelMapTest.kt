package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FifoParallelMapTest {
    @Test
    fun `bounded workers claim later items only after earlier work frees a slot`() = runBlocking {
        val gates = List(6) { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        val result =
            async {
                fifoParallelMap((0 until 6).toList(), parallelism = 2) { item ->
                    started += item
                    gates[item].await()
                    item * 10
                }
            }

        withTimeout(1_000L) {
            while (started.size < 2) yield()
        }
        assertEquals(setOf(0, 1), started.toSet())
        assertFalse(started.any { it >= 2 })

        gates[1].complete(Unit)
        withTimeout(1_000L) {
            while (2 !in started) yield()
        }
        assertTrue(2 in started)
        assertFalse(3 in started)

        gates[0].complete(Unit)
        withTimeout(1_000L) {
            while (3 !in started) yield()
        }
        assertTrue(3 in started)
        gates.drop(2).forEach { it.complete(Unit) }

        assertEquals(listOf(0, 10, 20, 30, 40, 50), result.await())
    }
}
