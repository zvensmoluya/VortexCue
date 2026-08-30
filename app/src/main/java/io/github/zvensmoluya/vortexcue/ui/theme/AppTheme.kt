package io.github.zvensmoluya.vortexcue.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val AppBackground = Color.Black
internal val AppSurface = Color(0xFF17151D)
internal val AppSurfaceRaised = Color(0xFF17151D)
internal val AppSurfaceSelected = Color(0xFF2A2732)
internal val AppHairline = Color(0xFF2A2732)
internal val AppAccent = Color(0xFFA39EAF)
internal val AppPrimaryText = Color(0xFFEEF0F4)
internal val AppSecondaryText = Color(0xFFA39EAF)
internal val AppMutedText = Color(0xFF6F6A79)
internal val AppNavigationMuted = Color(0xFF817B8B)
internal val AppBoneLogo = Color(0xFFE8E0D0)
internal val AppError = Color(0xFFFF9B9B)

@Composable
fun VortexCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = AppBackground,
                surface = AppSurface,
                surfaceVariant = AppSurfaceRaised,
                primary = AppPrimaryText,
                secondary = AppSecondaryText,
                onBackground = AppPrimaryText,
                onSurface = AppPrimaryText,
                outline = AppHairline,
            ),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(8.dp),
                large = RoundedCornerShape(8.dp),
                extraLarge = RoundedCornerShape(8.dp),
            ),
        content = content,
    )
}
