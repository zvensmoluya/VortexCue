package io.github.zvensmoluya.vortexcue

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val MaxSubtitleBytes = 5 * 1024 * 1024

fun ContentResolver.persistReadPermission(uri: Uri) {
    try {
        takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Some document providers only grant access for the current process.
    }
}

fun ContentResolver.displayName(uri: Uri): String {
    val name =
        runCatching {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    return name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "未命名文件"
}

fun ContentResolver.readSubtitleBytes(uri: Uri): ByteArray {
    val input = openInputStream(uri) ?: throw IOException("无法打开所选字幕")
    return input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MaxSubtitleBytes) throw IOException("字幕文件不能超过 5 MB")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
