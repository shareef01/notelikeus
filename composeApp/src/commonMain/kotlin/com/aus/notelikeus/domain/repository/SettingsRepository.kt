package com.aus.notelikeus.domain.repository

import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import kotlinx.coroutines.flow.Flow
import com.aus.notelikeus.domain.model.SavedFilter
import com.aus.notelikeus.domain.model.NoteQuery

interface SettingsRepository {
    /**
     * The resolved theme: base, black level and accent, each chosen independently.
     *
     * Derived from the stored [AppTheme] plus the AMOLED and accent keys. The three legacy
     * theme names are still readable and still resolve to what their users chose; nothing
     * rewrites them. See ThemePreference.toThemePreference and DECISIONS.md D2.
     */
    val themePreference: Flow<ThemePreference>
    suspend fun setThemeBase(base: ThemeBase)
    suspend fun setAccentColor(accent: AccentColor)

    val isTrueDarkMode: Flow<Boolean>
    suspend fun setTrueDarkMode(enabled: Boolean)

    val isAppLockEnabled: Flow<Boolean>
    suspend fun setAppLockEnabled(enabled: Boolean)
    val noteViewMode: Flow<NoteViewMode>
    suspend fun setNoteViewMode(mode: NoteViewMode)
    val noteSortOrder: Flow<NoteSortOrder>
    suspend fun setNoteSortOrder(order: NoteSortOrder)
    val isCloudAutoSyncEnabled: Flow<Boolean>
    suspend fun setCloudAutoSyncEnabled(enabled: Boolean)

    /** True once the user has chosen to continue without an account. */
    val hasChosenOffline: Flow<Boolean>
    suspend fun setChosenOffline(chosen: Boolean)

    /**
     * Named queries, newest first.
     *
     * Never throws and never propagates a parse failure: a settings value that cannot be read
     * yields an empty list, because a corrupt shortcut is not worth taking the notes list down
     * for. See SettingsRepositoryImpl.savedFilters.
     */
    val savedFilters: Flow<List<SavedFilter>>

    /** Saves [query] under [name], replacing any filter already using that name. */
    suspend fun saveFilter(name: String, query: NoteQuery)
    suspend fun deleteSavedFilter(name: String)

    val recentSearches: Flow<List<String>>
    suspend fun addRecentSearch(query: String)
    suspend fun clearRecentSearches()
}
