package io.github.zvensmoluya.vortexcue.library

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
}
