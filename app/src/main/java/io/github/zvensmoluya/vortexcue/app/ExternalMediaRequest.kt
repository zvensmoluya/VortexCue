package io.github.zvensmoluya.vortexcue.app

import android.content.Intent
import android.net.Uri

internal data class ExternalMediaRequest(
    val id: Long,
    val uri: Uri,
)

internal fun isSupportedExternalMediaRequest(
    action: String?,
    scheme: String?,
    mimeType: String?,
    uriValue: String?,
): Boolean {
    if (action != Intent.ACTION_VIEW) return false
    if (scheme?.lowercase() !in setOf("content", "file")) return false
    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (
        normalizedMimeType?.startsWith("audio/") == true ||
            normalizedMimeType?.startsWith("video/") == true ||
            normalizedMimeType in setOf("application/ogg", "application/x-ogg")
    ) {
        return true
    }
    val extension =
        uriValue
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.lowercase()
    return extension in SupportedExternalMediaExtensions
}

private val SupportedExternalMediaExtensions =
    setOf(
        "3gp", "aac", "flac", "m2ts", "m4a", "m4v", "mka", "mkv", "mov",
        "mp3", "mp4", "mpeg", "mpg", "oga", "ogg", "opus", "ts", "wav", "webm",
    )
