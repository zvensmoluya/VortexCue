package io.github.zvensmoluya.vortexcue.playback

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMs: Long = 0L,
    val configuredDurationMs: Long = DefaultSleepTimerDurationMs,
    val didFinish: Boolean = false,
)

internal object SleepTimerCommands {
    const val ActionGet = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_GET"
    const val ActionSetDuration = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_SET_DURATION"
    const val ActionCancel = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_CANCEL"
    const val DurationMs = "duration_ms"
    const val IsActive = "is_active"
    const val RemainingMs = "remaining_ms"
    const val ConfiguredDurationMs = "configured_duration_ms"
    const val DidFinish = "did_finish"

    val Get = SessionCommand(ActionGet, Bundle.EMPTY)
    val SetDuration = SessionCommand(ActionSetDuration, Bundle.EMPTY)
    val Cancel = SessionCommand(ActionCancel, Bundle.EMPTY)
    val All = listOf(Get, SetDuration, Cancel)
}

@Composable
internal fun rememberSleepTimerState(
    controller: MediaController,
    revision: Int,
): SleepTimerState {
    var state by remember(controller) { mutableStateOf(SleepTimerState()) }

    LaunchedEffect(controller, revision) {
        while (isActive) {
            state = querySleepTimer(controller)
            delay(if (state.isActive) 1_000L else 4_000L)
        }
    }
    return state
}

internal fun MediaController.setSleepTimer(durationMs: Long) {
    sendCustomCommand(
        SleepTimerCommands.SetDuration,
        Bundle().apply { putLong(SleepTimerCommands.DurationMs, durationMs) },
    )
}

internal fun MediaController.cancelSleepTimer() {
    sendCustomCommand(SleepTimerCommands.Cancel, Bundle.EMPTY)
}

private suspend fun querySleepTimer(controller: MediaController): SleepTimerState {
    val result =
        runCatching {
            controller
                .sendCustomCommand(SleepTimerCommands.Get, Bundle.EMPTY)
                .await()
        }.getOrNull() ?: return SleepTimerState()
    return SleepTimerState(
        isActive = result.extras.getBoolean(SleepTimerCommands.IsActive),
        remainingMs = result.extras.getLong(SleepTimerCommands.RemainingMs).coerceAtLeast(0L),
        configuredDurationMs =
            result.extras
                .getLong(SleepTimerCommands.ConfiguredDurationMs, DefaultSleepTimerDurationMs)
                .coerceAtLeast(1L),
        didFinish = result.extras.getBoolean(SleepTimerCommands.DidFinish),
    )
}

internal const val DefaultSleepTimerDurationMs = 30L * 60_000L

private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                runCatching(::get)
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.cancel(it) }
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation { cancel(true) }
    }
