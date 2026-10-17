package dev.chiller.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalChillerReducedMotion = staticCompositionLocalOf { false }
val LocalChillerCompact = staticCompositionLocalOf { false }

data class ChillerPalette(
    val canvas: Color, val sidebar: Color, val surface: Color, val border: Color,
    val text: Color, val muted: Color, val faint: Color, val hover: Color,
    val selected: Color, val accent: Color, val focus: Color, val positive: Color, val negative: Color,
    val added: Color, val removed: Color,
)
val LocalChillerPalette = staticCompositionLocalOf { ChillerPalette(
    Color(0xFFFFFFFF), Color(0xFFF4F5F6), Color.White, Color(0xFFE1E4E7),
    Color(0xFF252A30), Color(0xFF606871), Color(0xFF737C86), Color(0xFFEBEDF0),
    Color(0xFFE4E7EA), Color(0xFF376D59), Color(0xFF1769AA), Color(0xFF356D57), Color(0xFFB6453A),
    Color(0xFFF0F6F0), Color(0xFFFBF0EE),
) }

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val DesktopTypography = Typography(
    displayLarge = type(40, 48, tracking = -1.5f), displayMedium = type(34, 42, tracking = -1f), displaySmall = type(30, 38, tracking = -.8f),
    headlineLarge = type(28, 36, tracking = -.8f), headlineMedium = type(24, 32, tracking = -.6f), headlineSmall = type(21, 28, tracking = -.4f),
    titleLarge = type(18, 25, FontWeight.Medium, -.3f), titleMedium = type(14, 21, FontWeight.Medium, -.1f), titleSmall = type(13, 19, FontWeight.Medium),
    bodyLarge = type(14, 23), bodyMedium = type(13, 20), bodySmall = type(12, 18),
    labelLarge = type(13, 18, FontWeight.Medium), labelMedium = type(12, 17), labelSmall = type(11, 16),
)

private val Ink = Color(0xFF252A30)
private val Muted = Color(0xFF606871)
private val Green = Color(0xFF376D59)
private val DarkInk = Color(0xFFE7E9EC)
private val DarkMuted = Color(0xFFB0B6BE)
private val DarkGreen = Color(0xFF91C3A9)

val ChillerCanvasLight = Color(0xFFFFFFFF)
val ChillerSidebarLight = Color(0xFFF4F5F6)
val ChillerElevatedLight = Color(0xFFFFFFFF)
val ChillerBorderLight = Color(0xFFE1E4E7)
val ChillerCanvasDark = Color(0xFF1D2024)
val ChillerSidebarDark = Color(0xFF181B1F)
val ChillerElevatedDark = Color(0xFF24282D)
val ChillerBorderDark = Color(0xFF3B4148)

@Composable
fun ChillerTheme(darkTheme: Boolean = isSystemInDarkTheme(), reducedMotion: Boolean = false, compact: Boolean = false, content: @Composable () -> Unit) {
    val palette = if (darkTheme) ChillerPalette(
        ChillerCanvasDark, ChillerSidebarDark, ChillerElevatedDark, ChillerBorderDark,
        DarkInk, DarkMuted, Color(0xFF9AA2AB), Color(0xFF2D3238),
        Color(0xFF343A41), DarkGreen, Color(0xFF72B7EF), Color(0xFF8BC5A3), Color(0xFFE19A91),
        Color(0xFF253026), Color(0xFF342725),
    ) else LocalChillerPalette.current
    val colors = if (darkTheme) darkColorScheme(
        primary = DarkGreen, onPrimary = Color(0xFF10251B),
        secondary = DarkGreen, onSecondary = Color(0xFF10251B),
        background = ChillerCanvasDark, onBackground = DarkInk,
        surface = ChillerElevatedDark, onSurface = DarkInk,
        surfaceVariant = ChillerSidebarDark, onSurfaceVariant = DarkMuted,
        outline = ChillerBorderDark, error = Color(0xFFE9A49A),
    ) else lightColorScheme(
        primary = Green, onPrimary = Color.White,
        secondary = Green, onSecondary = Color.White,
        background = ChillerCanvasLight, onBackground = Ink,
        surface = ChillerElevatedLight, onSurface = Ink,
        surfaceVariant = ChillerSidebarLight, onSurfaceVariant = Muted,
        outline = ChillerBorderLight, error = Color(0xFFB6453A),
    )
    CompositionLocalProvider(LocalChillerPalette provides palette, LocalChillerReducedMotion provides reducedMotion, LocalChillerCompact provides compact) {
        MaterialTheme(colorScheme = colors.copy(
            primary = palette.text, onPrimary = palette.canvas, primaryContainer = palette.selected, onPrimaryContainer = palette.text,
            secondaryContainer = palette.selected, onSecondaryContainer = palette.text,
            tertiary = palette.muted, tertiaryContainer = palette.hover, onTertiaryContainer = palette.text,
            background = palette.canvas, onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.sidebar, onSurfaceVariant = palette.muted, outline = palette.border,
            surfaceTint = Color.Transparent,
        ), typography = DesktopTypography, content = content)
    }
}
