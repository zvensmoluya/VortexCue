package io.github.zvensmoluya.vortexcue.app

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.core.storage.*
import io.github.zvensmoluya.vortexcue.library.*
import io.github.zvensmoluya.vortexcue.playback.*
import io.github.zvensmoluya.vortexcue.subtitle.*
import io.github.zvensmoluya.vortexcue.ui.home.*
import io.github.zvensmoluya.vortexcue.ui.player.*
import io.github.zvensmoluya.vortexcue.ui.theme.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppDestination {
    Home,
    Player,
}

private enum class MediaPickTarget {
    Draft,
    LastSession,
}

private enum class SubtitlePickTarget {
    DraftPrimary,
    DraftSecondary,
    PlayerPrimary,
    PlayerSecondary,
}

@Composable
fun VortexCueApp(
    player: MediaController,
    onWindowBrightnessChanged: (Float?) -> Unit,
    onImmersiveModeChanged: (Boolean) -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val playback = rememberPlaybackSnapshot(player)
    val embeddedTextRuntime = rememberEmbeddedTextRuntimeState(player)
    var sleepTimerRevision by remember { mutableIntStateOf(0) }
    val sleepTimer = rememberSleepTimerState(player, sleepTimerRevision)
    val sessionStore = remember(context) { SessionStore(context) }
    val libraryStateHolder =
        remember(context, coroutineScope) { LibraryStateHolder(context, coroutineScope) }
    val tonightStateHolder =
        remember(context, coroutineScope) { TonightQueueStateHolder(context, coroutineScope) }
    val subtitleCache = remember(context) { SubtitleCache(context) }
    val initialSession = remember(player) { player.currentMediaItem?.toActiveSession() }

    var destination by
        rememberSaveable {
            mutableStateOf(
                if (initialSession == null) AppDestination.Home else AppDestination.Player,
            )
        }
    var homeSection by rememberSaveable { mutableStateOf(HomeSection.Home) }
    var playerSurface by rememberSaveable { mutableStateOf(PlayerSurface.Normal) }
    var activeSession by remember { mutableStateOf(initialSession) }
    var lastSession by remember { mutableStateOf<RestorableSession?>(null) }
    var draft by remember { mutableStateOf(initialSession?.toDraft() ?: SessionDraft()) }
    var displayProfile by remember { mutableStateOf(DisplayProfile.Balanced) }
    var glowCalibrationCompleted by remember { mutableStateOf(false) }
    var glowGestureHintSeen by remember { mutableStateOf(false) }
    var restorationCompleted by remember { mutableStateOf(false) }
    var mediaPickTarget by rememberSaveable { mutableStateOf(MediaPickTarget.Draft) }
    var mediaLoadJob by remember { mutableStateOf<Job?>(null) }
    var mediaRequestId by remember { mutableLongStateOf(0L) }
    var primaryDraftSubtitleLoadJob by remember { mutableStateOf<Job?>(null) }
    var secondaryDraftSubtitleLoadJob by remember { mutableStateOf<Job?>(null) }
    var draftSubtitleRequestIds by remember { mutableStateOf(DraftSubtitleRequestIds()) }
    var primaryTrackLoadJob by remember { mutableStateOf<Job?>(null) }
    var primaryTrackRequestId by remember { mutableLongStateOf(0L) }
    var secondaryTrackLoadJob by remember { mutableStateOf<Job?>(null) }
    var secondaryTrackRequestId by remember { mutableLongStateOf(0L) }
    var subtitlePickTarget by rememberSaveable {
        mutableStateOf(SubtitlePickTarget.DraftPrimary)
    }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var pendingAutoPlay by rememberSaveable { mutableStateOf(false) }
    var folderPickTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val libraryState = libraryStateHolder.state
    val tonight = tonightStateHolder.state
    val selectedEmbeddedTrackId =
        activeSession?.media?.let { media ->
            TextTrackSlot.entries
                .mapNotNull { slot -> libraryState.config.activeTextTrack(media, slot) }
                .singleOrNull { it.origin == TimedTextOrigin.Embedded }
                ?.stableId
        }
    fun startPlayer() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
        }
        player.play()
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (pendingAutoPlay) {
                pendingAutoPlay = false
                startPlayer()
            }
        }

    fun playWithNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationPermissionRequested &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            pendingAutoPlay = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPlayer()
        }
    }

    fun togglePlayback() {
        if (player.isPlaying) player.pause() else playWithNotificationPermission()
    }

    fun tonightContentId(media: MediaSourceRef): String =
        libraryStateHolder.state.config.contentUnitFor(media)?.stableId
            ?: "uri:${media.uri}"

    fun resolveTonightMedia(entry: TonightQueueEntry): MediaSourceRef? {
        val config = libraryStateHolder.state.config
        return config.contentUnits.firstOrNull { it.stableId == entry.contentId }?.media
            ?: config.resolveActiveMedia(entry.media)
    }

    fun runtimeMatchesTonightOrder(plan: TonightQueue = tonightStateHolder.state): Boolean {
        if (!plan.started || plan.entries.isEmpty()) return false
        val planIds = plan.entries.map(TonightQueueEntry::contentId)
        val runtimeIds =
            (0 until player.mediaItemCount).mapNotNull { index ->
                player.getMediaItemAt(index).toMediaSourceRef()?.let(::tonightContentId)
            }
        return runtimeIds == planIds
    }

    fun isTonightRuntimeActive(plan: TonightQueue = tonightStateHolder.state): Boolean {
        if (!runtimeMatchesTonightOrder(plan)) return false
        val runtimeMedia = player.currentMediaItem?.toMediaSourceRef() ?: return false
        return plan.current?.contentId == tonightContentId(runtimeMedia)
    }

    fun persistTonightProgress() {
        val plan = tonightStateHolder.state
        if (!isTonightRuntimeActive(plan)) return
        val index = player.currentMediaItemIndex
        if (index !in plan.entries.indices) return
        tonightStateHolder.updateProgress(
            index = index,
            positionMs = player.currentPosition,
            durationMs = maxOf(plan.entries[index].durationMs, playback.durationMs),
        )
    }

    fun addToTonight(media: MediaSourceRef) {
        val resolved = libraryStateHolder.state.config.resolveActiveMedia(media)
        val existing = tonightStateHolder.state.entries.any {
            it.contentId == tonightContentId(resolved)
        }
        if (existing) return
        val wasRuntimeActive = isTonightRuntimeActive()
        val priorProgress =
            lastSession?.takeIf {
                libraryStateHolder.state.config.sameContent(it.session.media, resolved)
            }
        val knownDurationMs = maxOf(priorProgress?.durationMs ?: 0L, resolved.durationMs)
        tonightStateHolder.add(
            TonightQueueEntry(
                contentId = tonightContentId(resolved),
                media = resolved,
                positionMs =
                    resolveTonightStartPosition(
                        positionMs = priorProgress?.positionMs ?: 0L,
                        durationMs = knownDurationMs,
                    ),
                durationMs = knownDurationMs,
            ),
        )
        if (wasRuntimeActive) {
            player.addMediaItem(resolved.toMediaItem())
        }
    }

    fun removeFromTonight(index: Int) {
        val plan = tonightStateHolder.state
        if (index !in plan.entries.indices) return
        val wasRuntimeActive = isTonightRuntimeActive(plan)
        val keepPlaying = player.isPlaying
        val currentItem = player.currentMediaItem
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        if (wasRuntimeActive) persistTonightProgress()
        tonightStateHolder.removeAt(index)
        if (!wasRuntimeActive || index >= player.mediaItemCount) return
        if (plan.entries.size == 1 && currentItem != null) {
            player.setMediaItems(listOf(currentItem), 0, currentPosition)
            player.prepare()
            if (keepPlaying) playWithNotificationPermission()
        } else {
            player.removeMediaItem(index)
        }
    }

    fun moveTonight(fromIndex: Int, toIndex: Int) {
        val plan = tonightStateHolder.state
        if (fromIndex !in plan.entries.indices || toIndex !in plan.entries.indices) return
        val wasRuntimeActive = isTonightRuntimeActive(plan)
        if (wasRuntimeActive) persistTonightProgress()
        tonightStateHolder.move(fromIndex, toIndex)
        if (
            wasRuntimeActive &&
                fromIndex < player.mediaItemCount &&
                toIndex < player.mediaItemCount
        ) {
            player.moveMediaItem(fromIndex, toIndex)
        }
    }

    fun clearTonight() {
        val plan = tonightStateHolder.state
        if (plan.isEmpty) return
        val keepPlaying = player.isPlaying
        val currentItem = player.currentMediaItem
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val wasRuntimeActive = isTonightRuntimeActive(plan)
        if (wasRuntimeActive) persistTonightProgress()
        tonightStateHolder.clear()
        if (wasRuntimeActive && currentItem != null) {
            player.setMediaItems(listOf(currentItem), 0, currentPosition)
            player.prepare()
            if (keepPlaying) playWithNotificationPermission()
        }
    }

    fun currentRestorableSession(session: ActiveSession): RestorableSession {
        val previous =
            lastSession?.takeIf {
                libraryStateHolder.state.config.sameContent(it.session.media, session.media)
            }
        val queue =
            (0 until player.mediaItemCount)
                .mapNotNull { index ->
                    val item = player.getMediaItemAt(index)
                    val decoded = item.toMediaSourceRef() ?: return@mapNotNull null
                    libraryState.items.firstOrNull { it.uri == decoded.uri }
                        ?: previous?.queue?.firstOrNull { it.uri == decoded.uri }
                        ?: decoded
                }
                .ifEmpty { listOf(session.media) }
        return RestorableSession(
            session = session,
            positionMs =
                resolveRestorablePosition(
                    currentPositionMs = player.currentPosition,
                    currentDurationMs = playback.durationMs,
                    previousPositionMs = previous?.positionMs,
                ),
            durationMs =
                resolveRestorableDuration(
                    currentDurationMs = playback.durationMs,
                    previousDurationMs = previous?.durationMs,
                ),
            playbackSpeed = player.playbackParameters.speed,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            lastSurface = playerSurface.asLastSurface(),
        )
    }

    fun persistSession(session: ActiveSession) {
        val saved = currentRestorableSession(session)
        lastSession = saved
        coroutineScope.launch { sessionStore.saveLastSession(saved) }
    }

    fun startTonight() {
        val plan = tonightStateHolder.state
        val resolvedEntries =
            plan.entries.mapNotNull { entry -> resolveTonightMedia(entry)?.let { entry to it } }
        if (resolvedEntries.isEmpty()) return
        val index = plan.currentIndex.coerceIn(resolvedEntries.indices)
        val queue = resolvedEntries.map { it.second }
        val selectedEntry = resolvedEntries[index].first
        val position =
            resolveTonightStartPosition(
                positionMs = selectedEntry.positionMs,
                durationMs = maxOf(selectedEntry.durationMs, selectedEntry.media.durationMs),
            )
        tonightStateHolder.startAt(index)
        player.setMediaItems(queue.map { it.toMediaItem() }, index, position)
        player.prepare()
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        val media = queue[index]
        val previousSession = activeSession
        val session =
            ActiveSession(
                media = media,
                subtitle =
                    previousSession
                        ?.takeIf { libraryStateHolder.state.config.sameContent(it.media, media) }
                        ?.subtitle,
                secondarySubtitle =
                    previousSession
                        ?.takeIf { libraryStateHolder.state.config.sameContent(it.media, media) }
                        ?.secondarySubtitle,
                subtitleOffsetMs = libraryStateHolder.state.config.subtitleOffsetFor(media),
            )
        activeSession = session
        draft = session.toDraft()
        playerSurface = PlayerSurface.Normal
        destination = AppDestination.Player
        persistSession(session)
        playWithNotificationPermission()
    }

    fun resumeSession(saved: RestorableSession) {
        val queue = saved.queue.ifEmpty { listOf(saved.session.media) }
        val index = saved.currentIndex.coerceIn(queue.indices)
        player.setMediaItems(queue.map { it.toMediaItem() }, index, saved.positionMs)
        player.prepare()
        player.setPlaybackSpeed(saved.playbackSpeed)
        player.shuffleModeEnabled = saved.shuffleEnabled
        player.repeatMode = saved.repeatMode
        activeSession = saved.session
        lastSession = saved.copy(mediaAvailability = MediaAvailability.Ready)
        draft = saved.session.toDraft()
        playerSurface = resolveResumeSurface(saved.lastSurface, glowCalibrationCompleted)
        destination = AppDestination.Player
        playWithNotificationPermission()
    }

    fun cancelMediaLoad() {
        mediaRequestId += 1L
        mediaLoadJob?.cancel()
        mediaLoadJob = null
    }

    fun cancelDraftSubtitleLoad(slot: TextTrackSlot) {
        draftSubtitleRequestIds = draftSubtitleRequestIds.invalidate(slot)
        when (slot) {
            TextTrackSlot.Primary -> {
                primaryDraftSubtitleLoadJob?.cancel()
                primaryDraftSubtitleLoadJob = null
            }
            TextTrackSlot.Secondary -> {
                secondaryDraftSubtitleLoadJob?.cancel()
                secondaryDraftSubtitleLoadJob = null
            }
        }
    }

    fun cancelDraftSubtitleLoads() {
        TextTrackSlot.entries.forEach(::cancelDraftSubtitleLoad)
    }

    fun startDraftSubtitleLoad(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        source: SubtitleSourceRef,
        existingTrack: TimedTextTrack? = null,
    ) {
        cancelDraftSubtitleLoad(slot)
        contentResolver.persistReadPermission(source.uri)
        val requestId = draftSubtitleRequestIds.current(slot)
        val job =
            coroutineScope.launch {
                var track = existingTrack
                try {
                    if (track == null) {
                        track =
                            libraryStateHolder.persistManualTextSource(
                                media = media,
                                slot = slot,
                                subtitle = source,
                            )
                    }
                    if (
                        !isCurrentDraftSubtitleRequest(
                            slot = slot,
                            requestId = requestId,
                            currentRequestIds = draftSubtitleRequestIds,
                            mediaUriValue = media.uri.toString(),
                            currentMediaUriValue = draft.media.source?.uri?.toString(),
                        )
                    ) {
                        return@launch
                    }
                    if (track == null) {
                        draft =
                            draft.withSubtitle(
                                slot,
                                DraftSubtitle.Failed(
                                    source = source,
                                    message = "这份字幕已被另一显示轨使用",
                                ),
                            )
                        return@launch
                    }
                    libraryStateHolder.updateTextTrackReadiness(
                        media = media,
                        trackId = track.stableId,
                        readiness = TimedTextReadiness.Unknown,
                    )
                    draft = draft.withSubtitle(slot, DraftSubtitle.Loading(source))
                    val loaded = loadSubtitleSource(contentResolver, subtitleCache, source)
                    if (
                        !isCurrentDraftSubtitleRequest(
                            slot = slot,
                            requestId = requestId,
                            currentRequestIds = draftSubtitleRequestIds,
                            mediaUriValue = media.uri.toString(),
                            currentMediaUriValue = draft.media.source?.uri?.toString(),
                        )
                    ) {
                        return@launch
                    }
                    libraryStateHolder.updateTextTrackReadiness(
                        media = media,
                        trackId = track.stableId,
                        readiness = TimedTextReadiness.Ready,
                        resolvedSource = loaded.source,
                    )
                    draft = draft.withSubtitle(slot, DraftSubtitle.Ready(loaded))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (
                        !isCurrentDraftSubtitleRequest(
                            slot = slot,
                            requestId = requestId,
                            currentRequestIds = draftSubtitleRequestIds,
                            mediaUriValue = media.uri.toString(),
                            currentMediaUriValue = draft.media.source?.uri?.toString(),
                        )
                    ) {
                        return@launch
                    }
                    val failure = subtitleFailure(error)
                    track?.let {
                        libraryStateHolder.updateTextTrackReadiness(
                            media = media,
                            trackId = it.stableId,
                            readiness = failure.readiness,
                            failureMessage = failure.message,
                        )
                    }
                    draft =
                        draft.withSubtitle(
                            slot,
                            DraftSubtitle.Failed(source, failure.message),
                        )
                }
            }
        when (slot) {
            TextTrackSlot.Primary -> primaryDraftSubtitleLoadJob = job
            TextTrackSlot.Secondary -> secondaryDraftSubtitleLoadJob = job
        }
    }

    fun restoreDraftSubtitles(media: MediaSourceRef) {
        val config = libraryStateHolder.state.config
        TextTrackSlot.entries.forEach { slot ->
            val track = config.activeTextTrack(media, slot)
            val source = track?.toSubtitleSourceRef()
            if (source == null) {
                cancelDraftSubtitleLoad(slot)
                draft = draft.withSubtitle(slot, DraftSubtitle.None)
            } else {
                startDraftSubtitleLoad(media, slot, source, track)
            }
        }
    }

    fun loadMedia(
        uri: Uri,
        target: MediaPickTarget,
    ) {
        cancelMediaLoad()
        if (target == MediaPickTarget.Draft) cancelDraftSubtitleLoads()
        val source =
            MediaSourceRef(
                uri = uri,
                displayName = contentResolver.displayName(uri),
                mimeType = contentResolver.safeMimeType(uri),
                origin = MediaOrigin.OpenDocument,
                sourceId = LibrarySourceIds.LooseFiles,
            )
        val requestId = mediaRequestId
        val relinkedFrom =
            if (target == MediaPickTarget.LastSession) lastSession?.session?.media else null
        val relinkedSession =
            if (target == MediaPickTarget.LastSession) {
                lastSession?.relinkMedia(source, MediaAvailability.Checking)
            } else {
                null
            }

        when (target) {
            MediaPickTarget.Draft ->
                draft =
                    SessionDraft(
                        media = DraftMedia.Checking(source),
                        subtitle = DraftSubtitle.None,
                    )
            MediaPickTarget.LastSession -> relinkedSession?.let { lastSession = it }
        }

        mediaLoadJob =
            coroutineScope.launch {
                try {
                    ensureMediaReadable(contentResolver, source)
                    val readySource = libraryStateHolder.inspectMedia(source)
                    if (requestId != mediaRequestId) return@launch
                    when (target) {
                        MediaPickTarget.Draft -> {
                            libraryStateHolder.rememberRecentMedia(readySource)
                            val previous =
                                activeSession?.takeIf {
                                    libraryStateHolder.state.config.sameContent(it.media, readySource)
                                }
                            val mediaChanged =
                                player.currentMediaItem?.localConfiguration?.uri != readySource.uri
                            if (mediaChanged) {
                                player.setMediaItem(readySource.toMediaItem())
                            }
                            if (mediaChanged || player.playbackState == Player.STATE_IDLE) {
                                player.prepare()
                            }
                            if (!mediaChanged && player.playbackState == Player.STATE_ENDED) {
                                player.seekTo(0L)
                            }
                            val session =
                                ActiveSession(
                                    media = readySource,
                                    subtitle = previous?.subtitle,
                                    secondarySubtitle = previous?.secondarySubtitle,
                                    subtitleOffsetMs =
                                        previous?.subtitleOffsetMs
                                            ?: libraryStateHolder.state.config.subtitleOffsetFor(
                                                readySource,
                                            ),
                                )
                            activeSession = session
                            draft = session.toDraft()
                            playerSurface = PlayerSurface.Normal
                            destination = AppDestination.Player
                            playWithNotificationPermission()
                            persistSession(session)
                        }
                        MediaPickTarget.LastSession ->
                            relinkedSession?.let { session ->
                                relinkedFrom?.let { previous ->
                                    libraryStateHolder.relinkMedia(previous, readySource)
                                }
                                val ready =
                                    session.relinkMedia(readySource, MediaAvailability.Ready)
                                lastSession = ready
                                sessionStore.saveLastSession(ready)
                                libraryStateHolder.rememberRecentMedia(readySource)
                                resumeSession(ready)
                            }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (requestId != mediaRequestId) return@launch
                    val message = mediaAccessMessage(error)
                    when (target) {
                        MediaPickTarget.Draft ->
                            draft =
                                SessionDraft(
                                    media = DraftMedia.Unavailable(source, message),
                                    subtitle = DraftSubtitle.None,
                                )
                        MediaPickTarget.LastSession ->
                            relinkedSession?.let { session ->
                                val unavailable =
                                    session.copy(
                                        mediaAvailability =
                                            MediaAvailability.Unavailable(message),
                                    )
                                lastSession = unavailable
                                sessionStore.saveLastSession(unavailable)
                            }
                    }
                }
            }
    }

    fun cancelTrackLoad(slot: TextTrackSlot) {
        when (slot) {
            TextTrackSlot.Primary -> {
                primaryTrackRequestId += 1L
                primaryTrackLoadJob?.cancel()
                primaryTrackLoadJob = null
            }
            TextTrackSlot.Secondary -> {
                secondaryTrackRequestId += 1L
                secondaryTrackLoadJob?.cancel()
                secondaryTrackLoadJob = null
            }
        }
    }

    fun updateSessionTrack(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        loaded: LoadedSubtitle?,
    ) {
        val current = activeSession ?: return
        if (current.media.uri != media.uri) return
        val updated =
            when (slot) {
                TextTrackSlot.Primary -> current.copy(subtitle = loaded)
                TextTrackSlot.Secondary -> current.copy(secondarySubtitle = loaded)
            }
        activeSession = updated
        draft = updated.toDraft()
        persistSession(updated)
    }

    fun loadSessionTrack(
        media: MediaSourceRef,
        slot: TextTrackSlot,
        track: TimedTextTrack,
    ) {
        val source = track.toSubtitleSourceRef()
        if (source == null) {
            updateSessionTrack(media, slot, null)
            return
        }
        cancelTrackLoad(slot)
        val requestId =
            when (slot) {
                TextTrackSlot.Primary -> primaryTrackRequestId
                TextTrackSlot.Secondary -> secondaryTrackRequestId
            }
        libraryStateHolder.updateTextTrackReadiness(
            media = media,
            trackId = track.stableId,
            readiness = TimedTextReadiness.Unknown,
        )
        updateSessionTrack(media, slot, null)
        val job =
            coroutineScope.launch {
                try {
                    contentResolver.persistReadPermission(source.uri)
                    val loaded = loadSubtitleSource(contentResolver, subtitleCache, source)
                    val isCurrent =
                        when (slot) {
                            TextTrackSlot.Primary -> requestId == primaryTrackRequestId
                            TextTrackSlot.Secondary -> requestId == secondaryTrackRequestId
                        }
                    if (!isCurrent || activeSession?.media?.uri != media.uri) return@launch
                    libraryStateHolder.updateTextTrackReadiness(
                        media = media,
                        trackId = track.stableId,
                        readiness = TimedTextReadiness.Ready,
                        resolvedSource = loaded.source,
                    )
                    updateSessionTrack(media, slot, loaded)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val isCurrent =
                        when (slot) {
                            TextTrackSlot.Primary -> requestId == primaryTrackRequestId
                            TextTrackSlot.Secondary -> requestId == secondaryTrackRequestId
                        }
                    if (!isCurrent) return@launch
                    val failure = subtitleFailure(error)
                    libraryStateHolder.updateTextTrackReadiness(
                        media = media,
                        trackId = track.stableId,
                        readiness = failure.readiness,
                        failureMessage = failure.message,
                    )
                    updateSessionTrack(media, slot, null)
                }
            }
        when (slot) {
            TextTrackSlot.Primary -> primaryTrackLoadJob = job
            TextTrackSlot.Secondary -> secondaryTrackLoadJob = job
        }
    }

    fun restoreAssociatedTextTracks(media: MediaSourceRef) {
        val config = libraryStateHolder.state.config
        val primaryTrack = config.activeTextTrack(media, TextTrackSlot.Primary)
        val secondaryTrack = config.activeTextTrack(media, TextTrackSlot.Secondary)
        listOf(
            TextTrackSlot.Primary to primaryTrack,
            TextTrackSlot.Secondary to secondaryTrack,
        ).forEach { (slot, track) ->
            val loaded =
                when (slot) {
                    TextTrackSlot.Primary -> activeSession?.subtitle
                    TextTrackSlot.Secondary -> activeSession?.secondarySubtitle
                }
            val source = track?.toSubtitleSourceRef()
            if (source == null) {
                cancelTrackLoad(slot)
                if (loaded != null) updateSessionTrack(media, slot, null)
                return@forEach
            }
            if (
                loaded?.source?.uri == source.uri &&
                    track.readiness == TimedTextReadiness.Ready
            ) {
                return@forEach
            }
            loadSessionTrack(media, slot, track)
        }
    }

    fun selectSessionTrack(
        slot: TextTrackSlot,
        trackId: String?,
    ) {
        val media = activeSession?.media ?: return
        cancelTrackLoad(slot)
        libraryStateHolder.selectManualTextTrack(media, slot, trackId)
        restoreAssociatedTextTracks(media)
    }

    fun restoreAutomaticSessionTrack(slot: TextTrackSlot) {
        val media = activeSession?.media ?: return
        cancelTrackLoad(slot)
        libraryStateHolder.restoreAutomaticTextTrack(media, slot)
        restoreAssociatedTextTracks(media)
    }

    fun loadSubtitle(
        uri: Uri,
        target: SubtitlePickTarget,
    ) {
        val source =
            SubtitleSourceRef(
                uri = uri,
                displayName = contentResolver.displayName(uri),
                mimeType = contentResolver.safeMimeType(uri),
            )
        val draftSlot =
            when (target) {
                SubtitlePickTarget.DraftPrimary -> TextTrackSlot.Primary
                SubtitlePickTarget.DraftSecondary -> TextTrackSlot.Secondary
                SubtitlePickTarget.PlayerPrimary,
                SubtitlePickTarget.PlayerSecondary,
                -> null
            }
        if (draftSlot == null) {
            cancelDraftSubtitleLoads()
            val session = activeSession ?: return
            val slot =
                when (target) {
                    SubtitlePickTarget.PlayerPrimary -> TextTrackSlot.Primary
                    SubtitlePickTarget.PlayerSecondary -> TextTrackSlot.Secondary
                    SubtitlePickTarget.DraftPrimary,
                    SubtitlePickTarget.DraftSecondary,
                    -> error("Draft target handled above")
                }
            val track =
                libraryStateHolder.updateManualTextSource(
                    media = session.media,
                    slot = slot,
                    subtitle = source,
                )
            if (track != null) restoreAssociatedTextTracks(session.media)
            return
        }
        val media = (draft.media as? DraftMedia.Ready)?.source ?: return
        val otherSlot =
            when (draftSlot) {
                TextTrackSlot.Primary -> TextTrackSlot.Secondary
                TextTrackSlot.Secondary -> TextTrackSlot.Primary
            }
        val otherSource =
            draft.subtitleFor(otherSlot).sourceOrNull()
                ?: libraryStateHolder.state.config
                    .activeTextTrack(media, otherSlot)
                    ?.toSubtitleSourceRef()
        if (otherSource?.uri == source.uri) {
            cancelDraftSubtitleLoad(draftSlot)
            draft =
                draft.withSubtitle(
                    draftSlot,
                    DraftSubtitle.Failed(
                        source = source,
                        message = "同一份字幕不能同时作为主字幕和副字幕",
                    ),
                )
            return
        }
        startDraftSubtitleLoad(media, draftSlot, source)
    }

    LaunchedEffect(Unit) {
        libraryStateHolder.restoreConfig()
        tonightStateHolder.restore()
        val stored =
            runCatching { sessionStore.load() }
                .getOrElse {
                    StoredAppState(
                        draft = StoredDraft(null),
                        lastSession = null,
                        displayProfile = DisplayProfile.Balanced,
                    )
                }
        displayProfile = stored.displayProfile
        glowCalibrationCompleted = stored.glowCalibrationCompleted
        glowGestureHintSeen = stored.glowGestureHintSeen

        val storedLast = stored.lastSession
        if (storedLast != null) {
            val restoredConfig = libraryStateHolder.state.config
            val restoredMedia = restoredConfig.resolveActiveMedia(storedLast.media)
            val storedTonight = tonightStateHolder.state
            val tonightQueueItems = storedTonight.entries.mapNotNull(::resolveTonightMedia)
            val storedTonightMatchesLast =
                storedTonight.current
                    ?.let(::resolveTonightMedia)
                    ?.let { current -> restoredConfig.sameContent(storedLast.media, current) }
                    ?: false
            val shouldRestoreTonight =
                storedTonight.started &&
                    tonightQueueItems.size == storedTonight.entries.size &&
                    storedTonightMatchesLast
            val restoredQueue =
                if (shouldRestoreTonight) {
                    tonightQueueItems
                } else {
                    storedLast.queue
                        .map(restoredConfig::resolveActiveMedia)
                        .ifEmpty { listOf(restoredMedia) }
                }
            val restoredIndex =
                if (shouldRestoreTonight) {
                    storedTonight.currentIndex.coerceIn(restoredQueue.indices)
                } else {
                    storedLast.currentIndex.coerceIn(restoredQueue.indices)
                }
            val restoredPosition =
                if (shouldRestoreTonight) {
                    storedTonight.current?.positionMs?.takeIf { it > 0L } ?: storedLast.positionMs
                } else {
                    storedLast.positionMs
                }
            val mediaError =
                runCatching {
                    ensureMediaReadable(contentResolver, restoredMedia)
                }.exceptionOrNull()
            val primaryTrack =
                restoredConfig.activeTextTrack(restoredMedia, TextTrackSlot.Primary)
            val primarySource = primaryTrack?.toSubtitleSourceRef()
            val secondaryTrack =
                restoredConfig.activeTextTrack(restoredMedia, TextTrackSlot.Secondary)
            val secondarySource = secondaryTrack?.toSubtitleSourceRef()
            val primaryResult =
                primarySource?.let { source ->
                    runCatching { loadSubtitleSource(contentResolver, subtitleCache, source) }
                }
            val secondaryResult =
                secondarySource?.let { source ->
                    runCatching { loadSubtitleSource(contentResolver, subtitleCache, source) }
                }
            primaryTrack?.let { track ->
                primaryResult?.fold(
                    onSuccess = { loaded ->
                        libraryStateHolder.updateTextTrackReadiness(
                            media = restoredMedia,
                            trackId = track.stableId,
                            readiness = TimedTextReadiness.Ready,
                            resolvedSource = loaded.source,
                        )
                    },
                    onFailure = { error ->
                        val failure = subtitleFailure(error)
                        libraryStateHolder.updateTextTrackReadiness(
                            media = restoredMedia,
                            trackId = track.stableId,
                            readiness = failure.readiness,
                            failureMessage = failure.message,
                        )
                    },
                )
            }
            secondaryTrack?.let { track ->
                secondaryResult?.fold(
                    onSuccess = { loaded ->
                        libraryStateHolder.updateTextTrackReadiness(
                            media = restoredMedia,
                            trackId = track.stableId,
                            readiness = TimedTextReadiness.Ready,
                            resolvedSource = loaded.source,
                        )
                    },
                    onFailure = { error ->
                        val failure = subtitleFailure(error)
                        libraryStateHolder.updateTextTrackReadiness(
                            media = restoredMedia,
                            trackId = track.stableId,
                            readiness = failure.readiness,
                            failureMessage = failure.message,
                        )
                    },
                )
            }
            val loadedSubtitle = primaryResult?.getOrNull()
            val loadedSecondarySubtitle = secondaryResult?.getOrNull()
            val restored =
                RestorableSession(
                    session =
                        ActiveSession(
                            media = restoredMedia,
                            subtitle = loadedSubtitle,
                            secondarySubtitle = loadedSecondarySubtitle,
                            subtitleOffsetMs =
                                restoredConfig.subtitleOffsetFor(restoredMedia),
                        ),
                    positionMs = restoredPosition,
                    durationMs = storedLast.durationMs,
                    playbackSpeed = storedLast.playbackSpeed,
                    queue = restoredQueue,
                    currentIndex = restoredIndex,
                    shuffleEnabled = storedLast.shuffleEnabled,
                    repeatMode = storedLast.repeatMode,
                    lastSurface = storedLast.lastSurface,
                    mediaAvailability =
                        mediaError?.let {
                            MediaAvailability.Unavailable(mediaAccessMessage(it))
                        } ?: MediaAvailability.Ready,
                )
            libraryStateHolder.updateMediaAvailability(
                restoredMedia,
                if (mediaError == null) {
                    ContentAvailability.Available
                } else {
                    ContentAvailability.Unavailable
                },
            )
            lastSession = restored
            sessionStore.saveLastSession(restored)
            if (initialSession?.let { restoredConfig.sameContent(it.media, restoredMedia) } == true) {
                if (mediaError == null) {
                    activeSession =
                        initialSession.copy(
                            subtitle = loadedSubtitle,
                            secondarySubtitle = loadedSecondarySubtitle,
                            subtitleOffsetMs =
                                restoredConfig.subtitleOffsetFor(restoredMedia),
                        )
                    player.setPlaybackSpeed(storedLast.playbackSpeed)
                    playerSurface =
                        resolveResumeSurface(storedLast.lastSurface, glowCalibrationCompleted)
                } else {
                    player.pause()
                    activeSession = null
                    destination = AppDestination.Home
                }
            }
        }

        val storedDraft = stored.draft
        if (storedDraft.media != null) {
            val storedMedia = storedDraft.media
            draft = SessionDraft(media = DraftMedia.Checking(storedMedia))
            val isReadable =
                try {
                    ensureMediaReadable(contentResolver, storedMedia)
                    true
                } catch (error: Exception) {
                    draft =
                        SessionDraft(
                            media =
                                DraftMedia.Unavailable(
                                    source = storedMedia,
                                    message = mediaAccessMessage(error),
                                ),
                        )
                    false
                }
            if (isReadable) {
                libraryStateHolder.rememberRecentMedia(storedMedia)
                draft = SessionDraft(media = DraftMedia.Ready(storedMedia))
            } else {
                libraryStateHolder.updateMediaAvailability(
                    storedMedia,
                    ContentAvailability.Unavailable,
                )
            }
            restoreDraftSubtitles(storedMedia)
        }
        restorationCompleted = true
    }

    LaunchedEffect(restorationCompleted) {
        if (restorationCompleted) {
            snapshotFlow { draft }.collect(sessionStore::saveDraft)
        }
    }
    LaunchedEffect(restorationCompleted, activeSession?.media?.uri) {
        if (restorationCompleted) {
            activeSession?.media?.let(::restoreAssociatedTextTracks)
        }
    }
    LaunchedEffect(
        restorationCompleted,
        playback.playbackState,
        activeSession?.media?.uri,
        embeddedTextRuntime.discoveryRevision,
    ) {
        if (
            !restorationCompleted ||
                playback.playbackState != Player.STATE_READY ||
                !embeddedTextRuntime.isDiscoveryComplete
        ) {
            return@LaunchedEffect
        }
        val media = activeSession?.media ?: return@LaunchedEffect
        if (embeddedTextRuntime.mediaUriValue != media.uri.toString()) return@LaunchedEffect
        libraryStateHolder.updateEmbeddedTextTracks(media, embeddedTextRuntime.candidates)
    }
    LaunchedEffect(
        activeSession?.media?.uri,
        embeddedTextRuntime.mediaUriValue,
        embeddedTextRuntime.discoveryRevision,
        selectedEmbeddedTrackId,
    ) {
        val media = activeSession?.media ?: return@LaunchedEffect
        if (embeddedTextRuntime.mediaUriValue != media.uri.toString()) return@LaunchedEffect
        player.applyEmbeddedTextTrackSelection(
            tracks = embeddedTextRuntime.tracks,
            selectedTrackId = selectedEmbeddedTrackId,
        )
    }
    LaunchedEffect(restorationCompleted, displayProfile) {
        if (restorationCompleted) {
            delay(300L)
            sessionStore.saveDisplayProfile(displayProfile)
        }
    }
    LaunchedEffect(restorationCompleted, activeSession) {
        if (!restorationCompleted) return@LaunchedEffect
        val session = activeSession ?: return@LaunchedEffect
        while (true) {
            delay(5_000L)
            persistSession(session)
            persistTonightProgress()
        }
    }
    LaunchedEffect(tonight.entries.map(TonightQueueEntry::contentId)) {
        libraryStateHolder.retainContentIds(
            tonight.entries.mapTo(linkedSetOf(), TonightQueueEntry::contentId),
        )
    }

    LaunchedEffect(restorationCompleted, playback.isPlaying) {
        if (restorationCompleted && !playback.isPlaying) {
            activeSession?.let(::persistSession)
            persistTonightProgress()
        }
    }
    LaunchedEffect(restorationCompleted, playback.speed) {
        if (restorationCompleted) activeSession?.let(::persistSession)
    }
    LaunchedEffect(restorationCompleted, playerSurface) {
        if (restorationCompleted) activeSession?.let(::persistSession)
    }

    DisposableEffect(player, restorationCompleted, activeSession) {
        val session = activeSession
        val listener =
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    val plan = tonightStateHolder.state
                    val oldPlanIndex = oldPosition.mediaItemIndex
                    if (
                        runtimeMatchesTonightOrder(plan) &&
                            oldPlanIndex in plan.entries.indices
                    ) {
                        tonightStateHolder.updateProgress(
                            index = oldPlanIndex,
                            positionMs = oldPosition.positionMs,
                            durationMs =
                                maxOf(
                                    plan.entries[oldPlanIndex].durationMs,
                                    playback.durationMs,
                                ),
                        )
                    }
                    if (
                        restorationCompleted &&
                            session != null &&
                            player.currentMediaItem?.localConfiguration?.uri == session.media.uri
                    ) {
                        persistSession(session)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val failedSession = session ?: return
                    if (!isMediaAccessErrorCode(error.errorCode)) return
                    if (
                        player.currentMediaItem?.localConfiguration?.uri !=
                            failedSession.media.uri
                    ) {
                        return
                    }
                    val message = "媒体文件不可访问，请重新选择"
                    libraryStateHolder.updateMediaAvailability(
                        failedSession.media,
                        ContentAvailability.Unavailable,
                    )
                    val unavailable =
                        currentRestorableSession(failedSession).copy(
                            mediaAvailability = MediaAvailability.Unavailable(message),
                        )
                    lastSession = unavailable
                    coroutineScope.launch { sessionStore.saveLastSession(unavailable) }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    val decoded = mediaItem?.toMediaSourceRef() ?: return
                    val planBeforeTransition = tonightStateHolder.state
                    val newPlanIndex = player.currentMediaItemIndex
                    if (
                        runtimeMatchesTonightOrder(planBeforeTransition) &&
                            newPlanIndex != planBeforeTransition.currentIndex
                    ) {
                        if (
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                        ) {
                            tonightStateHolder.completeAt(planBeforeTransition.currentIndex)
                        } else {
                            tonightStateHolder.startAt(newPlanIndex)
                        }
                        tonightStateHolder.state.entries
                            .getOrNull(newPlanIndex)
                            ?.positionMs
                            ?.takeIf { it > 0L }
                            ?.let { position -> player.seekTo(position) }
                    }
                    val media =
                        libraryState.items.firstOrNull { it.uri == decoded.uri }
                            ?: lastSession?.queue?.firstOrNull { it.uri == decoded.uri }
                            ?: decoded
                    val updated =
                        if (
                            session != null &&
                                libraryStateHolder.state.config.sameContent(session.media, media)
                        ) {
                            session.copy(media = media)
                        } else {
                            cancelTrackLoad(TextTrackSlot.Primary)
                            cancelTrackLoad(TextTrackSlot.Secondary)
                            ActiveSession(
                                media = media,
                                subtitle = null,
                                subtitleOffsetMs =
                                    libraryStateHolder.state.config.subtitleOffsetFor(media),
                            )
                        }
                    activeSession = updated
                    draft = updated.toDraft()
                    if (restorationCompleted) {
                        libraryStateHolder.rememberRecentMedia(media)
                        persistSession(updated)
                    }
                    if (updated.subtitle == null || updated.secondarySubtitle == null) {
                        restoreAssociatedTextTracks(media)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val plan = tonightStateHolder.state
                    val currentEntry = plan.entries.getOrNull(player.currentMediaItemIndex)
                    val knownDurationMs =
                        maxOf(
                            currentEntry?.durationMs ?: 0L,
                            currentEntry?.media?.durationMs ?: 0L,
                            playback.durationMs,
                            player.duration.coerceAtLeast(0L),
                        )
                    if (
                        playbackState == Player.STATE_ENDED &&
                            isTonightRuntimeActive(plan) &&
                            player.currentMediaItemIndex == player.mediaItemCount - 1 &&
                            isConfirmedTonightEnd(player.currentPosition, knownDurationMs)
                    ) {
                        persistTonightProgress()
                        tonightStateHolder.completeAt(plan.currentIndex)
                    }
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner, restorationCompleted, activeSession, draft, displayProfile) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && restorationCompleted) {
                    activeSession?.let(::persistSession)
                    coroutineScope.launch {
                        sessionStore.saveDraft(draft)
                        libraryStateHolder.awaitPendingWrites()
                        tonightStateHolder.awaitPendingWrites()
                        sessionStore.saveDisplayProfile(displayProfile)
                    }
                }
            }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val mediaPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadMedia(it, mediaPickTarget) }
        }
    val subtitlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = subtitlePickTarget
            subtitlePickTarget = SubtitlePickTarget.DraftPrimary
            uri?.let { loadSubtitle(it, target) }
        }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val targetId = folderPickTargetId
            folderPickTargetId = null
            uri?.let { selectedUri ->
                if (targetId == null) {
                    libraryStateHolder.addFolder(selectedUri)
                } else {
                    libraryStateHolder.replaceFolder(targetId, selectedUri)
                }
            }
        }
    val libraryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            libraryStateHolder.scan()
        }

    val glowActive =
        destination == AppDestination.Player && playerSurface != PlayerSurface.Normal

    LaunchedEffect(glowActive, displayProfile) {
        onImmersiveModeChanged(glowActive)
        onWindowBrightnessChanged(
            if (glowActive) displayProfile.glowWindowBrightness else null,
        )
    }
    LaunchedEffect(destination, playerSurface, playback.isPlaying) {
        onPlaybackActiveChanged(
            shouldKeepPlaybackScreenOn(
                isPlayerDestination = destination == AppDestination.Player,
                surface = playerSurface,
                isPlaying = playback.isPlaying,
            ),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            mediaLoadJob?.cancel()
            primaryDraftSubtitleLoadJob?.cancel()
            secondaryDraftSubtitleLoadJob?.cancel()
            primaryTrackLoadJob?.cancel()
            secondaryTrackLoadJob?.cancel()
            libraryStateHolder.cancelScan()
            onPlaybackActiveChanged(false)
            onImmersiveModeChanged(false)
            onWindowBrightnessChanged(null)
        }
    }

    if (!restorationCompleted) {
        Box(
            modifier = modifier.fillMaxSize().background(AppBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "正在恢复会话…",
                color = AppSecondaryText,
                fontSize = 14.sp,
            )
        }
        return
    }

    BackHandler(enabled = destination == AppDestination.Player) {
        if (playerSurface != PlayerSurface.Normal) {
            playerSurface = PlayerSurface.Normal
        } else {
            homeSection = HomeSection.Home
            destination = AppDestination.Home
        }
    }
    BackHandler(
        enabled = destination == AppDestination.Home && homeSection != HomeSection.Home,
    ) {
        homeSection = HomeSection.Home
    }

    when (destination) {
        AppDestination.Home ->
            HomeScreen(
                section = homeSection,
                onSectionChanged = { homeSection = it },
                draft = draft,
                activeSession = activeSession,
                lastSession = lastSession,
                tonight = tonight,
                tonightActive = isTonightRuntimeActive(tonight),
                playback = playback,
                displayProfile = displayProfile,
                library = libraryState,
                onPickMedia = {
                    mediaPickTarget = MediaPickTarget.Draft
                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                },
                onCancelMediaOpen = {
                    cancelMediaLoad()
                    draft = activeSession?.toDraft() ?: SessionDraft()
                },
                onRequestLibraryAccess = {
                    val permissions = mediaLibraryPermissionsToRequest(context)
                    if (permissions.isEmpty()) {
                        libraryStateHolder.scan()
                    } else {
                        libraryPermissionLauncher.launch(permissions)
                    }
                },
                onRefreshLibrary = { libraryStateHolder.scan() },
                onRefreshSource = libraryStateHolder::refreshSource,
                onCancelScan = { sourceId -> libraryStateHolder.cancelScan(sourceId) },
                onAddFolder = {
                    folderPickTargetId = null
                    folderPicker.launch(null)
                },
                onRelinkFolder = { sourceId ->
                    folderPickTargetId = sourceId
                    folderPicker.launch(null)
                },
                onRemoveFolder = libraryStateHolder::removeFolder,
                onClearLooseFiles = libraryStateHolder::clearLooseFiles,
                onAcceptCandidates = libraryStateHolder::acceptCandidates,
                onIgnoreCandidates = libraryStateHolder::ignoreCandidates,
                onRemoveIncluded = libraryStateHolder::removeIncluded,
                onRestoreIgnored = libraryStateHolder::restoreIgnored,
                onCollectMedia = libraryStateHolder::collectMedia,
                onPlayLibraryItems = { items, index ->
                    val config = libraryStateHolder.state.config
                    val resolvedItems = items.map(config::resolveActiveMedia)
                    resolvedItems.getOrNull(index)?.let { media ->
                        libraryStateHolder.rememberRecentMedia(media)
                        player.setMediaItems(
                            resolvedItems.map { it.toMediaItem() },
                            index,
                            0L,
                        )
                        player.prepare()
                        val session =
                            ActiveSession(
                                media = media,
                                subtitle =
                                    activeSession
                                        ?.takeIf {
                                            libraryStateHolder.state.config.sameContent(
                                                it.media,
                                                media,
                                            )
                                        }
                                        ?.subtitle,
                                secondarySubtitle =
                                    activeSession
                                        ?.takeIf {
                                            libraryStateHolder.state.config.sameContent(
                                                it.media,
                                                media,
                                            )
                                        }
                                        ?.secondarySubtitle,
                                subtitleOffsetMs =
                                    activeSession
                                        ?.takeIf {
                                            libraryStateHolder.state.config.sameContent(
                                                it.media,
                                                media,
                                            )
                                        }
                                        ?.subtitleOffsetMs
                                        ?: libraryStateHolder.state.config.subtitleOffsetFor(media),
                            )
                        activeSession = session
                        draft = session.toDraft()
                        playerSurface = PlayerSurface.Normal
                        destination = AppDestination.Player
                        playWithNotificationPermission()
                        persistSession(session)
                        if (session.subtitle == null || session.secondarySubtitle == null) {
                            restoreAssociatedTextTracks(media)
                        }
                    }
                },
                onAddToTonight = ::addToTonight,
                onRemoveFromTonight = ::removeFromTonight,
                onMoveTonight = ::moveTonight,
                onClearTonight = ::clearTonight,
                onStartTonight = ::startTonight,
                onCreatePlaylist = libraryStateHolder::createPlaylist,
                onAddToPlaylist = libraryStateHolder::addToPlaylist,
                onRemoveFromPlaylist = libraryStateHolder::removeFromPlaylist,
                onDeletePlaylist = libraryStateHolder::deletePlaylist,
                onContinue = {
                    val currentSession = activeSession
                    val saved = lastSession
                    if (
                        saved?.mediaAvailability is MediaAvailability.Unavailable &&
                            (currentSession == null ||
                                libraryStateHolder.state.config.sameContent(
                                    currentSession.media,
                                    saved.session.media,
                                ))
                    ) {
                        mediaPickTarget = MediaPickTarget.LastSession
                        mediaPicker.launch(arrayOf("audio/*", "video/*"))
                    } else if (currentSession != null) {
                        val lastSurface =
                            saved
                                ?.takeIf {
                                    libraryStateHolder.state.config.sameContent(
                                        it.session.media,
                                        currentSession.media,
                                    )
                                }
                                ?.lastSurface
                                ?: PlayerSurface.Normal
                        playerSurface =
                            resolveResumeSurface(lastSurface, glowCalibrationCompleted)
                        destination = AppDestination.Player
                        playWithNotificationPermission()
                        persistSession(currentSession)
                    } else {
                        saved?.let { restorable ->
                            when (restorable.mediaAvailability) {
                                MediaAvailability.Ready -> resumeSession(restorable)
                                MediaAvailability.Checking -> Unit
                                is MediaAvailability.Unavailable -> {
                                    mediaPickTarget = MediaPickTarget.LastSession
                                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                                }
                            }
                        }
                    }
                },
                onPlayPause = ::togglePlayback,
                onDisplayProfileChanged = { displayProfile = it },
                modifier = modifier,
            )
        AppDestination.Player -> {
            val session = activeSession
            if (session == null) {
                destination = AppDestination.Home
            } else {
                PlayerScreen(
                    player = player,
                    session = session,
                    playback = playback,
                    surface = playerSurface,
                    displayProfile = displayProfile,
                    sleepTimer = sleepTimer,
                    showGestureHint = !glowGestureHintSeen,
                    isCollected = libraryState.config.isCollected(session.media),
                    isInTonight =
                        tonight.entries.any { it.contentId == tonightContentId(session.media) },
                    isTonightPlaying = isTonightRuntimeActive(tonight),
                    contentUnit = libraryState.config.contentUnitFor(session.media),
                    embeddedCueTrackId = embeddedTextRuntime.cueTrackId,
                    embeddedCueText = embeddedTextRuntime.cueText,
                    onPlayPause = ::togglePlayback,
                    onSurfaceChanged = { requested ->
                        playerSurface =
                            if (
                                requested == PlayerSurface.Glow &&
                                    !glowCalibrationCompleted
                            ) {
                                PlayerSurface.Calibration
                            } else {
                                requested
                            }
                    },
                    onDisplayProfileChanged = { displayProfile = it },
                    onCalibrationCompleted = {
                        glowCalibrationCompleted = true
                        playerSurface = PlayerSurface.Glow
                        coroutineScope.launch {
                            sessionStore.saveGlowOnboarding(
                                calibrationCompleted = true,
                                gestureHintSeen = glowGestureHintSeen,
                            )
                        }
                    },
                    onGestureHintSeen = {
                        if (!glowGestureHintSeen) {
                            glowGestureHintSeen = true
                            coroutineScope.launch {
                                sessionStore.saveGlowOnboarding(
                                    calibrationCompleted = glowCalibrationCompleted,
                                    gestureHintSeen = true,
                                )
                            }
                        }
                    },
                    onBackToLibrary = {
                        homeSection = HomeSection.Home
                        destination = AppDestination.Home
                    },
                    onOpenSources = {
                        homeSection = HomeSection.Sources
                        destination = AppDestination.Home
                    },
                    onRelinkMedia = {
                        activeSession?.let(::persistSession)
                        mediaPickTarget = MediaPickTarget.LastSession
                        mediaPicker.launch(arrayOf("audio/*", "video/*"))
                    },
                    onToggleCollected = {
                        if (libraryState.config.isCollected(session.media)) {
                            libraryStateHolder.uncollectMedia(session.media)
                        } else {
                            libraryStateHolder.collectMedia(session.media)
                        }
                    },
                    onAddToTonight = { addToTonight(session.media) },
                    onOpenTonight = {
                        homeSection = HomeSection.Tonight
                        destination = AppDestination.Home
                    },
                    onQueueSelect = { index ->
                        if (index in 0 until player.mediaItemCount) {
                            val position =
                                tonight
                                    .takeIf(::isTonightRuntimeActive)
                                    ?.entries
                                    ?.getOrNull(index)
                                    ?.positionMs
                                    ?: 0L
                            if (isTonightRuntimeActive(tonight)) tonightStateHolder.startAt(index)
                            player.seekTo(index, position.coerceAtLeast(0L))
                            playWithNotificationPermission()
                        }
                    },
                    onQueueMove = { fromIndex, toIndex ->
                        if (isTonightRuntimeActive(tonight)) {
                            moveTonight(fromIndex, toIndex)
                        } else if (
                            fromIndex in 0 until player.mediaItemCount &&
                                toIndex in 0 until player.mediaItemCount
                        ) {
                            player.moveMediaItem(fromIndex, toIndex)
                        }
                    },
                    onQueueRemove = { index ->
                        if (isTonightRuntimeActive(tonight)) {
                            removeFromTonight(index)
                        } else if (index in 0 until player.mediaItemCount) {
                            player.removeMediaItem(index)
                        }
                    },
                    onSelectTextTrack = ::selectSessionTrack,
                    onRestoreAutomaticTextTrack = ::restoreAutomaticSessionTrack,
                    onAddTextTrack = { slot ->
                        subtitlePickTarget =
                            when (slot) {
                                TextTrackSlot.Primary -> SubtitlePickTarget.PlayerPrimary
                                TextTrackSlot.Secondary -> SubtitlePickTarget.PlayerSecondary
                            }
                        subtitlePicker.launch(arrayOf("*/*"))
                    },
                    onSubtitleOffsetChanged = { offsetMs ->
                        val updated = session.copy(subtitleOffsetMs = offsetMs)
                        activeSession = updated
                        libraryStateHolder.updateSubtitleOffset(session.media, offsetMs)
                        persistSession(updated)
                    },
                    onSetSleepTimer = { durationMs ->
                        player.setSleepTimer(durationMs)
                        sleepTimerRevision += 1
                    },
                    onStopAfterCurrent = {
                        player.stopAfterCurrentItem()
                        sleepTimerRevision += 1
                    },
                    onStopAfterTonight = {
                        player.stopAfterTonight()
                        sleepTimerRevision += 1
                    },
                    onCancelSleepTimer = {
                        player.cancelSleepTimer()
                        sleepTimerRevision += 1
                    },
                    modifier = modifier,
                )
            }
        }
    }
}

