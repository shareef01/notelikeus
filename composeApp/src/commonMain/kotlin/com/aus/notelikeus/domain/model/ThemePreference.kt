package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable

/**
 * The three themes the picker offers.
 *
 * [AppTheme] used to be all of this: six entries covering base, black level and hue at once, four
 * of which were dark schemes differing mainly in how black the background was. Splitting the three
 * independent decisions apart means Dark + AMOLED + green is expressible, and so is light + green,
 * neither of which had a name before.
 */
enum class ThemeBase { SYSTEM, LIGHT, DARK }

/**
 * The hue applied over the base theme.
 *
 * [NEUTRAL] is the grey scheme the app has always had. [BLUE] and [GREEN] are what the Midnight
 * and Forest themes actually were — a dark scheme with a tinted primary and tinted surfaces — now
 * expressible independently of the black level.
 */
enum class AccentColor {
    NEUTRAL, BLUE, GREEN;

    companion object {
        fun fromName(name: String?): AccentColor = entries.find { it.name == name } ?: NEUTRAL
    }
}

/**
 * The resolved theme state: base, black level and hue, each chosen independently.
 */
@Immutable
data class ThemePreference(
    val base: ThemeBase = ThemeBase.SYSTEM,
    val amoled: Boolean = false,
    val accent: AccentColor = AccentColor.NEUTRAL
)

/**
 * Maps the stored [AppTheme] to the new three-part model, **on read**.
 *
 * Nothing rewrites the stored preference. A user who chose `FOREST` keeps `FOREST` in DataStore
 * until they actively change a theme setting, at which point new values are written as
 * `DARK` + accent `GREEN`. That is deliberate and permanent, not transitional: a write-time
 * migration is a one-way door that runs once, before anyone has seen the result, and if the
 * mapping is wrong the original choice is gone. Read-time mapping makes the whole change
 * reversible by reverting the build.
 *
 * It follows that this function is load-bearing indefinitely and cannot be deleted after
 * "everyone has upgraded" — see DECISIONS.md, D2.
 *
 * [storedAmoled] and [storedAccent] are the independently-stored values, used for the three
 * non-legacy entries. For `TRUE_DARK`, `MIDNIGHT` and `FOREST` the legacy name already implies a
 * black level and a hue, so it wins: those users see exactly what they saw before, regardless of
 * what the newer keys happen to hold.
 */
fun AppTheme.toThemePreference(
    storedAmoled: Boolean,
    storedAccent: AccentColor
): ThemePreference = when (this) {
    AppTheme.AUTO -> ThemePreference(ThemeBase.SYSTEM, storedAmoled, storedAccent)
    AppTheme.LIGHT -> ThemePreference(ThemeBase.LIGHT, storedAmoled, storedAccent)
    AppTheme.DARK -> ThemePreference(ThemeBase.DARK, storedAmoled, storedAccent)
    AppTheme.TRUE_DARK -> ThemePreference(ThemeBase.DARK, amoled = true, accent = AccentColor.NEUTRAL)
    AppTheme.MIDNIGHT -> ThemePreference(ThemeBase.DARK, amoled = false, accent = AccentColor.BLUE)
    AppTheme.FOREST -> ThemePreference(ThemeBase.DARK, amoled = false, accent = AccentColor.GREEN)
}

/**
 * The [AppTheme] to persist for a chosen [ThemeBase].
 *
 * Only ever one of the three non-legacy entries — the legacy names are read, never written.
 */
fun ThemeBase.toStoredAppTheme(): AppTheme = when (this) {
    ThemeBase.SYSTEM -> AppTheme.AUTO
    ThemeBase.LIGHT -> AppTheme.LIGHT
    ThemeBase.DARK -> AppTheme.DARK
}
