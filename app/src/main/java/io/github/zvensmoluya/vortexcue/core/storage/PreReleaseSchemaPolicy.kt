package io.github.zvensmoluya.vortexcue.core.storage

internal fun isCurrentPreReleaseSchema(
    storedVersion: Int?,
    currentVersion: Int,
): Boolean = storedVersion == currentVersion
