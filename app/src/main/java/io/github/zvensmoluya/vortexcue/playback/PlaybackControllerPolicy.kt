package io.github.zvensmoluya.vortexcue.playback

internal enum class ControllerAccess {
    Rejected,
    Trusted,
    OwnApp,
}

internal fun resolveControllerAccess(
    appPackageName: String,
    appUid: Int,
    controllerPackageName: String,
    controllerUid: Int,
    isPackageNameVerified: Boolean,
    isTrusted: Boolean,
): ControllerAccess {
    val isOwnApp =
        isPackageNameVerified &&
            controllerPackageName == appPackageName &&
            controllerUid == appUid
    return when {
        isOwnApp -> ControllerAccess.OwnApp
        isTrusted -> ControllerAccess.Trusted
        else -> ControllerAccess.Rejected
    }
}
