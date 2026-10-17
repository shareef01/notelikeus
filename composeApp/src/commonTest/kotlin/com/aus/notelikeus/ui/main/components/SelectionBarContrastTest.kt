package com.aus.notelikeus.ui.main.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.colorSchemeFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "3 selected" bar's text and icons against its fill, in every theme combination. In dark
 * themes they once sat at about 2.8:1 on a mid-grey wash.
 */
class SelectionBarContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `selection bar text clears WCAG AA in every theme`() {
        val failures = ThemeBase.entries.flatMap { base ->
            AccentColor.entries.flatMap { accent ->
                listOf(false, true).flatMap { amoled ->
                    listOf(false, true).mapNotNull { systemDark ->
                        val scheme = colorSchemeFor(ThemePreference(base, amoled, accent), systemDark)
                        val ratio = contrast(scheme.onPrimaryContainer, selectionBarContainer(scheme))
                        if (ratio >= 4.5f) null else "$base/$accent/amoled=$amoled/systemDark=$systemDark = $ratio"
                    }
                }
            }
        }
        assertEquals(emptyList(), failures)
    }
}
