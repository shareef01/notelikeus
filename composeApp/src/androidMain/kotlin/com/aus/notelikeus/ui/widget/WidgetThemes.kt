package com.aus.notelikeus.ui.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.colorSchemeFor

/**
 * The six colours the widget draws with.
 *
 * Glance composables cannot read `MaterialTheme`, so the widget has to be handed its colours
 * rather than reading them. That is a real platform constraint -- what is not forced is where the
 * values come from.
 */
data class WidgetThemeColors(
    val surface: ColorProvider,
    val onSurface: ColorProvider,
    val onSurfaceVariant: ColorProvider,
    val primary: ColorProvider,
    val primaryContainer: ColorProvider,
    val surfaceVariant: ColorProvider
)

/**
 * The widget's colours for a theme, taken from the scheme the app itself would render.
 *
 * This used to be eighteen colour literals in three hand-written palettes -- a fourth copy of the
 * app's colours, free to drift from them with nothing failing when it did (F6). Deriving them from
 * [colorSchemeFor] means the widget cannot disagree with the app about what "dark" looks like,
 * because it is asking the same function.
 *
 * It also makes the accent work. The widget had no notion of one, so Midnight and Forest users got
 * a neutral widget beside a tinted app.
 */
fun widgetColorsFor(preference: ThemePreference, systemDark: Boolean): WidgetThemeColors =
    widgetColorsFrom(colorSchemeFor(preference, systemDark))

internal fun widgetColorsFrom(scheme: ColorScheme): WidgetThemeColors = WidgetThemeColors(
    surface = ColorProvider(scheme.surface),
    onSurface = ColorProvider(scheme.onSurface),
    onSurfaceVariant = ColorProvider(scheme.onSurfaceVariant),
    primary = ColorProvider(scheme.primary),
    primaryContainer = ColorProvider(scheme.primaryContainer),
    surfaceVariant = ColorProvider(scheme.surfaceVariant)
)

/** Factory for dynamic colors to bypass strange Glance internal restrictions. */
fun dynamicColor(color: Color): ColorProvider = ColorProvider(color)