private suspend fun loadSubtitleSource(
    contentResolver: ContentResolver,
    subtitleCache: SubtitleCache,
    source: SubtitleSourceRef,
): LoadedSubtitle =
    withContext(Dispatchers.IO) {
        val providerBytes = runCatching { contentResolver.readSubtitleBytes(source.uri) }
        val bytes =
            providerBytes.getOrElse { providerError ->
                subtitleCache.read(source.cachedFileName) ?: throw providerError
            }
        val parseName =
            if (providerBytes.isSuccess) source.displayName else source.cachedFileName ?: source.displayName
        val cues = SubtitleParser.parse(parseName, bytes)
        require(cues.isNotEmpty()) { "字幕中没有可显示的同步文字" }
        val cachedFileName =
            if (providerBytes.isSuccess) {
                runCatching { subtitleCache.store(source.uri, cues) }
                    .getOrNull()
                    ?: source.cachedFileName
            } else {
                source.cachedFileName
            }
        val cachedSource = source.copy(cachedFileName = cachedFileName)
        LoadedSubtitle(cachedSource, SubtitleTimeline(cues))
    }

private data class SubtitleFailure(
    val readiness: TimedTextReadiness,
    val message: String,
)

private fun subtitleFailure(error: Throwable): SubtitleFailure =
    when (error) {
        is SecurityException ->
            SubtitleFailure(
                TimedTextReadiness.Unreadable,
                "字幕授权已失效，请重新选择文件。",
            )
        is FileNotFoundException ->
            SubtitleFailure(
                TimedTextReadiness.Unreadable,
                "字幕文件已移动、删除或无法访问，请重新选择。",
            )
        is IOException ->
            SubtitleFailure(
                TimedTextReadiness.Unreadable,
                "无法读取字幕文件，请重新选择后重试。",
            )
        else ->
            SubtitleFailure(
                TimedTextReadiness.ParseFailed,
                error.message ?: "无法解析字幕，请选择其他文件。",
            )
    }

