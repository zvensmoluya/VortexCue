package io.github.zvensmoluya.vortexcue.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackControllerPolicyTest {
    @Test
    fun verifiedSameUidControllerGetsAppAccess() {
        assertEquals(
            ControllerAccess.OwnApp,
            resolveControllerAccess(
                appPackageName = "app.package",
                appUid = 1000,
                controllerPackageName = "app.package",
                controllerUid = 1000,
                isPackageNameVerified = true,
                isTrusted = true,
            ),
        )
    }

    @Test
    fun trustedExternalControllerGetsStandardAccess() {
        assertEquals(
            ControllerAccess.Trusted,
            resolveControllerAccess(
                appPackageName = "app.package",
                appUid = 1000,
                controllerPackageName = "system.controller",
                controllerUid = 2000,
                isPackageNameVerified = true,
                isTrusted = true,
            ),
        )
    }

    @Test
    fun spoofedOrUntrustedControllerIsRejected() {
        assertEquals(
            ControllerAccess.Rejected,
            resolveControllerAccess(
                appPackageName = "app.package",
                appUid = 1000,
                controllerPackageName = "app.package",
                controllerUid = 2000,
                isPackageNameVerified = false,
                isTrusted = false,
            ),
        )
    }
}
