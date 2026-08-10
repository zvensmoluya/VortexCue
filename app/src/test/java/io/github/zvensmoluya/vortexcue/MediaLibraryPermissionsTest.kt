package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryPermissionsTest {
    @Test
    fun fullVideoAccessWinsSelectedAccess() {
        assertEquals(
            VideoLibraryAccess.Full,
            resolveVideoLibraryAccess(hasFullAccess = true, hasSelectedAccess = true),
        )
    }

    @Test
    fun selectedAccessIsReportedAsPartial() {
        assertEquals(
            VideoLibraryAccess.Partial,
            resolveVideoLibraryAccess(hasFullAccess = false, hasSelectedAccess = true),
        )
    }

    @Test
    fun missingVideoPermissionsAreReportedAsNone() {
        assertEquals(
            VideoLibraryAccess.None,
            resolveVideoLibraryAccess(hasFullAccess = false, hasSelectedAccess = false),
        )
    }
}
