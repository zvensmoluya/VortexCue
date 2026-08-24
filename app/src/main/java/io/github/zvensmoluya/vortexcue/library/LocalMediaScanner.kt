package io.github.zvensmoluya.vortexcue.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.zvensmoluya.vortexcue.core.model.*
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class LocalMediaScanner(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun scanSystemAudio(): List<MediaSourceRef> =
        withContext(Dispatchers.IO) {
            val collection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
            val projection =
                buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.MIME_TYPE)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.DATE_ADDED)
                    add(MediaStore.Audio.Media.SIZE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        add(MediaStore.Audio.Media.DATA)
                    }
                }.toTypedArray()

            resolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.DURATION} > 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val addedIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val pathIndex =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    }

                buildList {
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = cursor.getLong(idIndex)
                        val displayName = cursor.stringOrNull(nameIndex) ?: "未命名媒体"
                        val rawPath = cursor.stringOrNull(pathIndex)
                        add(
                            MediaSourceRef(
                                uri = ContentUris.withAppendedId(collection, id),
                                displayName = displayName,
                                mimeType = cursor.stringOrNull(mimeIndex),
                                title = cursor.stringOrNull(titleIndex),
                                artist = cursor.stringOrNull(artistIndex).normalizeMetadata(),
                                album = cursor.stringOrNull(albumIndex).normalizeMetadata(),
                                durationMs = cursor.longOrZero(durationIndex),
                                folder =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        rawPath?.trimEnd('/')
                                    } else {
                                        rawPath?.let(::File)?.parentFile?.name
                                    },
                                dateAddedSeconds = cursor.longOrZero(addedIndex),
                                origin = MediaOrigin.MediaStore,
                                sourceId = LibrarySourceIds.SystemLibrary,
                                kind = MediaKind.Audio,
                                sizeBytes = cursor.longOrZero(sizeIndex),
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }

    suspend fun scanFolder(folder: LibraryFolderRef): LibraryScanBatch =
        withContext(Dispatchers.IO) {
            val rootId = DocumentsContract.getTreeDocumentId(folder.uri)
            val pending = ArrayDeque<PendingFolder>()
            pending.add(PendingFolder(rootId, folder.displayName))
            val results = mutableListOf<MediaSourceRef>()
            val subtitles = mutableListOf<DiscoveredSubtitle>()

            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val current = pending.removeFirst()
                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(folder.uri, current.documentId)
                resolver.query(childrenUri, DocumentProjection, null, null, null)?.use { cursor ->
                    val idIndex =
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex =
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex =
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modifiedIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val documentId = cursor.getString(idIndex)
                        val name = cursor.getString(nameIndex) ?: "未命名媒体"
                        val mime = cursor.getString(mimeIndex)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(PendingFolder(documentId, "${current.path}/$name"))
                        } else if (isAudioDocument(name, mime)) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(folder.uri, documentId)
                            val source =
                                MediaSourceRef(
                                    uri = uri,
                                    displayName = name,
                                    mimeType = mime,
                                    folder = current.path,
                                    dateAddedSeconds = cursor.longOrZero(modifiedIndex) / 1_000L,
                                    origin = MediaOrigin.DocumentTree,
                                    sourceId = folder.id,
                                    kind = MediaKind.infer(mime, name),
                                    sizeBytes = cursor.longOrZero(sizeIndex),
                                )
                            val inspected = inspect(source)
                            if (inspected.kind == MediaKind.Audio) results += inspected
                        } else if (isSubtitleDocument(name, mime)) {
                            subtitles +=
                                DiscoveredSubtitle(
                                    folder = current.path,
                                    source =
                                        SubtitleSourceRef(
                                            uri =
                                                DocumentsContract.buildDocumentUriUsingTree(
                                                    folder.uri,
                                                    documentId,
                                                ),
                                            displayName = name,
                                            mimeType = mime,
                                        ),
                                )
                        }
                    }
                }
            }
            LibraryScanBatch(
                media = results,
                subtitleSuggestions = matchSubtitles(results, subtitles),
            )
        }

    suspend fun inspect(source: MediaSourceRef): MediaSourceRef =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source.uri)
                source.copy(
                    title =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            ?.takeIf(String::isNotBlank)
                            ?: source.title,
                    artist =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            .normalizeMetadata()
                            ?: source.artist,
                    album =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            .normalizeMetadata()
                            ?: source.album,
                    durationMs =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?.coerceAtLeast(0L)
                            ?: source.durationMs,
                    kind =
                        if (
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                                .equals("yes", ignoreCase = true)
                        ) {
                            MediaKind.Video
                        } else {
                            source.kind
                        },
                    videoWidth =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull()
                            ?.coerceAtLeast(0)
                            ?: source.videoWidth,
                    videoHeight =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull()
                            ?.coerceAtLeast(0)
                            ?: source.videoHeight,
                )
            } catch (_: RuntimeException) {
                source
            } finally {
                retriever.release()
            }
        }

    private data class PendingFolder(
        val documentId: String,
        val path: String,
    )

    private data class DiscoveredSubtitle(
        val folder: String,
        val source: SubtitleSourceRef,
    )

    companion object {
        private val DocumentProjection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )

        private val AudioExtensions =
            setOf(
                "aac",
                "flac",
                "m4a",
                "mka",
                "mp3",
                "oga",
                "ogg",
                "opus",
                "wav",
            )

        private val SubtitleExtensions = setOf("ass", "lrc", "srt", "ssa", "vtt")

        internal fun isAudioDocument(
            name: String,
            mimeType: String?,
        ): Boolean =
            mimeType?.startsWith("video/") != true &&
                (mimeType?.startsWith("audio/") == true ||
                    name.substringAfterLast('.', "").lowercase() in AudioExtensions)

        private fun isSubtitleDocument(
            name: String,
            mimeType: String?,
        ): Boolean =
            mimeType in setOf("application/x-subrip", "text/vtt") ||
                name.substringAfterLast('.', "").lowercase() in SubtitleExtensions

        private fun matchSubtitles(
            media: List<MediaSourceRef>,
            subtitles: List<DiscoveredSubtitle>,
        ): List<TimedTextTrackCandidate> =
            media.flatMap { item ->
                subtitles
                    .asSequence()
                    .filter { it.folder == item.folder }
                    .mapNotNull { subtitle ->
                        buildSidecarTextTrackCandidate(item, subtitle.source)
                    }
                    .toList()
            }
    }
}

private fun android.database.Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun android.database.Cursor.longOrZero(index: Int): Long =
    if (index < 0 || isNull(index)) 0L else getLong(index)

private fun String?.normalizeMetadata(): String? =
    this?.takeIf { it.isNotBlank() && it != "<unknown>" }
