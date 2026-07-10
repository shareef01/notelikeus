package com.aus.notelikeus.domain.repository

import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val appTheme: Flow<AppTheme>
    suspend fun setAppTheme(theme: AppTheme)

    val isTrueDarkMode: Flow<Boolean>
    suspend fun setTrueDarkMode(enabled: Boolean)
    val isAppLockEnabled: Flow<Boolean>
    suspend fun setAppLockEnabled(enabled: Boolean)
    val noteViewMode: Flow<NoteViewMode>
    suspend fun setNoteViewMode(mode: NoteViewMode)
    val noteSortOrder: Flow<NoteSortOrder>
    suspend fun setNoteSortOrder(order: NoteSortOrder)
    val useMonochromeTheme: Flow<Boolean>
    suspend fun setUseMonochromeTheme(enabled: Boolean)
    val isCloudAutoSyncEnabled: Flow<Boolean>
    suspend fun setCloudAutoSyncEnabled(enabled: Boolean)

    val recentSearches: Flow<List<String>>
    suspend fun addRecentSearch(query: String)
    suspend fun clearRecentSearches()

    suspend fun getLastKnownCloudIds(userId: String): Set<String>
    suspend fun setLastKnownCloudIds(userId: String, cloudIds: Set<String>)
    suspend fun clearLastKnownCloudIds(userId: String)
}
