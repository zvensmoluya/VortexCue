package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EncodedAudioBudget(
    private val maxBytes: Long = 32L * 1024L * 1024L,
    maxParallelRequests: Int = 2,
) {
    private val mutex = Mutex()
    private val waiters = mutableSetOf<CompletableDeferred<Unit>>()
    private var parallelLimit = maxParallelRequests.coerceAtLeast(1)
    private var inFlightBytes = 0L
    private var inFlightRequests = 0

    suspend fun acquire(bytes: Long): Permit {
        require(bytes in 1..maxBytes) { "Encoded audio request exceeds in-flight budget" }
        while (true) {
            var acquired = false
            val waiter = CompletableDeferred<Unit>()
            mutex.withLock {
                if (inFlightRequests < parallelLimit && inFlightBytes + bytes <= maxBytes) {
                    inFlightRequests += 1
                    inFlightBytes += bytes
                    acquired = true
                } else {
                    waiters += waiter
                }
            }
            if (acquired) return Permit(bytes)
            try {
                waiter.await()
            } finally {
                mutex.withLock { waiters -= waiter }
            }
        }
    }

    suspend fun degradeToSerial() {
        mutex.withLock { parallelLimit = 1 }
    }

    suspend fun snapshot(): Snapshot =
        mutex.withLock { Snapshot(inFlightBytes, inFlightRequests, parallelLimit) }

    inner class Permit internal constructor(private val bytes: Long) {
        private var released = false

        suspend fun release() {
            val toWake =
                mutex.withLock {
                    if (released) return
                    released = true
                    inFlightRequests -= 1
                    inFlightBytes -= bytes
                    waiters.toList()
                }
            toWake.forEach { it.complete(Unit) }
        }
    }

    data class Snapshot(
        val inFlightBytes: Long,
        val inFlightRequests: Int,
        val parallelLimit: Int,
    )
}

suspend inline fun <T> EncodedAudioBudget.withPermit(bytes: Long, block: () -> T): T {
    val permit = acquire(bytes)
    return try {
        block()
    } finally {
        permit.release()
    }
}
