package io.github.zvensmoluya.vortexcue.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.zvensmoluya.vortexcue.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var playbackPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var timerDeadlineMs = 0L
    private var stopAfterCurrentItem = false

    private val sleepTimerListener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                if (
                    stopAfterCurrentItem &&
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                ) {
                    playbackPlayer?.pause()
                    clearSleepTimer()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (stopAfterCurrentItem && playbackState == Player.STATE_ENDED) {
                    clearSleepTimer()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        val player =
            ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                trackSelectionParameters =
                    trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
            }
        playbackPlayer = player
        player.addListener(sleepTimerListener)
        val sessionActivity =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        mediaSession =
            MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setCallback(SessionCallback())
                .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        clearSleepTimer()
        serviceScope.cancel()
        mediaSession?.run {
            player.removeListener(sleepTimerListener)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun setDurationTimer(durationMs: Long) {
        timerJob?.cancel()
        stopAfterCurrentItem = false
        timerDeadlineMs = SystemClock.elapsedRealtime() + durationMs
        timerJob =
            serviceScope.launch {
                delay(durationMs)
                playbackPlayer?.pause()
                clearSleepTimer()
            }
    }

    private fun setEndOfItemTimer() {
        timerJob?.cancel()
        timerJob = null
        timerDeadlineMs = 0L
        stopAfterCurrentItem = true
    }

    private fun clearSleepTimer() {
        timerJob?.cancel()
        timerJob = null
        timerDeadlineMs = 0L
        stopAfterCurrentItem = false
    }

    private fun sleepTimerResult(): SessionResult {
        val mode =
            when {
                stopAfterCurrentItem -> SleepTimerMode.EndOfItem
                timerDeadlineMs > 0L -> SleepTimerMode.Duration
                else -> SleepTimerMode.None
            }
        val remaining =
            if (mode == SleepTimerMode.Duration) {
                (timerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            } else {
                0L
            }
        return SessionResult(
            SessionResult.RESULT_SUCCESS,
            Bundle().apply {
                putString(SleepTimerCommands.Mode, mode.name)
                putLong(SleepTimerCommands.RemainingMs, remaining)
            },
        )
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands =
                base.availableSessionCommands
                    .buildUpon()
                    .apply { SleepTimerCommands.All.forEach(::add) }
                    .build()
            return MediaSession.ConnectionResult.accept(
                commands,
                base.availablePlayerCommands,
            )
        }

        @UnstableApi
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val result =
                when (customCommand.customAction) {
                    SleepTimerCommands.ActionGet -> sleepTimerResult()
                    SleepTimerCommands.ActionSetDuration -> {
                        val durationMs = args.getLong(SleepTimerCommands.DurationMs)
                        if (durationMs <= 0L) {
                            SessionResult(SessionError.ERROR_BAD_VALUE)
                        } else {
                            setDurationTimer(durationMs)
                            sleepTimerResult()
                        }
                    }
                    SleepTimerCommands.ActionEndOfItem -> {
                        setEndOfItemTimer()
                        sleepTimerResult()
                    }
                    SleepTimerCommands.ActionCancel -> {
                        clearSleepTimer()
                        sleepTimerResult()
                    }
                    else -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                }
            return Futures.immediateFuture(result)
        }
    }
}
