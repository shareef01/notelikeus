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
    surface = Color(0xFF000000), // Enforcing absolute discipline
    surfaceVariant = Color(0xFF121212),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF222222),
    outlineVariant = Color(0xFF121212)
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    isTrueDarkMode: Boolean = false,
    useMonochromeTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        !useMonochromeTheme && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isTrueDarkMode -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> if (isTrueDarkMode) TrueDarkColorScheme else DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
