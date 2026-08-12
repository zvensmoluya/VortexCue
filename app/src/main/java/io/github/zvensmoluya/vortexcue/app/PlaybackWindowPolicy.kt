package io.github.zvensmoluya.vortexcue.app

import io.github.zvensmoluya.vortexcue.core.model.PlayerSurface

/**
 * Decides when VortexCue is allowed to keep its own window awake.
 *
 * Normal playback is intentionally compatible with the user's system timeout. Glow only keeps
 * the low-light surface visible while audio is actively playing; pausing or reaching the end
 * gives the system its normal opportunity to turn the display off. Calibration is a transient
 * hands-on surface and therefore remains awake while it is visible.
 */
internal fun shouldKeepPlaybackScreenOn(
    isPlayerDestination: Boolean,
    surface: PlayerSurface,
    isPlaying: Boolean,
): Boolean =
    isPlayerDestination &&
        when (surface) {
            PlayerSurface.Calibration -> true
            PlayerSurface.Glow -> isPlaying
            PlayerSurface.Normal -> false
        }
