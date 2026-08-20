package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.model.ThemePreference

enum class NoteFilter {
    ACTIVE, ARCHIVED, TRASHED;

    fun toScope(): NoteScope = when (this) {
        ACTIVE -> NoteScope.ACTIVE
        ARCHIVED -> NoteScope.ARCHIVE
        TRASHED -> NoteScope.TRASH
    }

    companion object {
        /** [NoteScope.ALL] has no equivalent here; it reads as ACTIVE until the drawer offers it. */
        fun fromScope(scope: NoteScope): NoteFilter = when (scope) {
            NoteScope.ACTIVE, NoteScope.ALL -> ACTIVE
            NoteScope.ARCHIVE -> ARCHIVED
            NoteScope.TRASH -> TRASHED
        }
    }
}

enum class UndoAction {
    ARCHIVE, TRASH, PERMANENT_DELETE
}

/**
 * A note write that failed after the list had already been updated optimistically. The UI maps it
 * to a message; the state carries the kind rather than the text so the string stays localised.
 */
enum class NoteActionFailure {
    UPDATE, DELETE, UNDO, REORDER
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
    /**
     * Everything that decides which notes are shown and in what order.
     *
     * The single source of truth for filtering. What used to be five independent fields here --
     * searchQuery, selectedColor, selectedLabelId, sortOrder, currentFilter -- each with its own
     * setter and its own path into the recompute, and six such paths in total.
     */
    val query: NoteQuery = NoteQuery(),
    /** Base, black level and accent, resolved from the stored theme. See ThemePreference. */
    val themePreference: ThemePreference = ThemePreference(),
    val isAppLockEnabled: Boolean = false,
    val areSettingsLoaded: Boolean = false,
    val pendingUndoMessage: String? = null,
    val pendingActionFailure: NoteActionFailure? = null,
    val selectedNotes: Set<Long> = emptySet(),
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
) {
    // The five fields the query replaced, kept as read-only views onto it.
    //
    // The screens still speak in these terms and rebuilding that surface is the next phase's job;
    // meanwhile these keep the call sites compiling without giving anything a second place to
    // store the same fact. They are deliberately get-only: there is one setter, on the query.

    val searchQuery: String get() = query.text

    /**
     * The colour the user picked, when exactly one is selected.
     *
     * `query.colors` also holds that colour's light/dark counterpart so a note saved under either
     * theme matches, so this reads the first entry rather than the only one -- insertion order is
     * preserved and the user's own choice is inserted first.
     */
    val selectedColor: Int? get() = query.colors.firstOrNull()

    val selectedLabelId: Long? get() = query.labels.firstOrNull()

    val sortOrder: NoteSortOrder get() = query.sort

    val viewMode: NoteViewMode get() = query.view

    val currentFilter: NoteFilter get() = NoteFilter.fromScope(query.scope)
}
