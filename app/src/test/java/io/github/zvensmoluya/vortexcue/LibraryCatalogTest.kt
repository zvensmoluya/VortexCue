package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogTest {
    @Test
    fun `included decision wins over stale ignored state`() {
        assertEquals(
            LibraryDecision.Included,
            decideLibraryUri(
                uriValue = "content://media/item/1",
                includedUris = setOf("content://media/item/1"),
                ignoredUris = setOf("content://media/item/1"),
            ),
        )
    }

    @Test
    fun `ignored decision hides undecided media`() {
        assertEquals(
            LibraryDecision.Ignored,
            decideLibraryUri(
                uriValue = "content://media/item/2",
                includedUris = emptySet(),
                ignoredUris = setOf("content://media/item/2"),
            ),
        )
    }

    @Test
    fun `unknown media remains a review candidate`() {
        assertEquals(
            LibraryDecision.Candidate,
            decideLibraryUri(
                uriValue = "content://media/item/3",
                includedUris = emptySet(),
                ignoredUris = emptySet(),
            ),
        )
    }

    @Test
    fun `cold start restores cached configuration without scanning`() {
        val restored =
            restoreCachedLibraryState(
                config = LibraryConfig(),
                hasAudioAccess = false,
                videoAccess = VideoLibraryAccess.None,
            )

        assertFalse(restored.isLoading)
        assertTrue(restored.items.isEmpty())
        assertTrue(restored.candidates.isEmpty())
        assertTrue(restored.ignoredItems.isEmpty())
        assertEquals(1, restored.sources.size)
        assertFalse(restored.sources.single().isScanned)
    }
}
