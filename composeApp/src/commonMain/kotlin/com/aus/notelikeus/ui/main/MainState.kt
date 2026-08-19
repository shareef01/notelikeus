package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.model.ThemePreference

enum class NoteFilter {
    ACTIVE, ARCHIVED, TRASHED
}

enum class UndoAction {
    ARCHIVE, TRASH, PERMANENT_DELETE
}

internal data class PendingUndo(
    val notes: List<Note>,
    val type: UndoAction
)

data class MainState(
    val isLoading: Boolean = true,
    val notes: List<Note> = emptyList(),
    val filteredNotes: List<Note> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val searchQuery: String = "",
    val selectedColor: Int? = null,
    val selectedLabelId: Long? = null,
    val appTheme: AppTheme = AppTheme.AUTO,
    /** Base, black level and accent, resolved from the stored theme. See ThemePreference. */
    val themePreference: ThemePreference = ThemePreference(),
    val viewMode: NoteViewMode = NoteViewMode.GRID_2,
    val sortOrder: NoteSortOrder = NoteSortOrder.MANUAL,
    val isAppLockEnabled: Boolean = false,
    val areSettingsLoaded: Boolean = false,
    val pendingUndoMessage: String? = null,
    val selectedNotes: Set<Long> = emptySet(),
    val currentFilter: NoteFilter = NoteFilter.ACTIVE,
    val allLabels: List<Label> = emptyList(),
    val totalNoteCount: Int = 0,
    val archivedNoteCount: Int = 0,
    val trashedNoteCount: Int = 0,
    val listRevision: Int = 0,
    val cloudSyncStatus: CloudSyncStatus = CloudSyncStatus.Unknown,
    val cloudSyncedNoteCount: Int = 0,
    val pendingCloudSyncEvent: CloudSyncEvent? = null,
    val cloudAccount: CloudAccount = CloudAccount(),
    val isCloudAutoSyncEnabled: Boolean = true,
    val isSigningIn: Boolean = false,
    /**
     * The user chose to use the app without an account. Persisted, so the sign-in gate asks once
     * rather than on every launch.
     */
    val hasChosenOffline: Boolean = false
)
