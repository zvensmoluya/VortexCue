package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranslationRequestBudget(maxParallelRequests: Int = 4) {
    private val mutex = Mutex()
    private val cooldownMutex = Mutex()
    private val waiters = mutableSetOf<CompletableDeferred<Unit>>()
    private var parallelLimit = maxParallelRequests.coerceAtLeast(1)
    private var inFlight = 0
    private var cooldownEpoch = 0L

    suspend fun acquire(): Permit {
        cooldownMutex.lock()
        try {
            while (true) {
                var acquired = false
                val waiter = CompletableDeferred<Unit>()
                mutex.withLock {
                    if (inFlight < parallelLimit) {
                        inFlight += 1
                        acquired = true
                    } else {
                        waiters += waiter
                    }
                }
                if (acquired) return Permit(mutex.withLock { cooldownEpoch })
                try {
                    waiter.await()
                } finally {
                    mutex.withLock { waiters -= waiter }
                }
            }
        } finally {
            cooldownMutex.unlock()
        }
    }

    suspend fun degradeToSerial() {
        mutex.withLock { parallelLimit = 1 }
    }

    suspend fun awaitSharedBackoff(
        observedEpoch: Long,
        delayMs: Long,
        wait: suspend (Long) -> Unit,
    ) {
        cooldownMutex.withLock {
            val shouldWait =
                mutex.withLock {
                    if (cooldownEpoch != observedEpoch) {
                        false
                    } else {
                        cooldownEpoch += 1L
                        true
                    }
                }
            if (shouldWait) wait(delayMs)
        }
    }

    internal suspend fun parallelLimit(): Int = mutex.withLock { parallelLimit }

    inner class Permit internal constructor(
        internal val cooldownEpoch: Long,
    ) {
        private var released = false

        suspend fun release() {
            val toWake =
                mutex.withLock {
                    if (released) return
                    released = true
                    inFlight -= 1
                    waiters.toList()
                }
            toWake.forEach { it.complete(Unit) }
        }
    }
}
