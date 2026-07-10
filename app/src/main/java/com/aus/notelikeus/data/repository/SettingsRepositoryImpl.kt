package com.aus.notelikeus.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.aus.notelikeus.data.local.APP_LOCK_ENABLED_KEY
import com.aus.notelikeus.data.local.CLOUD_AUTO_SYNC_ENABLED_KEY
import com.aus.notelikeus.data.local.NOTE_SORT_ORDER_KEY
import com.aus.notelikeus.data.local.NOTE_VIEW_MODE_KEY
import com.aus.notelikeus.data.local.RECENT_SEARCHES_KEY
import com.aus.notelikeus.data.local.TRUE_DARK_MODE_KEY
import com.aus.notelikeus.data.local.USE_MONOCHROME_THEME_KEY
import com.aus.notelikeus.data.local.settingsDataStore
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.ui.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun refreshWidget() {
        widgetScope.launch {
            WidgetUpdater.refresh(context)
        }
    }

    override val isTrueDarkMode: Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[TRUE_DARK_MODE_KEY] ?: false
        }

    override suspend fun setTrueDarkMode(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[TRUE_DARK_MODE_KEY] = enabled
        }
        refreshWidget()
    }

    override val isAppLockEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[APP_LOCK_ENABLED_KEY] ?: false
        }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[APP_LOCK_ENABLED_KEY] = enabled
        }
    }

    override val noteViewMode: Flow<NoteViewMode> = context.settingsDataStore.data
        .map { preferences ->
            NoteViewMode.fromName(preferences[NOTE_VIEW_MODE_KEY])
        }

    override suspend fun setNoteViewMode(mode: NoteViewMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[NOTE_VIEW_MODE_KEY] = mode.name
        }
    }

    override val noteSortOrder: Flow<NoteSortOrder> = context.settingsDataStore.data
        .map { preferences ->
            NoteSortOrder.fromName(preferences[NOTE_SORT_ORDER_KEY])
        }

    override suspend fun setNoteSortOrder(order: NoteSortOrder) {
        context.settingsDataStore.edit { preferences ->
            preferences[NOTE_SORT_ORDER_KEY] = order.name
        }
    }

    override val useMonochromeTheme: Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[USE_MONOCHROME_THEME_KEY] ?: true
        }

    override suspend fun setUseMonochromeTheme(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[USE_MONOCHROME_THEME_KEY] = enabled
        }
        refreshWidget()
    }

    override val isCloudAutoSyncEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[CLOUD_AUTO_SYNC_ENABLED_KEY] ?: true
        }

    override suspend fun setCloudAutoSyncEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[CLOUD_AUTO_SYNC_ENABLED_KEY] = enabled
        }
    }

    override val recentSearches: Flow<List<String>> = context.settingsDataStore.data
        .map { preferences ->
            preferences[RECENT_SEARCHES_KEY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        }

    override suspend fun addRecentSearch(query: String) {
        if (query.isBlank()) return
        context.settingsDataStore.edit { preferences ->
            val existing = preferences[RECENT_SEARCHES_KEY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(query.trim()) + existing.filter { it != query.trim() }).take(MAX_RECENT_SEARCHES)
            preferences[RECENT_SEARCHES_KEY] = updated.joinToString("|")
        }
    }

    override suspend fun clearRecentSearches() {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(RECENT_SEARCHES_KEY)
        }
    }

    companion object {
        private const val MAX_RECENT_SEARCHES = 10
    }
}
