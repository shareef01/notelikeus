package com.aus.notelikeus.domain.repository

import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
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
}
