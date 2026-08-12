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

enum class SleepTimerMode {
    None,
    Duration,
    EndOfItem,
    EndOfTonight,
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.None,
    val remainingMs: Long = 0L,
) {
    val isActive: Boolean
        get() = mode != SleepTimerMode.None
}

internal object SleepTimerCommands {
    const val ActionGet = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_GET"
    const val ActionSetDuration = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_SET_DURATION"
    const val ActionEndOfItem = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_END_OF_ITEM"
    const val ActionEndOfTonight = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_END_OF_TONIGHT"
    const val ActionCancel = "io.github.zvensmoluya.vortexcue.SLEEP_TIMER_CANCEL"
    const val DurationMs = "duration_ms"
    const val Mode = "mode"
    const val RemainingMs = "remaining_ms"

    val Get = SessionCommand(ActionGet, Bundle.EMPTY)
    val SetDuration = SessionCommand(ActionSetDuration, Bundle.EMPTY)
    val EndOfItem = SessionCommand(ActionEndOfItem, Bundle.EMPTY)
    val EndOfTonight = SessionCommand(ActionEndOfTonight, Bundle.EMPTY)
    val Cancel = SessionCommand(ActionCancel, Bundle.EMPTY)
    val All = listOf(Get, SetDuration, EndOfItem, EndOfTonight, Cancel)
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

internal fun MediaController.stopAfterCurrentItem() {
    sendCustomCommand(SleepTimerCommands.EndOfItem, Bundle.EMPTY)
}

internal fun MediaController.stopAfterTonight() {
    sendCustomCommand(SleepTimerCommands.EndOfTonight, Bundle.EMPTY)
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
    val mode =
        runCatching {
            SleepTimerMode.valueOf(result.extras.getString(SleepTimerCommands.Mode).orEmpty())
        }.getOrDefault(SleepTimerMode.None)
    return SleepTimerState(
        mode = mode,
        remainingMs = result.extras.getLong(SleepTimerCommands.RemainingMs).coerceAtLeast(0L),
    )
}

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
