package com.rawpocket

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Paleta neutra y oscura: los controles no deben competir con los colores de la foto.
object Palette {
    val Background = Color(0xFF0A0A0C)
    val Surface = Color(0xFF131316)
    val SurfaceHigh = Color(0xFF1D1D21)
    val Outline = Color(0xFF2C2C32)
    val TextPrimary = Color(0xFFEDEDEF)
    val TextSecondary = Color(0xFF8E8E98)
    val Accent = Color(0xFFE8B04A)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF1B1405),
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Outline,
    error = Color(0xFFFF8A80),
)

private val typography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun RawPocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
