package io.github.zvensmoluya.vortexcue.library

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryPermissionsTest {
    @Test
    fun androidThirteenRequestsAudioOnly() {
        assertEquals(
            Manifest.permission.READ_MEDIA_AUDIO,
            requiredAudioLibraryPermission(33),
        )
    }

    @Test
    fun olderAndroidUsesLegacyStoragePermission() {
        assertEquals(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            requiredAudioLibraryPermission(32),
        )
    }
}
