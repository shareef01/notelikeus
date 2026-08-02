package com.aus.notelikeus.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aus.notelikeus.domain.model.AppTheme

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = Color(0xFF121212),
    onSecondary = Color(0xFFE8E8E8),
    onBackground = PrimaryDark,
    onSurface = PrimaryDark,
    onSurfaceVariant = SecondaryDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF2A2A2A),
    primaryContainer = PrimaryDark.copy(alpha = 0.12f),
    onPrimaryContainer = PrimaryDark,
)

private val TrueDarkColorScheme = darkColorScheme(
    primary = PrimaryTrueDark,
    secondary = SecondaryTrueDark,
    background = BackgroundTrueDark,
    surface = SurfaceTrueDark,
    surfaceVariant = SurfaceVariantTrueDark,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = MutedTrueDark,
    outline = OutlineTrueDark,
    outlineVariant = Color(0xFF121212),
    primaryContainer = PrimaryTrueDark.copy(alpha = 0.12f),
    onPrimaryContainer = PrimaryTrueDark,
)

private val MidnightColorScheme = darkColorScheme(
    primary = PrimaryMidnight,
    secondary = SecondaryMidnight,
    background = BackgroundMidnight,
    surface = SurfaceMidnight,
    surfaceVariant = SurfaceVariantMidnight,
    onPrimary = BackgroundMidnight,
    onSecondary = Color.White,
    onBackground = PrimaryMidnight,
    onSurface = PrimaryMidnight,
    onSurfaceVariant = MutedMidnight,
    outline = OutlineMidnight,
    outlineVariant = SurfaceVariantMidnight,
    primaryContainer = PrimaryMidnight.copy(alpha = 0.12f),
    onPrimaryContainer = PrimaryMidnight,
)

private val ForestColorScheme = darkColorScheme(
    primary = PrimaryForest,
    secondary = SecondaryForest,
    background = BackgroundForest,
    surface = SurfaceForest,
    surfaceVariant = SurfaceVariantForest,
    onPrimary = BackgroundForest,
    onSecondary = Color.White,
    onBackground = PrimaryForest,
    onSurface = PrimaryForest,
    onSurfaceVariant = MutedForest,
    outline = OutlineForest,
    outlineVariant = SurfaceVariantForest,
    primaryContainer = PrimaryForest.copy(alpha = 0.12f),
    onPrimaryContainer = PrimaryForest,
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    secondary = SecondaryLight,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onPrimary = Color.White,
    onSecondary = Color(0xFF1A1A1A),
    onBackground = PrimaryLight,
    onSurface = PrimaryLight,
    onSurfaceVariant = MutedLight,
    outline = OutlineLight,
    outlineVariant = Color(0xFFE8E8E8),
    primaryContainer = PrimaryLight.copy(alpha = 0.08f),
    onPrimaryContainer = PrimaryLight,
)

@Composable
fun NotelikeusTheme(
    appTheme: AppTheme = AppTheme.AUTO,
    darkTheme: Boolean = isSystemInDarkTheme(),
    useMonochromeTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.LIGHT -> LightColorScheme
        AppTheme.DARK -> DarkColorScheme
        AppTheme.TRUE_DARK -> TrueDarkColorScheme
        AppTheme.MIDNIGHT -> MidnightColorScheme
        AppTheme.FOREST -> ForestColorScheme
        AppTheme.AUTO -> {
            if (darkTheme) DarkColorScheme
            else LightColorScheme
        }
    }

    val isDark = when (appTheme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK, AppTheme.TRUE_DARK, AppTheme.MIDNIGHT, AppTheme.FOREST -> true
        AppTheme.AUTO -> darkTheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
