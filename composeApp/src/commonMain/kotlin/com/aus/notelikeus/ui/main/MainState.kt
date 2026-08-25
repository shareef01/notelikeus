package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.domain.model.SmartView
import com.aus.notelikeus.domain.model.SavedFilter

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
     * The raw contents of the search box, operators and all.
     *
     * Kept verbatim because it is what the user is typing and looking at. [query] holds what it
     * *means*, with `label:work` lifted out into a real label filter -- binding the field to that
     * instead would make operator text disappear as it was typed.
     */
    val searchInput: String = "",
    /**
     * What the chips, the drawer and the sort control have selected.
     *
     * Half of [query]'s inputs. Separate from the search box so the two cannot overwrite each
     * other: typing an operator must not clear a chip, and tapping a chip must not edit the text.
     */
    val baseQuery: NoteQuery = NoteQuery(),
    /**
     * [baseQuery] combined with whatever [searchInput]'s operators asked for.
     *
     * The single source of truth for filtering, and derived rather than set -- there is nowhere to
     * write an inconsistent value. What used to be five independent fields here -- searchQuery,
     * selectedColor, selectedLabelId, sortOrder, currentFilter -- each had its own setter and its
     * own path into the recompute, six paths in total.
     */
    val query: NoteQuery = NoteQuery(),
    /** Operator-shaped text the parser did not understand, so the UI can say so. */
    val unknownOperators: List<String> = emptyList(),
    /**
     * The visible notes are near misses, not exact matches.
     *
     * Set when nothing matched the text and the fuzzy fallback found something close. The UI has
     * to say so -- presenting corrected results as though they were what was asked for is worse
     * than showing nothing, because the user would believe their search worked.
     */
    val isFuzzyResult: Boolean = false,
    /** Base, black level and accent, resolved from the stored theme. See ThemePreference. */
    val themePreference: ThemePreference = ThemePreference(),
    val isAppLockEnabled: Boolean = false,
    val areSettingsLoaded: Boolean = false,
    val pendingUndoMessage: String? = null,
    val pendingActionFailure: NoteActionFailure? = null,
    val selectedNotes: Set<Long> = emptySet(),
    val allLabels: List<Label> = emptyList(),
    val totalNoteCount: Int = 0,
    /**
     * How many active notes each smart view would show.
     *
     * Counted with the same matcher the list runs, so a drawer row cannot promise notes the list
     * then fails to produce. Always over the *active* notes, whatever scope is on screen -- the
     * rows navigate to active notes, so a count taken from the trash would describe a different
     * list than the one tapping it opens.
     */
    val smartViewCounts: Map<SmartView, Int> = emptyMap(),
    /** Named queries the user kept, newest first. Empty until settings have loaded. */
    val savedFilters: List<SavedFilter> = emptyList(),
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

    val searchQuery: String get() = searchInput

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
