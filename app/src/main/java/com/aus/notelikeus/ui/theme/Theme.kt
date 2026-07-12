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
    primary = Color(0xFFC4B5FD),
    secondary = Color(0xFF9CA3AF),
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF242424),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF282828),
    primaryContainer = Color(0xFF3D2E6B),
    secondaryContainer = Color(0xFF2D2D2D),
    onPrimary = Color(0xFF0A0A0A),
    onSecondary = Color(0xFFE4E4E4),
    onBackground = Color(0xFFE4E4E4),
    onSurface = Color(0xFFE4E4E4),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF1F1F1F)
)

private val TrueDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC4B5FD),
    secondary = Color(0xFF9CA3AF),
    background = Color(0xFF000000),
    surface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    primaryContainer = Color(0xFF3D2E6B),
    secondaryContainer = Color(0xFF222222),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF1C1C1C),
    outlineVariant = Color(0xFF121212)
)

private val MidnightColorScheme = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    secondary = Color(0xFF94A3B8),
    background = Color(0xFF060B14),
    surface = Color(0xFF0F1622),
    surfaceVariant = Color(0xFF161E2E),
    surfaceContainerLow = Color(0xFF0A101A),
    surfaceContainer = Color(0xFF121A28),
    surfaceContainerHigh = Color(0xFF1C2436),
    primaryContainer = Color(0xFF2E3A5C),
    secondaryContainer = Color(0xFF1E293B),
    onPrimary = Color(0xFF060B14),
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF1E293B),
    outlineVariant = Color(0xFF161E2E)
)

private val ForestColorScheme = darkColorScheme(
    primary = Color(0xFFBBF7D0),
    secondary = Color(0xFF86A88C),
    background = Color(0xFF060E06),
    surface = Color(0xFF0F180F),
    surfaceVariant = Color(0xFF182218),
    surfaceContainerLow = Color(0xFF0A140A),
    surfaceContainer = Color(0xFF121C12),
    surfaceContainerHigh = Color(0xFF1C281C),
    primaryContainer = Color(0xFF1B4D2E),
    secondaryContainer = Color(0xFF1B2B1B),
    onPrimary = Color(0xFF060E06),
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF86A88C),
    outline = Color(0xFF1E2B1E),
    outlineVariant = Color(0xFF182218)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4F378B),
    secondary = Color(0xFF585858),
    background = Color(0xFFF8F8F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F0),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFECECEC),
    surfaceContainerHigh = Color(0xFFE5E5E5),
    primaryContainer = Color(0xFFEADDFF),
    secondaryContainer = Color(0xFFE8E8E8),
    onPrimary = Color.White,
    onSecondary = Color(0xFF1A1A1A),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF585858),
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE2E2E2)
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
            @Suppress("DEPRECATION")
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
