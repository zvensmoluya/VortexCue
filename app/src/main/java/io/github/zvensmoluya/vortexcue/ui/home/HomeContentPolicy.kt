package io.github.zvensmoluya.vortexcue.ui.home

internal enum class MyContentTab {
    Collected,
    Recent,
}

internal fun defaultMyContentTab(collectedCount: Int): MyContentTab {
    require(collectedCount >= 0)
    return if (collectedCount > 0) MyContentTab.Collected else MyContentTab.Recent
}

internal fun shouldShowFirstUseHome(
    hasResumableSession: Boolean,
    collectedCount: Int,
    recentCount: Int,
    discoveryCount: Int,
    ignoredCount: Int,
    hasConfiguredSource: Boolean,
): Boolean {
    require(collectedCount >= 0)
    require(recentCount >= 0)
    require(discoveryCount >= 0)
    require(ignoredCount >= 0)
    return !hasResumableSession &&
        collectedCount == 0 &&
        recentCount == 0 &&
        discoveryCount == 0 &&
        ignoredCount == 0 &&
        !hasConfiguredSource
}

internal fun <T> orderCollectedContent(
    recentItems: List<T>,
    collectedItems: List<T>,
    stableKey: (T) -> String,
    isCollected: (T) -> Boolean,
): List<T> =
    (recentItems.filter(isCollected) + collectedItems).distinctBy(stableKey)
