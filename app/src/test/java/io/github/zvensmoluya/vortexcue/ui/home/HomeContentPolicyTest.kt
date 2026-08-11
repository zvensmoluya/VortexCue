package io.github.zvensmoluya.vortexcue.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContentPolicyTest {
    @Test
    fun `first-use home only appears before any session content discovery or source`() {
        assertTrue(
            shouldShowFirstUseHome(
                hasResumableSession = false,
                collectedCount = 0,
                recentCount = 0,
                discoveryCount = 0,
                ignoredCount = 0,
                hasConfiguredSource = false,
            ),
        )
        assertFalse(
            shouldShowFirstUseHome(
                hasResumableSession = false,
                collectedCount = 0,
                recentCount = 1,
                discoveryCount = 0,
                ignoredCount = 0,
                hasConfiguredSource = false,
            ),
        )
        assertFalse(
            shouldShowFirstUseHome(
                hasResumableSession = false,
                collectedCount = 0,
                recentCount = 0,
                discoveryCount = 1,
                ignoredCount = 0,
                hasConfiguredSource = false,
            ),
        )
        assertFalse(
            shouldShowFirstUseHome(
                hasResumableSession = false,
                collectedCount = 0,
                recentCount = 0,
                discoveryCount = 0,
                ignoredCount = 1,
                hasConfiguredSource = false,
            ),
        )
        assertFalse(
            shouldShowFirstUseHome(
                hasResumableSession = false,
                collectedCount = 0,
                recentCount = 0,
                discoveryCount = 0,
                ignoredCount = 0,
                hasConfiguredSource = true,
            ),
        )
    }

    @Test
    fun `my content defaults to recent only when collection is empty`() {
        assertEquals(MyContentTab.Recent, defaultMyContentTab(collectedCount = 0))
        assertEquals(MyContentTab.Collected, defaultMyContentTab(collectedCount = 1))
    }

    @Test
    fun `home preview puts recently used collected content first without duplicates`() {
        val ordered =
            orderCollectedContent(
                recentItems = listOf("recent-only", "collected-b", "collected-a"),
                collectedItems = listOf("collected-a", "collected-b", "collected-c"),
                stableKey = { it },
                isCollected = { it.startsWith("collected-") },
            )

        assertEquals(
            listOf("collected-b", "collected-a", "collected-c"),
            ordered,
        )
    }
}
