package io.github.zvensmoluya.vortexcue

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val AppBackground = Color.Black
internal val AppSurface = Color(0xFF090909)
internal val AppSurfaceRaised = Color(0xFF141414)
internal val AppHairline = Color(0xFF262626)
internal val AppAccent = Color(0xFFFED552)
internal val AppPrimaryText = Color(0xFFECECEC)
internal val AppSecondaryText = Color(0xFF9A9A9A)
internal val AppMutedText = Color(0xFF606060)
internal val AppError = Color(0xFFFF8F86)

@Composable
fun VortexCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = AppBackground,
                surface = AppSurface,
                surfaceVariant = AppSurfaceRaised,
                primary = AppAccent,
                onBackground = AppPrimaryText,
                onSurface = AppPrimaryText,
                outline = AppHairline,
            ),
        content = content,
    )
}
