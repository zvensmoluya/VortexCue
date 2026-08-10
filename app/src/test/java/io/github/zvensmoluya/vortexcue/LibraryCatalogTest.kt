package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertEquals
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
}