private suspend fun ensureMediaReadable(
    contentResolver: ContentResolver,
    source: MediaSourceRef,
) {
    withContext(Dispatchers.IO) {
        contentResolver.persistReadPermission(source.uri)
        val descriptor =
            contentResolver.openAssetFileDescriptor(source.uri, "r")
                ?: throw FileNotFoundException("Media descriptor is unavailable")
        descriptor.use { }
    }
}

private fun mediaAccessMessage(error: Throwable): String =
    when (error) {
        is SecurityException -> "播放文件授权已失效，请重新定位。"
        is FileNotFoundException -> "播放文件已移动、删除或无法访问，请重新定位。"
        is IOException -> "无法读取播放文件，请重新定位后重试。"
        else -> "无法验证播放文件，请重新定位后重试。"
    }

private fun ContentResolver.safeMimeType(uri: Uri): String? =
    runCatching { getType(uri) }.getOrNull()

private fun MediaSourceRef.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setMimeType(mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setArtist(displayArtist)
                .setAlbumTitle(album)
                .setExtras(
                    Bundle().apply {
                        putString(MediaExtraDisplayName, displayName)
                        putString(MediaExtraFolder, folder)
                        putString(MediaExtraOrigin, origin.name)
                        putString(MediaExtraSourceId, sourceId)
                        putLong(MediaExtraDuration, durationMs)
                        putLong(MediaExtraDateAdded, dateAddedSeconds)
                        putString(MediaExtraKind, kind.name)
                        putLong(MediaExtraSize, sizeBytes)
                        putInt(MediaExtraVideoWidth, videoWidth)
                        putInt(MediaExtraVideoHeight, videoHeight)
                    },
                )
                .build(),
        )
        .build()

