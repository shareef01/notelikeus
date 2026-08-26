package com.aus.notelikeus.ui.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import androidx.glance.unit.FixedColorProvider
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.colorSchemeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The widget must not be able to disagree with the app about a colour.
 *
 * It used to carry eighteen literals in three hand-written palettes -- a fourth copy of the app's
 * colours, free to drift with nothing failing when it did (F6). Worse, its resolution keyed off the
 * *system* dark mode for every branch except AMOLED, so choosing Light while the OS was dark gave a
 * dark widget beside a light app; and the accent was read and then never looked at.
 *
 * These sweep every combination the settings can produce and assert the widget's colours are the
 * app's, which is a property a shared list of constants could never give.
 */
class WidgetThemeParityTest {

    /** Every provider the widget builds is a fixed colour; this is how the test reads it back. */
    private val ColorProvider.fixed: Color get() = (this as FixedColorProvider).color

    private val everyPreference: List<ThemePreference> = buildList {
        for (base in ThemeBase.entries) {
            for (accent in AccentColor.entries) {
                for (amoled in listOf(false, true)) {
                    add(ThemePreference(base = base, amoled = amoled, accent = accent))
                }
            }
        }
    }

    @Test
    fun `the widget draws the scheme the app would render, for every setting`() {
        for (preference in everyPreference) {
            for (systemDark in listOf(false, true)) {
                val scheme = colorSchemeFor(preference, systemDark)
                val widget = widgetColorsFrom(scheme)

                assertEquals("$preference systemDark=$systemDark", scheme.surface, widget.surface.fixed)
                assertEquals("$preference systemDark=$systemDark", scheme.onSurface, widget.onSurface.fixed)
                assertEquals("$preference systemDark=$systemDark", scheme.primary, widget.primary.fixed)
                assertEquals(
                    "$preference systemDark=$systemDark",
                    scheme.onSurfaceVariant,
                    widget.onSurfaceVariant.fixed
                )
                assertEquals(
                    "$preference systemDark=$systemDark",
                    scheme.primaryContainer,
                    widget.primaryContainer.fixed
                )
                assertEquals(
                    "$preference systemDark=$systemDark",
                    scheme.surfaceVariant,
                    widget.surfaceVariant.fixed
                )
            }
        }
    }

    /**
     * The bug this replaced: every branch but AMOLED read the system's dark mode, so an explicit
     * Light or Dark was ignored unless Pure black happened to be on.
     */
    @Test
    fun `an explicit base wins over what the system is doing`() {
        val light = ThemePreference(base = ThemeBase.LIGHT)
        val dark = ThemePreference(base = ThemeBase.DARK)

        assertEquals(
            "Light must stay light on a dark OS",
            widgetColorsFor(light, systemDark = false).surface.fixed,
            widgetColorsFor(light, systemDark = true).surface.fixed
        )
        assertEquals(
            "Dark must stay dark on a light OS",
            widgetColorsFor(dark, systemDark = true).surface.fixed,
            widgetColorsFor(dark, systemDark = false).surface.fixed
        )
        assertNotEquals(
            "light and dark must not be the same widget",
            widgetColorsFor(light, systemDark = false).surface.fixed,
            widgetColorsFor(dark, systemDark = false).surface.fixed
        )
    }

    /** System is the one base that should follow the OS. */
    @Test
    fun `the system base follows the system`() {
        val system = ThemePreference(base = ThemeBase.SYSTEM)

        assertNotEquals(
            widgetColorsFor(system, systemDark = false).surface.fixed,
            widgetColorsFor(system, systemDark = true).surface.fixed
        )
    }

    /** The accent was read into the preference and then discarded. */
    @Test
    fun `the accent reaches the widget`() {
        val neutral = ThemePreference(base = ThemeBase.DARK, accent = AccentColor.NEUTRAL)
        val blue = ThemePreference(base = ThemeBase.DARK, accent = AccentColor.BLUE)
        val green = ThemePreference(base = ThemeBase.DARK, accent = AccentColor.GREEN)

        assertNotEquals(
            "a blue app must not have a neutral widget",
            widgetColorsFor(neutral, systemDark = true).surface.fixed,
            widgetColorsFor(blue, systemDark = true).surface.fixed
        )
        assertNotEquals(
            widgetColorsFor(blue, systemDark = true).surface.fixed,
            widgetColorsFor(green, systemDark = true).surface.fixed
        )
    }

    /** Pure black is a dark-theme idea, exactly as it is in the app. */
    @Test
    fun `amoled blackens a dark widget and leaves a light one alone`() {
        val dark = ThemePreference(base = ThemeBase.DARK)
        val light = ThemePreference(base = ThemeBase.LIGHT)

        assertNotEquals(
            widgetColorsFor(dark, systemDark = true).surface.fixed,
            widgetColorsFor(dark.copy(amoled = true), systemDark = true).surface.fixed
        )
        assertEquals(
            "AMOLED must mean nothing on a light base",
            widgetColorsFor(light, systemDark = false).surface.fixed,
            widgetColorsFor(light.copy(amoled = true), systemDark = false).surface.fixed
        )
    }

    /** An accented widget at true black keeps its hue rather than collapsing to plain black. */
    @Test
    fun `an accented widget stays accented at true black`() {
        val blueAmoled = ThemePreference(base = ThemeBase.DARK, accent = AccentColor.BLUE, amoled = true)
        val neutralAmoled = ThemePreference(base = ThemeBase.DARK, amoled = true)

        assertNotEquals(
            "a green or blue OLED widget must still read as green or blue",
            widgetColorsFor(neutralAmoled, systemDark = true).primary.fixed,
            widgetColorsFor(blueAmoled, systemDark = true).primary.fixed
        )
    }
}
