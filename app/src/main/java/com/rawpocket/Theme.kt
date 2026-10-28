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
    val Background = Color(0xFF0E0E10)
    val Surface = Color(0xFF17171A)
    val SurfaceHigh = Color(0xFF222226)
    val Outline = Color(0xFF3A3A40)
    val TextPrimary = Color(0xFFEDEDEF)
    val TextSecondary = Color(0xFF9A9AA3)
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
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun RawPocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
