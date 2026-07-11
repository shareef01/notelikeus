package com.aus.notelikeus.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aus.notelikeus.domain.model.AppTheme

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = Color(0xFF1E1E1E),
    onPrimary = Color(0xFF0A0A0A),
    onSecondary = Color(0xFFE8E8E8),
    onBackground = Color(0xFFE8E8E8),
    onSurface = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF2A2A2A)
)

private val TrueDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    background = Color.Black, // OLED Absolute Black
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF121212),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF222222),
    outlineVariant = Color(0xFF121212)
)

private val MidnightColorScheme = darkColorScheme(
    primary = PrimaryMidnight,
    secondary = SecondaryDark,
    background = BackgroundMidnight,
    surface = SurfaceMidnight,
    surfaceVariant = Color(0xFF161C29),
    onPrimary = Color(0xFF080C14),
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAB8C9),
    outline = Color(0xFF232D3B),
    outlineVariant = Color(0xFF161C29)
)

private val ForestColorScheme = darkColorScheme(
    primary = PrimaryForest,
    secondary = SecondaryDark,
    background = BackgroundForest,
    surface = SurfaceForest,
    surfaceVariant = Color(0xFF1A211A),
    onPrimary = Color(0xFF0A0F0A),
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFA9B8A9),
    outline = Color(0xFF263326),
    outlineVariant = Color(0xFF1A211A)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    secondary = SecondaryLight,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = Color(0xFFEFEFEF),
    onPrimary = Color.White,
    onSecondary = Color(0xFF1A1A1A),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF5C5C5C),
    outline = Color(0xFFD8D8D8),
    outlineVariant = Color(0xFFE8E8E8)
)

@Composable
fun NotelikeusTheme(
    appTheme: AppTheme = AppTheme.AUTO,
    darkTheme: Boolean = isSystemInDarkTheme(),
    isTrueDarkMode: Boolean = false,
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
            if (darkTheme) {
                if (isTrueDarkMode) TrueDarkColorScheme else DarkColorScheme
            } else {
                LightColorScheme
            }
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
