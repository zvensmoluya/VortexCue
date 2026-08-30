package io.github.zvensmoluya.vortexcue.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    suspend fun scanFullStorage(
        root: File = Environment.getExternalStorageDirectory(),
    ): LibraryScanBatch =
        withContext(Dispatchers.IO) {
            val indexedAudio = runCatching { scanSystemAudio() }.getOrDefault(emptyList())
            val fileIndex = indexSharedStorage(root)
            val indexedPaths =
                indexedAudio.mapNotNullTo(hashSetOf()) { media ->
                    media.storageRelativePath()
                }
            val media = indexedAudio.toMutableList()

            fileIndex.audio.forEach { entry ->
                currentCoroutineContext().ensureActive()
                if (entry.relativePathKey in indexedPaths) return@forEach
                val source =
                    MediaSourceRef(
                        uri = Uri.fromFile(entry.file),
                        displayName = entry.file.name,
                        mimeType = audioMimeType(entry.file.extension),
                        folder = entry.relativeFolder,
                        dateAddedSeconds = entry.file.lastModified().coerceAtLeast(0L) / 1_000L,
                        origin = MediaOrigin.FileSystem,
                        sourceId = LibrarySourceIds.SystemLibrary,
                        kind = MediaKind.Audio,
                        sizeBytes = entry.file.length().coerceAtLeast(0L),
                    )
                media += inspect(source)
                indexedPaths += entry.relativePathKey
            }

            val subtitles =
                fileIndex.subtitles.map { entry ->
                    DiscoveredSubtitle(
                        folder = entry.relativeFolder,
                        source =
                            SubtitleSourceRef(
                                uri = Uri.fromFile(entry.file),
                                displayName = entry.file.name,
                                mimeType = subtitleMimeType(entry.file.extension),
                            ),
                    )
                }
            val suggestions = matchSubtitles(media, subtitles)
            LibraryScanBatch(
                media = media.distinctBy { it.uri.toString() },
                subtitleSuggestions = suggestions,
            )
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
                if (source.origin == MediaOrigin.FileSystem) {
                    retriever.setDataSource(requireNotNull(source.uri.path))
                } else {
                    retriever.setDataSource(context, source.uri)
                }
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
        ): List<TimedTextTrackCandidate> {
            val subtitlesByFolder = subtitles.groupBy { it.folder.folderKey() }
            return media.flatMap { item ->
                subtitlesByFolder[item.folder.folderKey()]
                    .orEmpty()
                    .asSequence()
                    .mapNotNull { subtitle ->
                        buildSidecarTextTrackCandidate(item, subtitle.source)
                    }
                    .toList()
            }
        }

        private fun audioMimeType(extension: String): String? =
            when (extension.lowercase()) {
                "aac" -> "audio/aac"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "mka" -> "audio/x-matroska"
                "mp3" -> "audio/mpeg"
                "oga", "ogg" -> "audio/ogg"
                "opus" -> "audio/opus"
                "wav" -> "audio/wav"
                else -> null
            }

        private fun subtitleMimeType(extension: String): String? =
            when (extension.lowercase()) {
                "srt" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                "ass", "ssa" -> "text/x-ssa"
                "lrc" -> "text/plain"
                else -> null
            }

        internal suspend fun indexSharedStorage(root: File): SharedStorageFileIndex {
            val pending = ArrayDeque<File>()
            val audio = mutableListOf<SharedStorageFile>()
            val subtitles = mutableListOf<SharedStorageFile>()
            if (root.isDirectory) pending += root

            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = pending.removeFirst()
                val children = runCatching { directory.listFiles()?.toList() }.getOrNull().orEmpty()
                children.forEach { file ->
                    currentCoroutineContext().ensureActive()
                    if (file.isDirectory) {
                        if (!file.isRestrictedAndroidDirectory(root)) pending += file
                    } else {
                        val extension = file.extension.lowercase()
                        val entry = file.toSharedStorageFile(root) ?: return@forEach
                        when (extension) {
                            in AudioExtensions -> audio += entry
                            in SubtitleExtensions -> subtitles += entry
                        }
                    }
                }
            }
            return SharedStorageFileIndex(audio = audio, subtitles = subtitles)
        }
    }
}

internal data class SharedStorageFileIndex(
    val audio: List<SharedStorageFile>,
    val subtitles: List<SharedStorageFile>,
)

internal data class SharedStorageFile(
    val file: File,
    val relativeFolder: String,
) {
    val relativePathKey: String =
        "$relativeFolder/${file.name}"
            .replace('\\', '/')
            .trim('/')
            .lowercase()
}

private fun File.toSharedStorageFile(root: File): SharedStorageFile? {
    val relative = relativeToOrNull(root)?.invariantSeparatorsPath ?: return null
    val folder = relative.substringBeforeLast('/', missingDelimiterValue = "")
    return SharedStorageFile(file = this, relativeFolder = folder)
}

private fun File.isRestrictedAndroidDirectory(root: File): Boolean {
    val relative = relativeToOrNull(root)?.invariantSeparatorsPath?.trim('/') ?: return true
    return relative.equals("Android/data", ignoreCase = true) ||
        relative.equals("Android/obb", ignoreCase = true)
}

private fun MediaSourceRef.storageRelativePath(): String? {
    val parent = folder?.replace('\\', '/')?.trim('/') ?: return null
    return "$parent/$displayName".lowercase()
}

private fun String?.folderKey(): String =
    this.orEmpty().replace('\\', '/').trim('/').lowercase()

private fun android.database.Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun android.database.Cursor.longOrZero(index: Int): Long =
    if (index < 0 || isNull(index)) 0L else getLong(index)

private fun String?.normalizeMetadata(): String? =
    this?.takeIf { it.isNotBlank() && it != "<unknown>" }
