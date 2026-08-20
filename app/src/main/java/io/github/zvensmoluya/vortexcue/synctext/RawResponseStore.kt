package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RawResponseStore {
    suspend fun write(fileName: String, bytes: ByteArray): String

    suspend fun delete(fileName: String)

    suspend fun pruneExcept(retainedFileNames: Set<String>)
}

class PrivateRawResponseStore(context: Context) : RawResponseStore {
    private val directory =
        File(File(context.noBackupFilesDir, RootDirectory), RawResponsesDirectory).apply(File::mkdirs)

    override suspend fun write(fileName: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            require(bytes.size <= MaxRawResponseBytes) { "Raw response is too large" }
            val safeName = requireSafeFileName(fileName)
            val atomic = AtomicFile(File(directory, safeName))
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                stream.fd.sync()
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            safeName
        }

    override suspend fun delete(fileName: String) {
        withContext(Dispatchers.IO) { File(directory, requireSafeFileName(fileName)).delete() }
    }

    override suspend fun pruneExcept(retainedFileNames: Set<String>) {
        withContext(Dispatchers.IO) {
            val retained = retainedFileNames.map(::requireSafeFileName).toSet()
            directory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                if (file.name !in retained) file.delete()
            }
        }
    }

    private fun requireSafeFileName(value: String): String {
        require(SafeFileName.matches(value)) { "Unsafe raw response file name" }
        return value
    }

    companion object {
        const val RootDirectory = "sync_text"
        private const val RawResponsesDirectory = "raw_responses"
        private const val MaxRawResponseBytes = 8 * 1024 * 1024
        private val SafeFileName = Regex("[A-Za-z0-9_.-]{1,180}")
    }
}
