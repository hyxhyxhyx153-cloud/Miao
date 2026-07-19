package com.hyx.miao.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary           = MiaoPurple,
    onPrimary         = Color.White,
    primaryContainer  = Color(0xFFE9E6FF),
    onPrimaryContainer = Color(0xFF30276E),
    secondary         = MiaoPurple,
    onSecondary       = Color.White,
    background        = BackgroundLight,
    onBackground      = OnSurfaceLight,
    surface           = SurfaceLight,
    onSurface         = OnSurfaceLight,
    onSurfaceVariant  = OnSurfaceVariantLight,
    surfaceVariant    = Color(0xFFE9E8E4),
    surfaceContainer  = Color(0xFFF0EFEC),
    surfaceContainerHigh = Color(0xFFE7E6E2),
    outline           = Color(0xFFB7B5BA),
    outlineVariant    = Color(0xFFD4D2D5),
    errorContainer    = Color(0xFFFFE8E7),
    onErrorContainer  = Color(0xFF6F1E21),
    error             = ErrorRed,
)

private val DarkColorScheme = darkColorScheme(
    primary           = MiaoPurple80,
    onPrimary         = Color.White,
    primaryContainer  = Color(0xFF38315F),
    onPrimaryContainer = Color(0xFFE7E3FF),
    secondary         = MiaoPurple80,
    onSecondary       = Color.White,
    background        = BackgroundDark,
    onBackground      = OnSurfaceDark,
    surface           = SurfaceDark,
    onSurface         = OnSurfaceDark,
    onSurfaceVariant  = OnSurfaceVariantDark,
    surfaceVariant    = Color(0xFF303037),
    surfaceContainer  = Color(0xFF1A1A20),
    surfaceContainerHigh = Color(0xFF28282F),
    outline           = Color(0xFF6E6D76),
    outlineVariant    = Color(0xFF3E3D45),
    errorContainer    = Color(0xFF512B2E),
    onErrorContainer  = Color(0xFFFFDAD9),
    error             = ErrorRed,
)

private data class AccentPair(val light: Color, val dark: Color)

private fun accentPair(name: String) = when (name) {
    "pink" -> AccentPair(AccentPink, AccentPinkDark)
    "blue" -> AccentPair(AccentBlue, AccentBlueDark)
    "green" -> AccentPair(AccentGreen, AccentGreenDark)
    "orange" -> AccentPair(AccentOrange, AccentOrangeDark)
    "red" -> AccentPair(AccentRed, AccentRedDark)
    else -> AccentPair(MiaoPurple, MiaoPurple80)
}

private fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = (fontSize.value * scale).sp,
    lineHeight = (lineHeight.value * scale).sp,
)

data class MiaoExtraColors(
    val card: Color,
    val divider: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val accentGlow: Color,
    val elevatedShadow: Color,
)

val LocalMiaoExtraColors = staticCompositionLocalOf {
    MiaoExtraColors(
        card = CardLight,
        divider = Color(0x1A1C1C1E),
        glass = GlassLight,
        glassStrong = GlassStrongLight,
        glassBorder = Color(0xB8FFFFFF),
        accentGlow = AccentGlowLight,
        elevatedShadow = Color(0x241B1733),
    )
}

@Composable
fun MiaoTheme(
    darkMode: String = "system",
    themeColor: String = "purple",
    fontSize: String = "medium",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val accentSet = accentPair(themeColor)
    val accent = if (darkTheme) accentSet.dark else accentSet.light
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val primaryContainer = accent
        .copy(alpha = if (darkTheme) 0.25f else 0.15f)
        .compositeOver(baseScheme.surface)
    val colorScheme = baseScheme.copy(
        primary = accent,
        onPrimary = if (darkTheme) Color(0xFF211A3F) else Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = if (darkTheme) OnSurfaceDark else OnSurfaceLight,
        secondary = accent,
        onSecondary = if (darkTheme) Color(0xFF211A3F) else Color.White,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = if (darkTheme) OnSurfaceDark else OnSurfaceLight,
        surfaceTint = accent,
    )
    val textScale = when (fontSize) {
        "small" -> 0.9f
        "large" -> 1.15f
        else -> 1f
    }
    val typography = MiaoTypography.copy(
        displayLarge = MiaoTypography.displayLarge.scaledBy(textScale),
        displayMedium = MiaoTypography.displayMedium.scaledBy(textScale),
        displaySmall = MiaoTypography.displaySmall.scaledBy(textScale),
        headlineLarge = MiaoTypography.headlineLarge.scaledBy(textScale),
        headlineMedium = MiaoTypography.headlineMedium.scaledBy(textScale),
        headlineSmall = MiaoTypography.headlineSmall.scaledBy(textScale),
        titleLarge = MiaoTypography.titleLarge.scaledBy(textScale),
        titleMedium = MiaoTypography.titleMedium.scaledBy(textScale),
        titleSmall = MiaoTypography.titleSmall.scaledBy(textScale),
        bodyLarge = MiaoTypography.bodyLarge.scaledBy(textScale),
        bodyMedium = MiaoTypography.bodyMedium.scaledBy(textScale),
        bodySmall = MiaoTypography.bodySmall.scaledBy(textScale),
        labelLarge = MiaoTypography.labelLarge.scaledBy(textScale),
        labelMedium = MiaoTypography.labelMedium.scaledBy(textScale),
        labelSmall = MiaoTypography.labelSmall.scaledBy(textScale),
    )
    val extra = if (darkTheme) {
        MiaoExtraColors(
            card = CardDark,
            divider = Color.White.copy(alpha = 0.09f),
            glass = GlassDark,
            glassStrong = GlassStrongDark,
            glassBorder = Color.White.copy(alpha = 0.16f),
            accentGlow = AccentGlowDark,
            elevatedShadow = Color.Black.copy(alpha = 0.42f),
        )
    } else {
        MiaoExtraColors(
            card = CardLight,
            divider = Color(0x1A1C1C1E),
            glass = GlassLight,
            glassStrong = GlassStrongLight,
            glassBorder = Color.White.copy(alpha = 0.76f),
            accentGlow = AccentGlowLight,
            elevatedShadow = Color(0x241B1733),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalMiaoExtraColors provides extra) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = MiaoShapes,
            content = content,
        )
    }
}
