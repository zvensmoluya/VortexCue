package io.github.zvensmoluya.vortexcue.app

import io.github.zvensmoluya.vortexcue.core.model.PlayerSurface
import io.github.zvensmoluya.vortexcue.ui.main.MainTab
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteTest {
    @Test
    fun glowReturnsToNormalBeforeReturningToOriginTab() {
        val fromGlow =
            resolveBackRoute(
                destination = AppDestination.Player,
                mainTab = MainTab.Playlists,
                playerOriginTab = MainTab.Queue,
                playerSurface = PlayerSurface.Glow,
            )
        assertEquals(AppRouteSnapshot(AppDestination.Player, MainTab.Playlists, PlayerSurface.Normal), fromGlow)

        val fromNormal =
            resolveBackRoute(
                destination = fromGlow.destination,
                mainTab = fromGlow.mainTab,
                playerOriginTab = MainTab.Queue,
                playerSurface = fromGlow.playerSurface,
            )
        assertEquals(AppRouteSnapshot(AppDestination.Main, MainTab.Queue, PlayerSurface.Normal), fromNormal)
    }

    @Test
    fun settingsCalibrationReturnsDirectlyToSettings() {
        val result =
            resolveBackRoute(
                destination = AppDestination.Player,
                mainTab = MainTab.Settings,
                playerOriginTab = MainTab.Settings,
                playerSurface = PlayerSurface.Calibration,
            )

        assertEquals(AppRouteSnapshot(AppDestination.Main, MainTab.Settings, PlayerSurface.Normal), result)
    }

    @Test
    fun backFromAnotherMainTabReturnsToPlaylists() {
        val result =
            resolveBackRoute(
                destination = AppDestination.Main,
                mainTab = MainTab.Config,
                playerOriginTab = MainTab.Playlists,
                playerSurface = PlayerSurface.Normal,
            )

        assertEquals(AppRouteSnapshot(AppDestination.Main, MainTab.Playlists, PlayerSurface.Normal), result)
    }
}
