package io.github.zvensmoluya.vortexcue.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreReleaseSchemaPolicyTest {
    @Test
    fun `only the current pre-release schema is accepted`() {
        assertTrue(isCurrentPreReleaseSchema(storedVersion = 7, currentVersion = 7))
        assertFalse(isCurrentPreReleaseSchema(storedVersion = null, currentVersion = 7))
        assertFalse(isCurrentPreReleaseSchema(storedVersion = 6, currentVersion = 7))
        assertFalse(isCurrentPreReleaseSchema(storedVersion = 8, currentVersion = 7))
    }
}
