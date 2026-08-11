package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertFalse
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
}
