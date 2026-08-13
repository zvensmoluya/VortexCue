package io.github.zvensmoluya.vortexcue.subtitle

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 保存用户已经确认并成功解析的同步文字资产。 */
class SubtitleAssetStore(context: Context) {
    private val directory = File(context.filesDir, "subtitle_assets")
    private val legacyDirectory = File(context.filesDir, "session_subtitles")

    fun store(
        uri: Uri,
        cues: List<SubtitleCue>,
    ): String {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法建立字幕会话目录")
        }
        val fileName = "${uri.toString().sha256().take(32)}.vtt"
        val atomicFile = AtomicFile(File(directory, fileName))
        val output = atomicFile.startWrite()
        try {
            output.write(NormalizedSubtitleWriter.encode(cues))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
        return fileName
    }

    fun read(fileName: String?): ByteArray? {
        val safeName = fileName?.takeIf { File(it).name == it } ?: return null
        return sequenceOf(File(directory, safeName), File(legacyDirectory, safeName))
            .firstOrNull(File::isFile)
            ?.readBytes()
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
