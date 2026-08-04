package com.aus.notelikeus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aus.notelikeus.data.local.*
import com.aus.notelikeus.domain.model.AppTheme
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

    private fun refreshWidget() {
        widgetManager.refreshWidgets()
    }

    override val appTheme: Flow<AppTheme> = dataStore.data
        .map { preferences ->
            AppTheme.fromName(preferences[APP_THEME_KEY])
        }

    override suspend fun setAppTheme(theme: AppTheme) {
        dataStore.edit { preferences ->
            preferences[APP_THEME_KEY] = theme.name
        }
        refreshWidget()
    }

    override val isTrueDarkMode: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[TRUE_DARK_MODE_KEY] ?: false
        }

    override suspend fun setTrueDarkMode(enabled: Boolean) {
        dataStore.edit { preferences ->
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
