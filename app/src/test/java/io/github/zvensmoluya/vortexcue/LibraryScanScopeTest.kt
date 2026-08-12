package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanScopeTest {
    @Test
    fun `full scan includes the system library and every folder`() {
        val scope = LibraryScanScope.All

        assertTrue(scope.includeSystemLibrary)
        assertTrue(scope.includesFolder("folder:first"))
        assertTrue(scope.includesFolder("folder:second"))
    }

    @Test
    fun `folder scan excludes the system library and unrelated folders`() {
        val scope = LibraryScanScope.folder("folder:selected")

        assertFalse(scope.includeSystemLibrary)
        assertTrue(scope.includesFolder("folder:selected"))
        assertFalse(scope.includesFolder("folder:other"))
    }

    @Test
    fun `system scan excludes every folder`() {
        val scope = LibraryScanScope.SystemLibrary

        assertTrue(scope.includeSystemLibrary)
        assertFalse(scope.includesFolder("folder:first"))
        assertFalse(scope.includesFolder("folder:second"))
    }

    @Test
    fun `only complete successful system coverage can remove unseen cached items`() {
        assertTrue(
            isAuthoritativeSystemScan(
                hasAudioAccess = true,
                videoAccess = VideoLibraryAccess.Full,
                audioScanSucceeded = true,
                videoScanSucceeded = true,
            ),
        )
        assertFalse(
            isAuthoritativeSystemScan(
                hasAudioAccess = true,
                videoAccess = VideoLibraryAccess.Partial,
                audioScanSucceeded = true,
                videoScanSucceeded = true,
            ),
        )
        assertFalse(
            isAuthoritativeSystemScan(
                hasAudioAccess = true,
                videoAccess = VideoLibraryAccess.Full,
                audioScanSucceeded = true,
                videoScanSucceeded = false,
            ),
        )
    }

    @Test
    fun `updating one source preserves unrelated source state`() {
        val system =
            LibrarySourceState(
                id = LibrarySourceIds.SystemLibrary,
                kind = LibrarySourceKind.SystemLibrary,
                displayName = "设备媒体库",
                itemCount = 4,
            )
        val folder =
            LibrarySourceState(
                id = "folder:selected",
                kind = LibrarySourceKind.Folder,
                displayName = "Selected",
                itemCount = 2,
            )

        val updated =
            listOf(system, folder).withSourceState(folder.copy(itemCount = 3))

        assertEquals(listOf(system, folder.copy(itemCount = 3)), updated)
    }
}
