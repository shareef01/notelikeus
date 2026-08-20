package com.aus.notelikeus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aus.notelikeus.data.local.*
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.domain.model.toStoredAppTheme
import com.aus.notelikeus.domain.model.toThemePreference
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val widgetManager: PlatformWidgetManager
) : SettingsRepository {

    private suspend fun refreshWidget() {
        widgetManager.refreshWidgets()
    }

    override val themePreference: Flow<ThemePreference> = dataStore.data
        .map { preferences ->
            AppTheme.fromName(preferences[APP_THEME_KEY]).toThemePreference(
                storedAmoled = preferences[TRUE_DARK_MODE_KEY] ?: false,
                storedAccent = AccentColor.fromName(preferences[ACCENT_COLOR_KEY])
            )
        }

    /**
     * Writes the base theme, and collapses any legacy value that was still stored.
     *
     * Picking a base is the moment the user takes ownership of the new model, so the accent and
     * AMOLED level their legacy theme implied are written out explicitly here. Without that,
     * moving a FOREST user to Light and back to Dark would silently drop the green, because the
     * accent had only ever been implied by the name `FOREST` and never stored.
     */
    override suspend fun setThemeBase(base: ThemeBase) {
        dataStore.edit { preferences ->
            val current = AppTheme.fromName(preferences[APP_THEME_KEY]).toThemePreference(
                storedAmoled = preferences[TRUE_DARK_MODE_KEY] ?: false,
                storedAccent = AccentColor.fromName(preferences[ACCENT_COLOR_KEY])
            )
            preferences[APP_THEME_KEY] = base.toStoredAppTheme().name
            preferences[TRUE_DARK_MODE_KEY] = current.amoled
            preferences[ACCENT_COLOR_KEY] = current.accent.name
        }
        refreshWidget()
    }

    override suspend fun setAccentColor(accent: AccentColor) {
        dataStore.edit { preferences ->
            val stored = AppTheme.fromName(preferences[APP_THEME_KEY])
            val current = stored.toThemePreference(
                storedAmoled = preferences[TRUE_DARK_MODE_KEY] ?: false,
                storedAccent = AccentColor.fromName(preferences[ACCENT_COLOR_KEY])
            )
            // Same collapse as setThemeBase: once any part of the theme is chosen explicitly the
            // legacy name must stop being the source of truth, or it would keep overriding.
            preferences[APP_THEME_KEY] = current.base.toStoredAppTheme().name
            preferences[TRUE_DARK_MODE_KEY] = current.amoled
            preferences[ACCENT_COLOR_KEY] = accent.name
        }
        refreshWidget()
    }

    override val isTrueDarkMode: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[TRUE_DARK_MODE_KEY] ?: false
        }

    override suspend fun setTrueDarkMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            val current = AppTheme.fromName(preferences[APP_THEME_KEY]).toThemePreference(
                storedAmoled = preferences[TRUE_DARK_MODE_KEY] ?: false,
                storedAccent = AccentColor.fromName(preferences[ACCENT_COLOR_KEY])
            )
            // Collapse the legacy name, as in setThemeBase: a TRUE_DARK user turning AMOLED off
            // would otherwise keep resolving to AMOLED, because the name says so.
            preferences[APP_THEME_KEY] = current.base.toStoredAppTheme().name
            preferences[ACCENT_COLOR_KEY] = current.accent.name
            preferences[TRUE_DARK_MODE_KEY] = enabled
        }
        refreshWidget()
    }

    override val isAppLockEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[APP_LOCK_ENABLED_KEY] ?: false
        }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[APP_LOCK_ENABLED_KEY] = enabled
        }
        refreshWidget()
    }

    override val noteViewMode: Flow<NoteViewMode> = dataStore.data
        .map { preferences ->
            NoteViewMode.fromName(preferences[NOTE_VIEW_MODE_KEY])
        }

    override suspend fun setNoteViewMode(mode: NoteViewMode) {
        dataStore.edit { preferences ->
            preferences[NOTE_VIEW_MODE_KEY] = mode.name
        }
    }

    override val noteSortOrder: Flow<NoteSortOrder> = dataStore.data
        .map { preferences ->
            NoteSortOrder.fromName(preferences[NOTE_SORT_ORDER_KEY])
        }

    override suspend fun setNoteSortOrder(order: NoteSortOrder) {
        dataStore.edit { preferences ->
            preferences[NOTE_SORT_ORDER_KEY] = order.name
        }
    }

    override val isCloudAutoSyncEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[CLOUD_AUTO_SYNC_ENABLED_KEY] ?: true
        }

    override suspend fun setCloudAutoSyncEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CLOUD_AUTO_SYNC_ENABLED_KEY] = enabled
        }
    }

    override val hasChosenOffline: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[CONTINUE_OFFLINE_KEY] ?: false
        }

    override suspend fun setChosenOffline(chosen: Boolean) {
        dataStore.edit { preferences ->
            preferences[CONTINUE_OFFLINE_KEY] = chosen
        }
    }

    override val recentSearches: Flow<List<String>> = dataStore.data
        .map { preferences ->
            preferences[RECENT_SEARCHES_KEY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        }

    override suspend fun addRecentSearch(query: String) {
        if (query.isBlank()) return
        dataStore.edit { preferences ->
            val existing = preferences[RECENT_SEARCHES_KEY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(query.trim()) + existing.filter { it != query.trim() }).take(MAX_RECENT_SEARCHES)
            preferences[RECENT_SEARCHES_KEY] = updated.joinToString("|")
        }
    }

    override suspend fun clearRecentSearches() {
        dataStore.edit { preferences ->
            preferences.remove(RECENT_SEARCHES_KEY)
        }
    }

    companion object {
        private const val MAX_RECENT_SEARCHES = 10
    }
}
