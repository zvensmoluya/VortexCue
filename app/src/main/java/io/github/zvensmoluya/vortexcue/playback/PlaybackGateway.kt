package io.github.zvensmoluya.vortexcue.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.zvensmoluya.vortexcue.core.model.MediaKind
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.data.QueueContentRef
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

enum class PlayerMaterializationState {
    NotLoaded,
    Materializing,
    Materialized,
    ExplicitlyCleared,
}

internal fun PlayerMaterializationState.ownsRuntimePlaybackState(): Boolean =
    this == PlayerMaterializationState.Materialized ||
        this == PlayerMaterializationState.ExplicitlyCleared

class PlaybackGateway(private val controller: MediaController) {
    suspend fun replace(
        contentIds: List<String>,
        currentIndex: Int = 0,
        positionMs: Long = 0L,
    ): SessionResult =
        send(
            PlaybackCommands.Replace,
            Bundle().apply {
                putStringArrayList(PlaybackCommands.ContentIds, ArrayList(contentIds))
                putInt(PlaybackCommands.CurrentIndex, currentIndex)
                putLong(PlaybackCommands.PositionMs, positionMs)
            },
        )

    suspend fun replaceEntries(
        entries: List<QueueContentRef>,
        currentIndex: Int = 0,
        positionMs: Long = 0L,
    ): SessionResult =
        send(
            PlaybackCommands.Replace,
            Bundle().apply {
                putStringArrayList(PlaybackCommands.ContentIds, ArrayList(entries.map(QueueContentRef::contentId)))
                putStringArrayList(PlaybackCommands.OriginWorkIds, ArrayList(entries.map { it.originWorkId.orEmpty() }))
                putStringArrayList(
                    PlaybackCommands.OriginPlaylistEntryIds,
                    ArrayList(entries.map { it.originPlaylistEntryId.orEmpty() }),
                )
                putInt(PlaybackCommands.CurrentIndex, currentIndex)
                putLong(PlaybackCommands.PositionMs, positionMs)
            },
        )

    suspend fun append(contentIds: List<String>): SessionResult =
        send(
            PlaybackCommands.Append,
            Bundle().apply {
                putStringArrayList(PlaybackCommands.ContentIds, ArrayList(contentIds))
            },
        )

    suspend fun remove(contentId: String): SessionResult =
        send(
            PlaybackCommands.Remove,
            Bundle().apply { putString(PlaybackCommands.ContentId, contentId) },
        )

    suspend fun removeWork(workId: String): SessionResult =
        send(
            PlaybackCommands.RemoveWork,
            Bundle().apply { putString(PlaybackCommands.WorkId, workId) },
        )

    suspend fun move(fromIndex: Int, toIndex: Int): SessionResult =
        send(
            PlaybackCommands.Move,
            Bundle().apply {
                putInt(PlaybackCommands.FromIndex, fromIndex)
                putInt(PlaybackCommands.ToIndex, toIndex)
            },
        )

    suspend fun clear(): SessionResult = send(PlaybackCommands.Clear)

    suspend fun materialize(): SessionResult = send(PlaybackCommands.Materialize)

    suspend fun relink(
        contentId: String,
        replacement: MediaSourceRef,
    ): SessionResult =
        send(
            PlaybackCommands.Relink,
            Bundle().apply {
                putString(PlaybackCommands.ContentId, contentId)
                putMediaSource(PlaybackCommands.RelinkPrefix, replacement)
            },
        )

    suspend fun materializationState(): PlayerMaterializationState {
        val result = send(PlaybackCommands.GetMaterialization)
        return runCatching {
            PlayerMaterializationState.valueOf(
                result.extras.getString(PlaybackCommands.Materialization).orEmpty(),
            )
        }.getOrDefault(PlayerMaterializationState.NotLoaded)
    }

    private suspend fun send(
        command: SessionCommand,
        args: Bundle = Bundle.EMPTY,
    ): SessionResult = controller.sendCustomCommand(command, args).await()
}

