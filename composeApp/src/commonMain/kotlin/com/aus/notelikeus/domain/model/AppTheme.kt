package com.aus.notelikeus.domain.model

/**
 * The stored theme vocabulary. **Not** a UI model — nothing renders these directly any more.
 *
 * Appearance is chosen as [ThemePreference] (base x black level x accent); this enum is only the
 * string that lands in DataStore, and the three legacy entries below are read-only history.
 * `toThemePreference` maps them, and nothing ever writes them again — see DECISIONS.md D2 for why
 * that mapping is permanent rather than transitional.
 *
 * It stays a six-entry enum for exactly that reason: dropping TRUE_DARK, MIDNIGHT or FOREST would
 * make `fromName` fall through to AUTO and silently reset the appearance of anyone who has not
 * touched their theme since.
 */
enum class AppTheme {
    AUTO,
    LIGHT,
    DARK,

    // Legacy: written by builds that fused base, black level and hue into one setting.
    // Read and mapped, never written.
    TRUE_DARK,
    MIDNIGHT,
    FOREST;

    companion object {
        fun fromName(name: String?): AppTheme {
            return entries.find { it.name == name } ?: AUTO
        }
    }
}
