package io.github.zvensmoluya.vortexcue.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val AppBackground = Color.Black
internal val AppSurface = Color(0xFF17151D)
internal val AppSurfaceRaised = Color(0xFF17151D)
internal val AppSurfaceSelected = Color(0xFF282432)
internal val AppHairline = Color(0xFF2A2732)
internal val AppAccent = Color(0xFFA39EAF)
internal val AppPrimaryText = Color(0xFFEEF0F4)
internal val AppSecondaryText = Color(0xFFA39EAF)
internal val AppMutedText = Color(0xFF6F6A79)
internal val AppNavigationMuted = Color(0xFF817B8B)
internal val AppBoneLogo = Color(0xFFE8E0D0)
internal val AppError = Color(0xFFFF9B9B)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VortexCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = AppTypography,
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
