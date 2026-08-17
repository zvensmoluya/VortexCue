package io.github.zvensmoluya.vortexcue

import android.content.Intent
import io.github.zvensmoluya.vortexcue.app.MediaPickTarget
import io.github.zvensmoluya.vortexcue.app.collectsIntoContent
import io.github.zvensmoluya.vortexcue.app.isSupportedExternalMediaRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMediaRequestTest {
    @Test
    fun `view accepts local audio and video MIME types`() {
        assertTrue(isSupportedExternalMediaRequest(Intent.ACTION_VIEW, "content", "audio/flac", "content://downloads/item/1"))
        assertTrue(isSupportedExternalMediaRequest(Intent.ACTION_VIEW, "file", "video/mp4", "file:///storage/emulated/0/Download/work.mp4"))
    }

    @Test
    fun `view infers media extension when provider omits MIME type`() {
        assertTrue(isSupportedExternalMediaRequest(Intent.ACTION_VIEW, "content", null, "content://provider/document/work.opus"))
    }

    @Test
    fun `request rejects unsupported actions schemes and files`() {
        assertFalse(isSupportedExternalMediaRequest(Intent.ACTION_SEND, "content", "audio/flac", "content://downloads/item/1"))
        assertFalse(isSupportedExternalMediaRequest(Intent.ACTION_VIEW, "https", "audio/mpeg", "https://example.com/work.mp3"))
        assertFalse(isSupportedExternalMediaRequest(Intent.ACTION_VIEW, "content", "text/plain", "content://downloads/readme.txt"))
    }

    @Test
    fun `external view never collects while explicit import does`() {
        assertFalse(MediaPickTarget.External.collectsIntoContent)
        assertTrue(MediaPickTarget.Import.collectsIntoContent)
        assertFalse(MediaPickTarget.Relink.collectsIntoContent)
    }
}
