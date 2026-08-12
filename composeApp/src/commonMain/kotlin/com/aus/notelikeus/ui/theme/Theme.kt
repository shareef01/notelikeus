package com.aus.notelikeus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aus.notelikeus.domain.model.AppTheme

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = Color(0xFF0F0F0F),
    onSecondary = Color(0xFFE8E8E8),
    onBackground = PrimaryDark,
    onSurface = PrimaryDark,
    onSurfaceVariant = SecondaryDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF262626),
    primaryContainer = PrimaryDark.copy(alpha = 0.15f),
    onPrimaryContainer = PrimaryDark,
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF333333),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF121212),
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
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF282828),
    inverseSurface = Color(0xFFF0F0F0),
    inverseOnSurface = Color(0xFF0A0A0A),
    inversePrimary = Color(0xFF141414),
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
    surfaceContainerLowest = Color(0xFF05070D),
    surfaceContainerLow = Color(0xFF0A0F18),
    surfaceContainer = Color(0xFF111826),
    surfaceContainerHigh = Color(0xFF18202F),
    surfaceContainerHighest = Color(0xFF1F2939),
    inverseSurface = Color(0xFFE3EAF5),
    inverseOnSurface = Color(0xFF0A0F18),
    inversePrimary = Color(0xFF1A2535),
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
    surfaceContainerLowest = Color(0xFF060A07),
    surfaceContainerLow = Color(0xFF0C120D),
    surfaceContainer = Color(0xFF141C15),
    surfaceContainerHigh = Color(0xFF1A241B),
    surfaceContainerHighest = Color(0xFF212C22),
    inverseSurface = Color(0xFFD6E8D6),
    inverseOnSurface = Color(0xFF0C120D),
    inversePrimary = Color(0xFF1A2B1A),
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
    outlineVariant = Color(0xFFE5E5E5),
    primaryContainer = PrimaryLight.copy(alpha = 0.1f),
    onPrimaryContainer = PrimaryLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    inverseSurface = Color(0xFF2E2E2E),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFD9D9D9),
)

@Composable
fun NotelikeusTheme(
    appTheme: AppTheme = AppTheme.AUTO,
    darkTheme: Boolean = isSystemInDarkTheme(),
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
