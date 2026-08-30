package io.github.zvensmoluya.vortexcue.library

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaScannerClassificationTest {
    @Test
    fun folderDiscoveryAcceptsAudioAndRejectsVideo() {
        assertTrue(LocalMediaScanner.isAudioDocument("episode.mp3", "audio/mpeg"))
        assertTrue(LocalMediaScanner.isAudioDocument("episode.flac", "application/octet-stream"))
        assertFalse(LocalMediaScanner.isAudioDocument("episode.mp4", "video/mp4"))
        assertFalse(LocalMediaScanner.isAudioDocument("misnamed.mp3", "video/mp4"))
    }

    @Test
    fun fullStorageIndexFindsAudioAndSidecarsButSkipsRestrictedAndroidFolders() = runBlocking {
        val root = Files.createTempDirectory("vortexcue-storage").toFile()
        try {
            val work = root.resolve("Download/RJ12345678/Work/wav").apply { mkdirs() }
            work.resolve("episode.wav").writeBytes(byteArrayOf(1))
            work.resolve("episode.wav.vtt").writeText("WEBVTT")
            root.resolve("Pictures").apply { mkdirs() }.resolve("clip.mp4").writeBytes(byteArrayOf(1))
            root.resolve("Android/data/other.app").apply { mkdirs() }
                .resolve("hidden.mp3").writeBytes(byteArrayOf(1))

            val index = LocalMediaScanner.indexSharedStorage(root)

            assertEquals(listOf("episode.wav"), index.audio.map { it.file.name })
            assertEquals(listOf("episode.wav.vtt"), index.subtitles.map { it.file.name })
            assertEquals("Download/RJ12345678/Work/wav", index.audio.single().relativeFolder)
        } finally {
            root.deleteRecursively()
        }
    }
}
