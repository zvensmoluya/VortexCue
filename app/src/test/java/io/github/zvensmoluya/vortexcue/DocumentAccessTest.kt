package io.github.zvensmoluya.vortexcue.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentAccessTest {
    @Test
    fun `tree document id falls back to its final folder name`() {
        assertEquals(
            "VortexCue-M32D-20260811",
            readableDocumentTreeName(
                queriedName = "primary:Download/VortexCue-M32D-20260811",
                documentId = "primary:Download/VortexCue-M32D-20260811",
            ),
        )
    }

    @Test
    fun `provider display name remains preferred for opaque document ids`() {
        assertEquals(
            "Recordings",
            readableDocumentTreeName(
                queriedName = "Recordings",
                documentId = "root-42",
            ),
        )
    }
}
