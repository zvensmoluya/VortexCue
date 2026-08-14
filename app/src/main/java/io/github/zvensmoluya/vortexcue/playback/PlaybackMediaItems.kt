package io.github.zvensmoluya.vortexcue.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.zvensmoluya.vortexcue.core.model.displayArtist
import io.github.zvensmoluya.vortexcue.core.model.displayTitle
import io.github.zvensmoluya.vortexcue.data.PlaybackItem

internal fun PlaybackItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(contentId)
        .setUri(media.uri)
        .setMimeType(media.mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(media.displayTitle)
                .setArtist(media.displayArtist)
                .setAlbumTitle(media.album)
                .setExtras(
                    Bundle().apply {
                        putString(MediaItemExtras.LocatorId, locatorId)
                        putString(MediaItemExtras.DisplayName, media.displayName)
                        putString(MediaItemExtras.Folder, media.folder)
                        putString(MediaItemExtras.Origin, media.origin.name)
                        putString(MediaItemExtras.SourceId, media.sourceId)
                        putLong(MediaItemExtras.Duration, media.durationMs)
                        putLong(MediaItemExtras.DateAdded, media.dateAddedSeconds)
                        putString(MediaItemExtras.Kind, media.kind.name)
                        putLong(MediaItemExtras.Size, media.sizeBytes)
                        putInt(MediaItemExtras.VideoWidth, media.videoWidth)
                        putInt(MediaItemExtras.VideoHeight, media.videoHeight)
                    },
                ).build(),
        ).build()

internal object MediaItemExtras {
    const val LocatorId = "vortexcue.locator_id"
    const val DisplayName = "vortexcue.display_name"
    const val Folder = "vortexcue.folder"
    const val Origin = "vortexcue.origin"
    const val SourceId = "vortexcue.source_id"
    const val Duration = "vortexcue.duration_ms"
    const val DateAdded = "vortexcue.date_added_seconds"
    const val Kind = "vortexcue.kind"
    const val Size = "vortexcue.size_bytes"
    const val VideoWidth = "vortexcue.video_width"
    const val VideoHeight = "vortexcue.video_height"
}
