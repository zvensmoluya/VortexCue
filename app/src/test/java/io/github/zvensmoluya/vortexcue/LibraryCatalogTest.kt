package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogTest {
    @Test
    fun `opening media records recent without collecting it`() {
        val initial =
            LibraryMembershipState(
                recentUriValues = listOf("content://media/older"),
                includedUriValues = listOf("content://media/collected"),
                ignoredUriValues = listOf("content://media/ignored"),
            )

        val updated =
            initial.recordOpenedUri(
                uriValue = "content://media/opened",
                maxRecentItems = 50,
            )

        assertEquals(
            listOf("content://media/opened", "content://media/older"),
            updated.recentUriValues,
        )
        assertEquals(initial.includedUriValues, updated.includedUriValues)
        assertEquals(initial.ignoredUriValues, updated.ignoredUriValues)
    }

    @Test
    fun `opening known media moves it to the front and respects recent limit`() {
        val updated =
            LibraryMembershipState(
                recentUriValues =
                    listOf(
                        "content://media/older",
                        "content://media/opened",
                        "content://media/oldest",
                    ),
            ).recordOpenedUri(
                uriValue = "content://media/opened",
                maxRecentItems = 2,
            )

        assertEquals(
            listOf("content://media/opened", "content://media/older"),
            updated.recentUriValues,
        )
    }

    @Test
    fun `collecting media preserves recent and clears ignored decision`() {
        val initial =
            LibraryMembershipState(
                recentUriValues = listOf("content://media/opened"),
                includedUriValues = listOf("content://media/existing"),
                ignoredUriValues = listOf("content://media/opened"),
            )

        val updated = initial.collectUri("content://media/opened")

        assertEquals(initial.recentUriValues, updated.recentUriValues)
        assertEquals(
            listOf("content://media/existing", "content://media/opened"),
            updated.includedUriValues,
        )
        assertTrue(updated.ignoredUriValues.isEmpty())
    }

    @Test
    fun `uncollecting media preserves recent entry and source decision`() {
        val initial =
            LibraryMembershipState(
                recentUriValues = listOf("content://media/opened"),
                includedUriValues =
                    listOf("content://media/existing", "content://media/opened"),
                ignoredUriValues = listOf("content://media/ignored"),
            )

        val updated = initial.uncollectUris(setOf("content://media/opened"))

        assertEquals(initial.recentUriValues, updated.recentUriValues)
        assertEquals(listOf("content://media/existing"), updated.includedUriValues)
        assertEquals(initial.ignoredUriValues, updated.ignoredUriValues)
    }

    @Test
    fun `legacy playlist items remain collected`() {
        assertEquals(
            setOf("content://media/included", "content://media/playlist"),
            resolveCollectedUriValues(
                includedUriValues = listOf("content://media/included"),
                playlistUriValues = listOf("content://media/playlist"),
            ),
        )
    }

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
