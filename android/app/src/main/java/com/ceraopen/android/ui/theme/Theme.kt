// SPDX-License-Identifier: GPL-3.0-or-later
// The app's visual world: warm porcelain and crema, a machine you can read at
// a glance in a dark kitchen. Material 3 carries the structure; the palette,
// the type scale and the shapes are the brand's.
//
// Every colour role is set explicitly. Leaving any of them out makes Material
// fall back to its own purple-tinted defaults, which is what made the first
// build look like a template.
package com.ceraopen.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// -- brand ------------------------------------------------------------------

/** Crema: the one hue. It goes on data and on the single primary action. */
val Crema = Color(0xFFA85A2A)
val CremaDark = Color(0xFFE0A063)

/** Espresso ink, porcelain ground. */
val Ink = Color(0xFF191411)
val Porcelain = Color(0xFFF6F1EA)
val Graphite = Color(0xFF12100E)

/** Chart bars, kept as a named token so screens do not invent their own hue. */
val Bar = Crema

// -- colour schemes ---------------------------------------------------------

private val Light = lightColorScheme(
    primary = Crema,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E4D3),
    onPrimaryContainer = Color(0xFF4A2208),
    inversePrimary = CremaDark,
    secondary = Color(0xFF5E544C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE6DC),
    onSecondaryContainer = Color(0xFF2A221C),
    tertiary = Color(0xFF2F6B4F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8EBDF),
    onTertiaryContainer = Color(0xFF0C2C1D),
    background = Porcelain,
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EAE1),
    onSurfaceVariant = Color(0xFF6B6058),
    surfaceTint = Crema,
    inverseSurface = Color(0xFF322A24),
    inverseOnSurface = Color(0xFFF6EFE7),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFD5C9BD),
    outlineVariant = Color(0xFFE9E1D8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFBF6),
    surfaceDim = Color(0xFFE8E1D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF8F3),
    surfaceContainer = Color(0xFFF9F4EE),
    surfaceContainerHigh = Color(0xFFF3ECE4),
    surfaceContainerHighest = Color(0xFFEDE5DB),
)

private val Dark = darkColorScheme(
    primary = CremaDark,
    onPrimary = Color(0xFF3A1D08),
    primaryContainer = Color(0xFF4C2C14),
    onPrimaryContainer = Color(0xFFF7DFC8),
    inversePrimary = Crema,
    secondary = Color(0xFFCBBFB3),
    onSecondary = Color(0xFF332C26),
    secondaryContainer = Color(0xFF3A322B),
    onSecondaryContainer = Color(0xFFEADFD3),
    tertiary = Color(0xFF7FC79F),
    onTertiary = Color(0xFF06301C),
    tertiaryContainer = Color(0xFF1C3B2A),
    onTertiaryContainer = Color(0xFFC4EBD4),
    background = Graphite,
    onBackground = Color(0xFFF3ECE4),
    surface = Color(0xFF1A1714),
    onSurface = Color(0xFFF3ECE4),
    surfaceVariant = Color(0xFF262220),
    onSurfaceVariant = Color(0xFFB3A79C),
    surfaceTint = CremaDark,
    inverseSurface = Color(0xFFF3ECE4),
    inverseOnSurface = Color(0xFF1A1714),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF544A42),
    outlineVariant = Color(0xFF2E2925),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A2521),
    surfaceDim = Color(0xFF0D0B0A),
    surfaceContainerLowest = Color(0xFF0D0B0A),
    surfaceContainerLow = Color(0xFF171412),
    surfaceContainer = Color(0xFF1C1916),
    surfaceContainerHigh = Color(0xFF262220),
    surfaceContainerHighest = Color(0xFF322C27),
)

/**
 * Whether the dark scheme is in force. Material has no colour role for
 * "raised above its container", and the answer differs by theme: in light a
 * raised cell is lighter, in dark it is lighter too but Material's surface
 * roles run the other way. Components that need that answer read this.
 */
val LocalCeraDark = staticCompositionLocalOf { false }

// -- type -------------------------------------------------------------------

/** Figures that line up: temperatures, cup counts, extraction times. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val AppType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-2.4).sp,
        fontFeatureSettings = "tnum",
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.2).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CeraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalCeraDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppType,
            shapes = AppShapes,
            content = content,
        )
    }
}