private fun MediaItem.toMediaSourceRef(): MediaSourceRef? {
    val uri = localConfiguration?.uri ?: return null
    val title = mediaMetadata.title?.toString()?.takeIf(String::isNotBlank)
    val extras = mediaMetadata.extras
    return MediaSourceRef(
        uri = uri,
        displayName =
            extras?.getString(MediaExtraDisplayName)
                ?: title
                ?: uri.lastPathSegment
                ?: "未命名媒体",
        mimeType = localConfiguration?.mimeType,
        title = title,
        artist = mediaMetadata.artist?.toString(),
        album = mediaMetadata.albumTitle?.toString(),
        durationMs = extras?.getLong(MediaExtraDuration) ?: 0L,
        folder = extras?.getString(MediaExtraFolder),
        dateAddedSeconds = extras?.getLong(MediaExtraDateAdded) ?: 0L,
        origin =
            runCatching {
                MediaOrigin.valueOf(extras?.getString(MediaExtraOrigin).orEmpty())
            }.getOrDefault(MediaOrigin.OpenDocument),
        sourceId = extras?.getString(MediaExtraSourceId),
        kind =
            runCatching { MediaKind.valueOf(extras?.getString(MediaExtraKind).orEmpty()) }
                .getOrDefault(
                    MediaKind.infer(
                        localConfiguration?.mimeType,
                        extras?.getString(MediaExtraDisplayName) ?: title.orEmpty(),
                    ),
                ),
        sizeBytes = extras?.getLong(MediaExtraSize) ?: 0L,
        videoWidth = extras?.getInt(MediaExtraVideoWidth) ?: 0,
        videoHeight = extras?.getInt(MediaExtraVideoHeight) ?: 0,
    )
}

