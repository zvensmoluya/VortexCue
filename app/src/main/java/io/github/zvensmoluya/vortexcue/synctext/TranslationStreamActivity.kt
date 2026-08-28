package io.github.zvensmoluya.vortexcue.synctext

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps best-effort translation stream activity in memory for lightweight UI feedback.
 * Durable task progress continues to represent committed work units only.
 */
class TranslationStreamActivityTracker(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val publishIntervalMs: Long = DefaultPublishIntervalMs,
) {
    private val lock = Any()
    private val tasks = mutableMapOf<String, TaskEstimate>()
    private val _tokenEstimates = MutableStateFlow<Map<String, Long>>(emptyMap())

    val tokenEstimates: StateFlow<Map<String, Long>> = _tokenEstimates.asStateFlow()

    init {
        require(publishIntervalMs >= 0L) { "Translation activity publish interval must not be negative" }
    }

    fun beginAttempt(taskId: String, streamId: String) {
        synchronized(lock) {
            val task = tasks.getOrPut(taskId, ::TaskEstimate)
            task.streams[streamId] = StreamEstimate()
            publishLocked(taskId, task, force = true)
        }
    }

    fun recordDelta(taskId: String, streamId: String, delta: String) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            val task = tasks.getOrPut(taskId, ::TaskEstimate)
            val stream = task.streams.getOrPut(streamId, ::StreamEstimate)
            delta.forEach { character ->
                if (character.code < 128) {
                    stream.asciiCharacters += 1L
                } else {
                    stream.nonAsciiCharacters += 1L
                }
            }
            publishLocked(taskId, task, force = false)
        }
    }

    fun finishAttempt(taskId: String) {
        synchronized(lock) {
            tasks[taskId]?.let { publishLocked(taskId, it, force = true) }
        }
    }

    fun clear(taskId: String) {
        synchronized(lock) {
            tasks.remove(taskId)
            if (taskId in _tokenEstimates.value) {
                _tokenEstimates.value = _tokenEstimates.value - taskId
            }
        }
    }

    private fun publishLocked(taskId: String, task: TaskEstimate, force: Boolean) {
        val timestamp = nowMs()
        val estimate = task.streams.values.sumOf(StreamEstimate::estimatedTokens)
        val firstVisibleActivity = estimate > 0L && task.lastPublishedEstimate == 0L
        if (
            !force &&
            !firstVisibleActivity &&
            task.lastPublishedAtMs?.let { timestamp - it < publishIntervalMs } == true
        ) {
            return
        }
        task.lastPublishedAtMs = timestamp
        task.lastPublishedEstimate = estimate
        val current = _tokenEstimates.value
        _tokenEstimates.value =
            if (estimate > 0L) {
                current + (taskId to estimate)
            } else {
                current - taskId
            }
    }

    private class TaskEstimate {
        val streams = mutableMapOf<String, StreamEstimate>()
        var lastPublishedAtMs: Long? = null
        var lastPublishedEstimate: Long = 0L
    }

    private class StreamEstimate {
        var asciiCharacters: Long = 0L
        var nonAsciiCharacters: Long = 0L

        fun estimatedTokens(): Long = (asciiCharacters + 3L) / 4L + nonAsciiCharacters
    }

    companion object {
        private const val DefaultPublishIntervalMs = 250L
    }
}