internal object PlaybackCommands {
    private const val Prefix = "io.github.zvensmoluya.vortexcue.PLAYBACK_"
    const val ContentIds = "content_ids"
    const val ContentId = "content_id"
    const val WorkId = "work_id"
    const val OriginWorkIds = "origin_work_ids"
    const val OriginPlaylistEntryIds = "origin_playlist_entry_ids"
    const val CurrentIndex = "current_index"
    const val PositionMs = "position_ms"
    const val FromIndex = "from_index"
    const val ToIndex = "to_index"
    const val Materialization = "materialization"
    const val QueueRevision = "queue_revision"
    const val RelinkPrefix = "relink_"

    val Replace = SessionCommand(Prefix + "REPLACE", Bundle.EMPTY)
    val Append = SessionCommand(Prefix + "APPEND", Bundle.EMPTY)
    val Remove = SessionCommand(Prefix + "REMOVE", Bundle.EMPTY)
    val RemoveWork = SessionCommand(Prefix + "REMOVE_WORK", Bundle.EMPTY)
    val Move = SessionCommand(Prefix + "MOVE", Bundle.EMPTY)
    val Clear = SessionCommand(Prefix + "CLEAR", Bundle.EMPTY)
    val Materialize = SessionCommand(Prefix + "MATERIALIZE", Bundle.EMPTY)
    val Relink = SessionCommand(Prefix + "RELINK", Bundle.EMPTY)
    val GetMaterialization = SessionCommand(Prefix + "GET_MATERIALIZATION", Bundle.EMPTY)
    val All =
        listOf(
            Replace,
            Append,
            Remove,
            RemoveWork,
            Move,
            Clear,
            Materialize,
            Relink,
            GetMaterialization,
        )
}

internal fun Bundle.relinkMediaSource(): MediaSourceRef? {
    val prefix = PlaybackCommands.RelinkPrefix
    val uri = getString(prefix + "uri")?.takeIf(String::isNotBlank)?.let(Uri::parse) ?: return null
    val displayName = getString(prefix + "display_name")?.takeIf(String::isNotBlank) ?: return null
    val mimeType = getString(prefix + "mime_type")
    return MediaSourceRef(
        uri = uri,
        displayName = displayName,
        mimeType = mimeType,
        title = getString(prefix + "title"),
        artist = getString(prefix + "artist"),
        album = getString(prefix + "album"),
        durationMs = getLong(prefix + "duration_ms").coerceAtLeast(0L),
        folder = getString(prefix + "folder"),
        dateAddedSeconds = getLong(prefix + "date_added_seconds").coerceAtLeast(0L),
        origin =
            MediaOrigin.entries.firstOrNull { it.name == getString(prefix + "origin") }
                ?: MediaOrigin.OpenDocument,
        sourceId = getString(prefix + "source_id"),
        kind =
            MediaKind.entries.firstOrNull { it.name == getString(prefix + "kind") }
                ?: MediaKind.infer(mimeType, displayName),
        sizeBytes = getLong(prefix + "size_bytes").coerceAtLeast(0L),
        videoWidth = getInt(prefix + "video_width").coerceAtLeast(0),
        videoHeight = getInt(prefix + "video_height").coerceAtLeast(0),
    )
}

private fun Bundle.putMediaSource(
    prefix: String,
    source: MediaSourceRef,
) {
    putString(prefix + "uri", source.uri.toString())
    putString(prefix + "display_name", source.displayName)
    putString(prefix + "mime_type", source.mimeType)
    putString(prefix + "title", source.title)
    putString(prefix + "artist", source.artist)
    putString(prefix + "album", source.album)
    putLong(prefix + "duration_ms", source.durationMs)
    putString(prefix + "folder", source.folder)
    putLong(prefix + "date_added_seconds", source.dateAddedSeconds)
    putString(prefix + "origin", source.origin.name)
    putString(prefix + "source_id", source.sourceId)
    putString(prefix + "kind", source.kind.name)
    putLong(prefix + "size_bytes", source.sizeBytes)
    putInt(prefix + "video_width", source.videoWidth)
    putInt(prefix + "video_height", source.videoHeight)
}

private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                runCatching(::get)
                    .onSuccess(continuation::resume)
                    .onFailure(continuation::resumeWithException)
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation { cancel(true) }
    }
