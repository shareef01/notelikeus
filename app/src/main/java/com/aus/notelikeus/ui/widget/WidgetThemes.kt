package com.aus.notelikeus.ui.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider

data class WidgetThemeColors(
    val surface: ColorProvider,
    val onSurface: ColorProvider,
    val onSurfaceVariant: ColorProvider,
    val primary: ColorProvider,
    val primaryContainer: ColorProvider,
    val surfaceVariant: ColorProvider
)

/**
 * Widget Theme Overhaul
 * Synchronized with "Elite" Architecture Standards.
 */
object WidgetThemes {
    val Light = WidgetThemeColors(
        surface = ColorProvider(Color(0xFFF7F7F7)),
        onSurface = ColorProvider(Color(0xFF000000)),
        onSurfaceVariant = ColorProvider(Color(0xFF5C5C5C)),
        primary = ColorProvider(Color(0xFF000000)),
        primaryContainer = ColorProvider(Color(0xFFEEEEEE)),
        surfaceVariant = ColorProvider(Color(0xFFFFFFFF))
    )

    val Dark = WidgetThemeColors(
        surface = ColorProvider(Color(0xFF121212)),
        onSurface = ColorProvider(Color(0xFFFFFFFF)),
        onSurfaceVariant = ColorProvider(Color(0xFFAAAAAA)),
        primary = ColorProvider(Color(0xFFFFFFFF)),
        primaryContainer = ColorProvider(Color(0xFF222222)),
        surfaceVariant = ColorProvider(Color(0xFF1A1A1A))
    )

    val TrueDark = WidgetThemeColors(
        surface = ColorProvider(Color.Black),
        onSurface = ColorProvider(Color.White),
        onSurfaceVariant = ColorProvider(Color(0xFFAAAAAA)),
        primary = ColorProvider(Color(0xFFFFFFFF)),
        primaryContainer = ColorProvider(Color(0xFF121212)),
        surfaceVariant = ColorProvider(Color(0xFF121212))
    )

    val Midnight = WidgetThemeColors(
        surface = ColorProvider(Color(0xFF080C14)),
        onSurface = ColorProvider(Color.White),
        onSurfaceVariant = ColorProvider(Color(0xFFAAB8C9)),
        primary = ColorProvider(Color(0xFFD4E4FF)),
        primaryContainer = ColorProvider(Color(0xFF161C29)),
        surfaceVariant = ColorProvider(Color(0xFF0D121D))
    )

    val Forest = WidgetThemeColors(
        surface = ColorProvider(Color(0xFF0A0F0A)),
        onSurface = ColorProvider(Color.White),
        onSurfaceVariant = ColorProvider(Color(0xFFA9B8A9)),
        primary = ColorProvider(Color(0xFFD4FFD4)),
        primaryContainer = ColorProvider(Color(0xFF1A211A)),
        surfaceVariant = ColorProvider(Color(0xFF121812))
    )

    val MonochromeLight = Light
    val MonochromeDark = Dark
}