internal fun resolveRestorablePosition(
    currentPositionMs: Long,
    currentDurationMs: Long,
    previousPositionMs: Long?,
): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    return if (current == 0L && currentDurationMs <= 0L) {
        previousPositionMs?.coerceAtLeast(0L) ?: 0L
    } else {
        current
    }
}

internal fun resolveRestorableDuration(
    currentDurationMs: Long,
    previousDurationMs: Long?,
): Long =
    currentDurationMs.takeIf { it > 0L }
        ?: previousDurationMs?.coerceAtLeast(0L)
        ?: 0L

internal fun isMediaAccessErrorCode(errorCode: Int): Boolean =
    errorCode in
        setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        )

internal fun resolveResumeSurface(
    lastSurface: PlayerSurface,
    glowCalibrationCompleted: Boolean,
): PlayerSurface =
    when {
        lastSurface.asLastSurface() != PlayerSurface.Glow -> PlayerSurface.Normal
        glowCalibrationCompleted -> PlayerSurface.Glow
        else -> PlayerSurface.Calibration
    }

private fun MediaItem.toActiveSession(): ActiveSession? {
    val source = toMediaSourceRef() ?: return null
    return ActiveSession(
        media = source,
        subtitle = null,
    )
}

private fun ActiveSession.toDraft(): SessionDraft =
    SessionDraft(
        media = DraftMedia.Ready(media),
        subtitle = subtitle?.let(DraftSubtitle::Ready) ?: DraftSubtitle.None,
        secondarySubtitle =
            secondarySubtitle?.let(DraftSubtitle::Ready) ?: DraftSubtitle.None,
    )

private const val MediaExtraDisplayName = "vortexcue.display_name"
private const val MediaExtraFolder = "vortexcue.folder"
private const val MediaExtraOrigin = "vortexcue.origin"
private const val MediaExtraSourceId = "vortexcue.source_id"
private const val MediaExtraDuration = "vortexcue.duration_ms"
private const val MediaExtraDateAdded = "vortexcue.date_added_seconds"
private const val MediaExtraKind = "vortexcue.kind"
private const val MediaExtraSize = "vortexcue.size_bytes"
private const val MediaExtraVideoWidth = "vortexcue.video_width"
private const val MediaExtraVideoHeight = "vortexcue.video_height"
