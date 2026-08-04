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
        surface = ColorProvider(Color.Black), // OLED Absolute Black
        onSurface = ColorProvider(Color.White),
        onSurfaceVariant = ColorProvider(Color(0xFFAAAAAA)),
        primary = ColorProvider(Color(0xFFFFFFFF)),
        primaryContainer = ColorProvider(Color(0xFF121212)),
        surfaceVariant = ColorProvider(Color(0xFF121212))
    )

    val MonochromeLight = Light
    val MonochromeDark = Dark
}
