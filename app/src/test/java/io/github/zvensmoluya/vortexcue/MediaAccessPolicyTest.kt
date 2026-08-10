package io.github.zvensmoluya.vortexcue

import androidx.media3.common.PlaybackException
import io.github.zvensmoluya.vortexcue.app.isMediaAccessErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAccessPolicyTest {
    @Test
    fun `missing and revoked local media are repairable access failures`() {
        assertTrue(isMediaAccessErrorCode(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(isMediaAccessErrorCode(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertTrue(isMediaAccessErrorCode(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
    }

    @Test
    fun `decoder and malformed media failures do not rewrite uri availability`() {
        assertFalse(isMediaAccessErrorCode(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(isMediaAccessErrorCode(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }
}
