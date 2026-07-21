package com.sufficit.ai.mobiledevice

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Sufficit brand tokens — mirrors sufficit-blazor/src/wwwroot/assets/scss/_tokens.scss
 * (the source of truth; see docs/USAGE-brand-colors.md there). Brand evolution:
 * red -> graphite/silver -> orange. The app icon (logotipo-512x512.png, same file
 * production blazor.sufficit.com.br serves) keeps the original red/grey spiral —
 * only the UI accent color is the current orange.
 */
private val SufficitAmberLight = Color(0xFFEE6321)
private val SufficitAmberHoverLight = Color(0xFFD1530E)
private val SufficitAmberDark = Color(0xFFF4854A)
private val SufficitAmberHoverDark = Color(0xFFF69A66)

private val InkLight = Color(0xFF1F2226)
private val MutedLight = Color(0xFF6B7178)
private val CanvasLight = Color(0xFFF7F8FA)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceVariantLight = Color(0xFFF4F5F7)

private val InkDark = Color(0xFFF4F5F7)
private val MutedDark = Color(0xFF9AA1A9)
private val CanvasDark = Color(0xFF15171A)
private val SurfaceDark = Color(0xFF1F2226)
private val SurfaceVariantDark = Color(0xFF262A2F)

private val SufficitLightColorScheme = lightColorScheme(
    primary = SufficitAmberLight,
    onPrimary = Color.White,
    secondary = MutedLight,
    onSecondary = Color.White,
    background = CanvasLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = MutedLight,
    error = Color(0xFFD32F2F)
)

private val SufficitDarkColorScheme = darkColorScheme(
    primary = SufficitAmberDark,
    onPrimary = Color(0xFF1F2226),
    secondary = MutedDark,
    onSecondary = Color.White,
    background = CanvasDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = MutedDark,
    error = Color(0xFFEF5350)
)

@Composable
fun SufficitTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) SufficitDarkColorScheme else SufficitLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
