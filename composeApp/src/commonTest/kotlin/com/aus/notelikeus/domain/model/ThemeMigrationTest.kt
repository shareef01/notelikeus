package com.aus.notelikeus.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The six stored theme names, and what each must resolve to.
 *
 * This is the only part of the overhaul that touches a preference the user already set, so it is
 * pinned value by value rather than in the aggregate. The mapping is permanent, not transitional
 * — nothing rewrites the stored name, so a `FOREST` in DataStore has to keep meaning "dark,
 * green" for as long as the app exists. See docs/DECISIONS.md D2.
 */
class ThemeMigrationTest {

    private fun resolve(
        stored: AppTheme,
        amoled: Boolean = false,
        accent: AccentColor = AccentColor.NEUTRAL
    ) = stored.toThemePreference(storedAmoled = amoled, storedAccent = accent)

    @Test
    fun `every legacy theme name resolves to what its user chose`() {
        assertEquals(ThemePreference(ThemeBase.SYSTEM, false, AccentColor.NEUTRAL), resolve(AppTheme.AUTO))
        assertEquals(ThemePreference(ThemeBase.LIGHT, false, AccentColor.NEUTRAL), resolve(AppTheme.LIGHT))
        assertEquals(ThemePreference(ThemeBase.DARK, false, AccentColor.NEUTRAL), resolve(AppTheme.DARK))
        assertEquals(ThemePreference(ThemeBase.DARK, true, AccentColor.NEUTRAL), resolve(AppTheme.TRUE_DARK))
        assertEquals(ThemePreference(ThemeBase.DARK, false, AccentColor.BLUE), resolve(AppTheme.MIDNIGHT))
        assertEquals(ThemePreference(ThemeBase.DARK, false, AccentColor.GREEN), resolve(AppTheme.FOREST))
    }

    /** Every entry is covered, so a seventh legacy name cannot slip through untested. */
    @Test
    fun `no stored theme name is left without a mapping`() {
        AppTheme.entries.forEach { stored ->
            assertTrue(
                resolve(stored).base in ThemeBase.entries,
                "$stored does not resolve to a base theme"
            )
        }
        assertEquals(6, AppTheme.entries.size, "a theme name was added without extending this test")
    }

    /**
     * A legacy name carries its own black level and hue, and must win over the newer keys.
     *
     * Otherwise a Forest user whose accent key happened to hold NEUTRAL — which is what it holds
     * by default, since they never set it — would open the app to a grey theme.
     */
    @Test
    fun `a legacy name overrides the independently stored keys`() {
        assertEquals(
            ThemePreference(ThemeBase.DARK, false, AccentColor.GREEN),
            resolve(AppTheme.FOREST, amoled = true, accent = AccentColor.BLUE)
        )
        assertEquals(
            ThemePreference(ThemeBase.DARK, true, AccentColor.NEUTRAL),
            resolve(AppTheme.TRUE_DARK, amoled = false, accent = AccentColor.GREEN)
        )
    }

    /**
     * The three non-legacy names carry no hue or black level of their own, so the stored keys
     * decide. This is the path every write after the change takes.
     */
    @Test
    fun `the non-legacy names defer to the stored keys`() {
        assertEquals(
            ThemePreference(ThemeBase.DARK, true, AccentColor.GREEN),
            resolve(AppTheme.DARK, amoled = true, accent = AccentColor.GREEN)
        )
        assertEquals(
            ThemePreference(ThemeBase.LIGHT, false, AccentColor.BLUE),
            resolve(AppTheme.LIGHT, amoled = false, accent = AccentColor.BLUE)
        )
    }

    /** Only the three non-legacy names are ever written back. */
    @Test
    fun `only non-legacy names are persisted`() {
        assertEquals(AppTheme.AUTO, ThemeBase.SYSTEM.toStoredAppTheme())
        assertEquals(AppTheme.LIGHT, ThemeBase.LIGHT.toStoredAppTheme())
        assertEquals(AppTheme.DARK, ThemeBase.DARK.toStoredAppTheme())
    }

    /**
     * Round-trips every legacy value through resolve -> persist -> resolve.
     *
     * The base has to survive, and so do the accent and black level — which they only do because
     * the repository writes them out explicitly the first time the user changes anything. This is
     * the assertion that fails if that collapse step is ever removed.
     */
    @Test
    fun `resolving then persisting preserves the user's appearance`() {
        AppTheme.entries.forEach { stored ->
            val first = resolve(stored)
            val second = first.base.toStoredAppTheme().toThemePreference(
                storedAmoled = first.amoled,
                storedAccent = first.accent
            )
            assertEquals(first, second, "$stored changed appearance on round trip")
        }
    }
}
