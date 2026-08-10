package io.github.zvensmoluya.vortexcue.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import io.github.zvensmoluya.vortexcue.library.EmbeddedTextTrackMetadata
import io.github.zvensmoluya.vortexcue.library.TimedTextTrackCandidate
import io.github.zvensmoluya.vortexcue.library.buildEmbeddedTextTrackCandidate

internal data class EmbeddedTextTrackRuntime(
    val candidate: TimedTextTrackCandidate,
    val trackGroup: TrackGroup,
    val trackIndex: Int,
    val isSupported: Boolean,
    val isSelected: Boolean,
)

internal data class EmbeddedTextRuntimeState(
    val mediaUriValue: String? = null,
    val tracks: List<EmbeddedTextTrackRuntime> = emptyList(),
    val cueTrackId: String? = null,
    val cueText: String? = null,
    val isDiscoveryComplete: Boolean = false,
    val discoveryRevision: Int = 0,
) {
    val candidates: List<TimedTextTrackCandidate>
        get() = tracks.map(EmbeddedTextTrackRuntime::candidate)
}

@Composable
internal fun rememberEmbeddedTextRuntimeState(player: Player): EmbeddedTextRuntimeState {
    var state by remember(player) { mutableStateOf(player.embeddedTextRuntimeState()) }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    state =
                        EmbeddedTextRuntimeState(
                            mediaUriValue = mediaItem?.localConfiguration?.uri?.toString(),
                            discoveryRevision = state.discoveryRevision + 1,
                        )
                }

                override fun onTracksChanged(tracks: Tracks) {
                    state =
                        player.embeddedTextRuntimeState(
                            isDiscoveryComplete = player.playbackState == Player.STATE_READY,
                            discoveryRevision = state.discoveryRevision + 1,
                        ).retainCueFrom(state)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        state =
                            player.embeddedTextRuntimeState(
                                isDiscoveryComplete = true,
                                discoveryRevision = state.discoveryRevision + 1,
                            ).retainCueFrom(state)
                    }
                }

                override fun onCues(cueGroup: CueGroup) {
                    val selectedTrackId =
                        player.currentEmbeddedTextTracks()
                            .singleOrNull(EmbeddedTextTrackRuntime::isSelected)
                            ?.candidate
                            ?.track
                            ?.stableId
                    state =
                        state.copy(
                            cueTrackId = selectedTrackId,
                            cueText = cueGroup.textContent(),
                        )
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    return state
}

internal fun EmbeddedTextRuntimeState.retainCueFrom(
    previous: EmbeddedTextRuntimeState,
    selectedTrackId: String? = selectedEmbeddedTrackId(),
): EmbeddedTextRuntimeState =
    if (
        mediaUriValue == previous.mediaUriValue &&
            selectedTrackId != null &&
            selectedTrackId == previous.cueTrackId
    ) {
        copy(
            cueTrackId = selectedTrackId,
            cueText = previous.cueText,
        )
    } else {
        this
    }

internal fun Player.applyEmbeddedTextTrackSelection(
    tracks: List<EmbeddedTextTrackRuntime>,
    selectedTrackId: String?,
) {
    val selected =
        tracks.firstOrNull {
            it.candidate.track.stableId == selectedTrackId && it.isSupported
        }
    val updated =
        trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setSelectTextByDefault(false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selected == null)
            .apply {
                selected?.let { runtime ->
                    setOverrideForType(
                        TrackSelectionOverride(runtime.trackGroup, runtime.trackIndex),
                    )
                }
            }
            .build()
    if (updated != trackSelectionParameters) {
        trackSelectionParameters = updated
    }
}

private fun Player.embeddedTextRuntimeState(
    isDiscoveryComplete: Boolean = playbackState == Player.STATE_READY,
    discoveryRevision: Int = 0,
): EmbeddedTextRuntimeState {
    val mediaUriValue = currentMediaItem?.localConfiguration?.uri?.toString()
    val runtimeTracks = currentEmbeddedTextTracks()
    return EmbeddedTextRuntimeState(
        mediaUriValue = mediaUriValue,
        tracks = runtimeTracks,
        isDiscoveryComplete = isDiscoveryComplete,
        discoveryRevision = discoveryRevision,
    )
}

private fun Player.currentEmbeddedTextTracks(): List<EmbeddedTextTrackRuntime> {
    val mediaUriValue = currentMediaItem?.localConfiguration?.uri?.toString() ?: return emptyList()
    return discoverEmbeddedTextTracks(mediaUriValue, currentTracks)
}

private fun EmbeddedTextRuntimeState.selectedEmbeddedTrackId(): String? =
    tracks.singleOrNull(EmbeddedTextTrackRuntime::isSelected)
        ?.candidate
        ?.track
        ?.stableId

private fun discoverEmbeddedTextTracks(
    mediaUriValue: String,
    tracks: Tracks,
): List<EmbeddedTextTrackRuntime> =
    buildList {
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            repeat(group.length) { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val supported = group.isTrackSupported(trackIndex)
                val candidate =
                    buildEmbeddedTextTrackCandidate(
                        mediaUriValue = mediaUriValue,
                        metadata = format.toEmbeddedMetadata(group, groupIndex, trackIndex, supported),
                    ) ?: return@repeat
                add(
                    EmbeddedTextTrackRuntime(
                        candidate = candidate,
                        trackGroup = group.mediaTrackGroup,
                        trackIndex = trackIndex,
                        isSupported = supported,
                        isSelected = group.isTrackSelected(trackIndex),
                    ),
                )
            }
        }
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Format.toEmbeddedMetadata(
    group: Tracks.Group,
    groupIndex: Int,
    trackIndex: Int,
    supported: Boolean,
): EmbeddedTextTrackMetadata =
    EmbeddedTextTrackMetadata(
        groupId = group.mediaTrackGroup.id,
        formatId = id,
        groupIndex = groupIndex,
        trackIndex = trackIndex,
        mimeType = sampleMimeType,
        language = language,
        label = label,
        isDefault = selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
        isForced = selectionFlags and C.SELECTION_FLAG_FORCED != 0,
        isSdh =
            roleFlags and
                (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0,
        isClosedCaptions = roleFlags and C.ROLE_FLAG_CAPTION != 0,
        isSupported = supported,
    )

private fun CueGroup.textContent(): String? =
    cues
        .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString("\n")
        .takeIf(String::isNotEmpty)
