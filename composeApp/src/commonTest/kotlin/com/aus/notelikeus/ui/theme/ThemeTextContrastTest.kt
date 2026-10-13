package com.aus.notelikeus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * App-chrome text roles against every surface they are drawn on, in every theme combination.
 *
 * [NotePaletteContrastTest] covers text on note colours; this covers the chrome. `onSurfaceVariant`
 * is the muted role — placeholders, timestamps, secondary labels in ~20 places — and Material
 * draws it on `surfaceVariant` by definition, so it has to clear AA there as well as on the
 * background and surface.
 */
class ThemeTextContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `chrome text clears WCAG AA on every surface of every theme`() {
        val failures = mutableListOf<String>()
        for (base in ThemeBase.entries) {
            for (accent in AccentColor.entries) {
                for (amoled in listOf(false, true)) {
                    for (systemDark in listOf(false, true)) {
                        val scheme = colorSchemeFor(ThemePreference(base, amoled, accent), systemDark)
                        val surfaces = mapOf(
                            "background" to scheme.background,
                            "surface" to scheme.surface,
                            "surfaceVariant" to scheme.surfaceVariant,
                        )
                        val texts = mapOf("onSurface" to scheme.onSurface, "onSurfaceVariant" to scheme.onSurfaceVariant)
                        for ((textName, text) in texts) {
                            for ((surfaceName, surface) in surfaces) {
                                val ratio = contrast(text, surface)
                                if (ratio < 4.5f) {
                                    failures += "$base/$accent/amoled=$amoled/systemDark=$systemDark: " +
                                        "$textName on $surfaceName = ${(ratio * 100).toInt() / 100f}"
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), failures.distinct())
    }
}
