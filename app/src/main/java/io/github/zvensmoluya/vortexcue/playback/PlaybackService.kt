package io.github.zvensmoluya.vortexcue.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.zvensmoluya.vortexcue.app.MainActivity
import io.github.zvensmoluya.vortexcue.app.appContainer
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.PlaybackRecoveryProjection
import io.github.zvensmoluya.vortexcue.data.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var playbackPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queueMutex = Mutex()
    private lateinit var contentRepository: ContentRepository
    private lateinit var playbackRepository: PlaybackStateRepository
    private var materializationState = PlayerMaterializationState.NotLoaded
    private var runtimeQueueRevision = 0L
    private var suppressPersistence = false
    private var timerJob: Job? = null
    private var progressJob: Job? = null
    private var timerDeadlineMs = 0L
    private var configuredTimerDurationMs = DefaultSleepTimerDurationMs
    private var sleepTimerDidFinish = false

    private val sleepTimerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) sleepTimerDidFinish = false
            }
        }

    private val persistenceListener =
        object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (
                    materializationState == PlayerMaterializationState.Materializing &&
                        !timeline.isEmpty
                ) {
                    materializationState = PlayerMaterializationState.Materialized
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (
                    suppressPersistence ||
                        materializationState != PlayerMaterializationState.Materialized ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                ) {
                    return
                }
                val expectedRevision = runtimeQueueRevision
                serviceScope.launch {
                    queueMutex.withLock {
                        val player = playbackPlayer ?: return@withLock
                        val projection =
                            playbackRepository.setCurrent(
                                contentId = mediaItem?.mediaId?.takeIf(String::isNotBlank),
                                positionMs = player.currentPosition,
                                durationMs = player.duration.validDuration(),
                                expectedRevision = expectedRevision,
                            ) ?: return@withLock
                        runtimeQueueRevision = projection.queueRevision
                        mediaItem?.mediaId
                            ?.takeIf(String::isNotBlank)
                            ?.let { contentId -> runCatching { contentRepository.recordOpened(contentId) } }
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) persistProgressAsync()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) persistProgressAsync()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                persistPlaybackParametersAsync()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                persistPlaybackParametersAsync()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                persistPlaybackParametersAsync()
            }
        }

    override fun onCreate() {
        super.onCreate()
        contentRepository = appContainer.contentRepository
        playbackRepository = appContainer.playbackStateRepository

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
        player.addListener(persistenceListener)
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
        progressJob =
            serviceScope.launch {
                while (isActive) {
                    delay(ProgressSampleIntervalMs)
                    persistProgress()
                }
            }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        clearSleepTimer()
        progressJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.removeListener(sleepTimerListener)
            player.removeListener(persistenceListener)
            player.release()
            release()
        }
        mediaSession = null
        playbackPlayer = null
        super.onDestroy()
    }

    private fun persistProgressAsync() {
        val snapshot = currentProgressSnapshot() ?: return
        serviceScope.launch { persistProgress(snapshot) }
    }

    private suspend fun persistProgress() {
        val snapshot = currentProgressSnapshot() ?: return
        persistProgress(snapshot)
    }

    private suspend fun persistProgress(snapshot: ProgressSnapshot) {
        playbackRepository.updateProgress(
            contentId = snapshot.contentId,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            playbackSpeed = snapshot.playbackSpeed,
            expectedRevision = snapshot.expectedRevision,
        )
    }

    private fun currentProgressSnapshot(): ProgressSnapshot? {
        if (suppressPersistence || materializationState != PlayerMaterializationState.Materialized) return null
        val player = playbackPlayer ?: return null
        return ProgressSnapshot(
            contentId = player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank),
            positionMs = player.currentPosition,
            durationMs = player.duration.validDuration(),
            playbackSpeed = player.playbackParameters.speed,
            expectedRevision = runtimeQueueRevision,
        )
    }

    private fun persistPlaybackParametersAsync() {
        if (suppressPersistence || materializationState != PlayerMaterializationState.Materialized) return
        serviceScope.launch {
            val player = playbackPlayer ?: return@launch
            playbackRepository.updatePlaybackParameters(
                playbackSpeed = player.playbackParameters.speed,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
        }
    }

    private suspend fun ensureMaterializedInside(): PlaybackRecoveryProjection {
        if (materializationState.ownsRuntimePlaybackState()) {
            return playbackRepository.recovery()
        }
        materializationState = PlayerMaterializationState.Materializing
        return runCatching { playbackRepository.recovery() }
            .onSuccess { applyProjection(it, explicitlyCleared = false) }
            .onFailure { materializationState = PlayerMaterializationState.NotLoaded }
            .getOrThrow()
    }

    private fun applyProjection(
        projection: PlaybackRecoveryProjection,
        explicitlyCleared: Boolean,
    ) {
        val player = playbackPlayer ?: return
        val shouldPrepare = player.playbackState != Player.STATE_IDLE
        val shouldPlay = player.playWhenReady
        val previousCurrentId = player.currentMediaItem?.mediaId
        suppressPersistence = true
        try {
            player.playbackParameters = PlaybackParameters(projection.playbackSpeed)
            player.shuffleModeEnabled = projection.shuffleEnabled
            player.repeatMode = projection.repeatMode
            if (projection.queue.isEmpty()) {
                player.clearMediaItems()
            } else {
                player.setMediaItems(
                    projection.queue.map { it.toMediaItem() },
                    projection.currentIndex.coerceIn(0, projection.queue.lastIndex),
                    projection.positionMs,
                )
                if (shouldPrepare) {
                    player.prepare()
                    player.playWhenReady = shouldPlay
                }
            }
            runtimeQueueRevision = projection.queueRevision
            materializationState =
                if (explicitlyCleared) {
                    PlayerMaterializationState.ExplicitlyCleared
                } else {
                    PlayerMaterializationState.Materialized
                }
            val currentId = projection.currentItem?.contentId
            if (shouldPlay && currentId != null && currentId != previousCurrentId) {
                serviceScope.launch { runCatching { contentRepository.recordOpened(currentId) } }
            }
        } finally {
            suppressPersistence = false
        }
    }

    private fun setDurationTimer(durationMs: Long) {
        timerJob?.cancel()
        sleepTimerDidFinish = false
        configuredTimerDurationMs = durationMs
        timerDeadlineMs = SystemClock.elapsedRealtime() + durationMs
        timerJob =
            serviceScope.launch {
                delay(durationMs)
                playbackPlayer?.pause()
                clearSleepTimer(didFinish = true)
            }
    }

    private fun clearSleepTimer(didFinish: Boolean = false) {
        timerJob?.cancel()
        timerJob = null
        timerDeadlineMs = 0L
        sleepTimerDidFinish = didFinish
    }

    private fun sleepTimerResult(): SessionResult {
        val isActive = timerDeadlineMs > 0L
        val remaining =
            if (isActive) {
                (timerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            } else {
                0L
            }
        return SessionResult(
            SessionResult.RESULT_SUCCESS,
            Bundle().apply {
                putBoolean(SleepTimerCommands.IsActive, isActive)
                putLong(SleepTimerCommands.RemainingMs, remaining)
                putLong(SleepTimerCommands.ConfiguredDurationMs, configuredTimerDurationMs)
                putBoolean(SleepTimerCommands.DidFinish, sleepTimerDidFinish)
            },
        )
    }

    private fun queueResult(projection: PlaybackRecoveryProjection): SessionResult =
        SessionResult(
            SessionResult.RESULT_SUCCESS,
            Bundle().apply {
                putLong(PlaybackCommands.QueueRevision, projection.queueRevision)
                putString(PlaybackCommands.Materialization, materializationState.name)
            },
        )

    private inner class SessionCallback : MediaSession.Callback {
        @UnstableApi
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val access = controllerAccess(controller)
            if (access == ControllerAccess.Rejected) {
                return MediaSession.ConnectionResult.reject()
            }
            val base = super.onConnect(session, controller)
            val transportCommands =
                base.availablePlayerCommands
                    .buildUpon()
                    .remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .remove(Player.COMMAND_SET_PLAYLIST_METADATA)
                    .build()
            if (access == ControllerAccess.Trusted) {
                return MediaSession.ConnectionResult.accept(
                    base.availableSessionCommands,
                    transportCommands,
                )
            }
            val commands =
                base.availableSessionCommands
                    .buildUpon()
                    .apply {
                        SleepTimerCommands.All.forEach(::add)
                        PlaybackCommands.All.forEach(::add)
                    }.build()
            return MediaSession.ConnectionResult.accept(commands, transportCommands)
        }

        @UnstableApi
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            launchFuture {
                queueMutex.withLock {
                    if (materializationState == PlayerMaterializationState.ExplicitlyCleared) {
                        return@withLock MediaSession.MediaItemsWithStartPosition(
                            emptyList(),
                            C.INDEX_UNSET,
                            0L,
                        )
                    }
                    materializationState = PlayerMaterializationState.Materializing
                    try {
                        val projection = playbackRepository.recovery()
                        val player = playbackPlayer
                        suppressPersistence = true
                        try {
                            player?.playbackParameters = PlaybackParameters(projection.playbackSpeed)
                            player?.shuffleModeEnabled = projection.shuffleEnabled
                            player?.repeatMode = projection.repeatMode
                        } finally {
                            suppressPersistence = false
                        }
                        runtimeQueueRevision = projection.queueRevision
                        materializationState =
                            if (projection.queue.isEmpty()) {
                                PlayerMaterializationState.Materialized
                            } else {
                                PlayerMaterializationState.Materializing
                            }
                        if (isForPlayback) {
                            projection.currentItem?.contentId?.let { contentId ->
                                runCatching { contentRepository.recordOpened(contentId) }
                            }
                        }
                        MediaSession.MediaItemsWithStartPosition(
                            projection.queue.map { it.toMediaItem() },
                            projection.currentIndex.takeIf { projection.queue.isNotEmpty() }
                                ?: C.INDEX_UNSET,
                            projection.positionMs,
                        )
                    } catch (error: Exception) {
                        materializationState = PlayerMaterializationState.NotLoaded
                        throw error
                    }
                }
            }

        @UnstableApi
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controllerAccess(controller) != ControllerAccess.OwnApp) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            val sleepResult = handleSleepTimerCommand(customCommand, args)
            if (sleepResult != null) return Futures.immediateFuture(sleepResult)
            if (customCommand.customAction == PlaybackCommands.GetMaterialization.customAction) {
                return Futures.immediateFuture(
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putString(PlaybackCommands.Materialization, materializationState.name)
                            putLong(PlaybackCommands.QueueRevision, runtimeQueueRevision)
                        },
                    ),
                )
            }
            return launchFuture {
                queueMutex.withLock {
                    when (customCommand.customAction) {
                        PlaybackCommands.Replace.customAction -> {
                            val contentIds = args.getStringArrayList(PlaybackCommands.ContentIds).orEmpty()
                            val workIds = args.getStringArrayList(PlaybackCommands.OriginWorkIds).orEmpty()
                            val playlistEntryIds =
                                args.getStringArrayList(PlaybackCommands.OriginPlaylistEntryIds).orEmpty()
                            val entries = contentIds.mapIndexed { index, contentId ->
                                io.github.zvensmoluya.vortexcue.data.QueueContentRef(
                                    contentId = contentId,
                                    originWorkId = workIds.getOrNull(index)?.takeIf(String::isNotEmpty),
                                    originPlaylistEntryId =
                                        playlistEntryIds.getOrNull(index)?.takeIf(String::isNotEmpty),
                                )
                            }
                            val projection =
                                playbackRepository.replaceEntries(
                                    entries = entries,
                                    currentIndex = args.getInt(PlaybackCommands.CurrentIndex),
                                    positionMs = args.getLong(PlaybackCommands.PositionMs),
                                )
                            applyProjection(projection, explicitlyCleared = false)
                            queueResult(projection)
                        }
                        PlaybackCommands.Append.customAction -> {
                            ensureMaterializedInside()
                            val projection =
                                playbackRepository.append(
                                    args.getStringArrayList(PlaybackCommands.ContentIds).orEmpty(),
                                )
                            applyProjection(projection, explicitlyCleared = false)
                            queueResult(projection)
                        }
                        PlaybackCommands.Remove.customAction -> {
                            ensureMaterializedInside()
                            val contentId = args.getString(PlaybackCommands.ContentId)
                                ?: return@withLock SessionResult(SessionError.ERROR_BAD_VALUE)
                            val projection = playbackRepository.remove(contentId)
                            applyProjection(projection, explicitlyCleared = projection.queue.isEmpty())
                            queueResult(projection)
                        }
                        PlaybackCommands.RemoveWork.customAction -> {
                            ensureMaterializedInside()
                            val workId = args.getString(PlaybackCommands.WorkId)
                                ?: return@withLock SessionResult(SessionError.ERROR_BAD_VALUE)
                            val projection = playbackRepository.removeWork(workId)
                            applyProjection(projection, explicitlyCleared = projection.queue.isEmpty())
                            queueResult(projection)
                        }
                        PlaybackCommands.Move.customAction -> {
                            ensureMaterializedInside()
                            val projection =
                                playbackRepository.move(
                                    args.getInt(PlaybackCommands.FromIndex, -1),
                                    args.getInt(PlaybackCommands.ToIndex, -1),
                                )
                            applyProjection(projection, explicitlyCleared = false)
                            queueResult(projection)
                        }
                        PlaybackCommands.Clear.customAction -> {
                            val projection = playbackRepository.clear()
                            applyProjection(projection, explicitlyCleared = true)
                            queueResult(projection)
                        }
                        PlaybackCommands.Materialize.customAction -> {
                            val projection = ensureMaterializedInside()
                            queueResult(projection)
                        }
                        PlaybackCommands.Relink.customAction -> {
                            val contentId = args.getString(PlaybackCommands.ContentId)
                                ?: return@withLock SessionResult(SessionError.ERROR_BAD_VALUE)
                            val replacement = args.relinkMediaSource()
                                ?: return@withLock SessionResult(SessionError.ERROR_BAD_VALUE)
                            val previousState = materializationState
                            val canonical = contentRepository.relink(contentId, replacement)
                            if (previousState.ownsRuntimePlaybackState()) {
                                val projection = playbackRepository.recovery()
                                applyProjection(
                                    projection,
                                    explicitlyCleared =
                                        previousState == PlayerMaterializationState.ExplicitlyCleared &&
                                            projection.queue.isEmpty(),
                                )
                            }
                            SessionResult(
                                SessionResult.RESULT_SUCCESS,
                                Bundle().apply { putString(PlaybackCommands.ContentId, canonical) },
                            )
                        }
                        else -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                    }
                }
            }
        }

        @UnstableApi
        private fun handleSleepTimerCommand(
            command: SessionCommand,
            args: Bundle,
        ): SessionResult? =
            when (command.customAction) {
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
                SleepTimerCommands.ActionCancel -> {
                    clearSleepTimer()
                    sleepTimerResult()
                }
                else -> null
            }
    }

    private data class ProgressSnapshot(
        val contentId: String?,
        val positionMs: Long,
        val durationMs: Long,
        val playbackSpeed: Float,
        val expectedRevision: Long,
    )

    @UnstableApi
    private fun controllerAccess(controller: MediaSession.ControllerInfo): ControllerAccess =
        resolveControllerAccess(
            appPackageName = packageName,
            appUid = applicationInfo.uid,
            controllerPackageName = controller.packageName,
            controllerUid = controller.uid,
            isPackageNameVerified = controller.isPackageNameVerified,
            isTrusted = controller.isTrusted,
        )

    private fun <T> launchFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        serviceScope.launch {
            runCatching { block() }
                .onSuccess(future::set)
                .onFailure(future::setException)
        }
        return future
    }

    private companion object {
        const val ProgressSampleIntervalMs = 5_000L
    }
}

private fun Long.validDuration(): Long =
    takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

internal fun isConfirmedQueueEnd(
    positionMs: Long,
    durationMs: Long,
): Boolean {
    val duration = durationMs.coerceAtLeast(0L)
    if (duration == 0L) return true
    val completionWindowMs = minOf(5_000L, (duration / 100L).coerceAtLeast(1L))
    return positionMs.coerceAtLeast(0L) >= duration - completionWindowMs
}
