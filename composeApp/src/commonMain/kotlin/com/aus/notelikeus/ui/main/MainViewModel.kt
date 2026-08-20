package com.aus.notelikeus.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.BackupExportResult
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.domain.query.NoteQueryMatcher
import com.aus.notelikeus.ui.theme.noteColorCounterpart
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.delay
import com.aus.notelikeus.domain.model.DateRange
import com.aus.notelikeus.domain.query.NoteQueryParser
import com.aus.notelikeus.ui.theme.noteColorForKeyword

private const val TAG = "MainViewModel"

/** Long enough that typing a word recomputes once, short enough to feel immediate. */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * Coordinates the main screen's state. The three concerns that used to bloat this class each
 * live in their own file now: cloud account/sync in [CloudSyncController], selection actions and
 * undo in [NoteActionsController], and list filtering in [NoteQueryMatcher]. The public API is
 * unchanged — the UI keeps calling the ViewModel.
 */
class MainViewModel(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    backupExporter: NoteBackupExporter,
    backupImporter: NoteBackupImporter,
    syncManager: SyncManager,
    private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val backupExporter = backupExporter
    private val backupImporter = backupImporter

    private val cloudSync = CloudSyncController(_state, viewModelScope, syncManager)
    private val noteActions = NoteActionsController(
        repository = repository,
        state = _state,
        scope = viewModelScope,
        hideNotes = { ids -> hideNotesTemporarily(ids) },
        revealNotes = { ids -> revealNotes(ids) },
        onListStructureChanged = {
            _state.update { it.copy(listRevision = it.listRevision + 1) }
        }
    )

    private var currentNotesJob: Job? = null
    private var searchDebounceJob: Job? = null
    private val pendingHiddenIds = mutableSetOf<Long>()
    private var filterGeneration = 0

    init {
        observeScope(_state.value.query.scope)
        loadSettings()
        loadLabels()
        loadTotalNoteCount()
        loadDrawerCounts()
        loadRecentSearches()

        cloudSync.observe()
    }

    // ---- cloud account & sync (CloudSyncController) ----

    fun enterOfflineMode() = cloudSync.enterOfflineMode()

    fun signInWithGoogleIdToken(idToken: String) = cloudSync.signInWithGoogleIdToken(idToken)

    fun reportGoogleSignInFailure(error: Throwable) = cloudSync.reportGoogleSignInFailure(error)

    fun signInWithEmailPassword(email: String, password: String, createAccount: Boolean) =
        cloudSync.signInWithEmailPassword(email, password, createAccount)

    fun signOutFromCloud(deleteCloudData: Boolean = false) =
        cloudSync.signOutFromCloud(deleteCloudData)

    fun syncNotesToCloud() = cloudSync.syncNotesToCloud()

    fun downloadNotesFromCloud() = cloudSync.downloadNotesFromCloud()

    fun autoSyncOnForeground() = cloudSync.autoSyncOnForeground()

    fun clearPendingCloudSyncEvent() = cloudSync.clearPendingCloudSyncEvent()

    // ---- settings ----

    /** Dismisses the sign-in gate for good; the app runs on local storage only. */
    fun continueOffline() {
        viewModelScope.launch {
            settingsRepository.setChosenOffline(true)
        }
    }

    private fun loadSettings() {
        settingsRepository.hasChosenOffline
            .onEach { chosen ->
                _state.update { it.copy(hasChosenOffline = chosen) }
            }
            .launchIn(viewModelScope)

        settingsRepository.themePreference
            .onEach { preference ->
                _state.update { it.copy(themePreference = preference) }
            }
            .launchIn(viewModelScope)

        settingsRepository.isAppLockEnabled
            .catch { error ->
                // Fail closed: an unreadable setting must not be treated as "lock disabled".
                AppLog.warn(TAG, "App-lock setting unreadable; assuming enabled", error)
                emit(true)
            }
            .onEach { enabled ->
                // Gated on platform support so a persisted `true` (or the fail-closed `true`
                // above) can't put the UI behind a lock screen the platform cannot verify.
                val locked = enabled && AppConfig.supportsAppLock
                _state.update { it.copy(isAppLockEnabled = locked, areSettingsLoaded = true) }
            }
            .launchIn(viewModelScope)

        // A stored default lands on the query like any other change, so restoring a
        // preference and choosing one take the same path.
        settingsRepository.noteViewMode
            .onEach { mode -> updateQuery { it.copy(view = mode) } }
            .launchIn(viewModelScope)

        settingsRepository.noteSortOrder
            .onEach { order -> updateQuery { it.copy(sort = order) } }
            .launchIn(viewModelScope)

        settingsRepository.isCloudAutoSyncEnabled
            .onEach { enabled ->
                _state.update { it.copy(isCloudAutoSyncEnabled = enabled) }
            }
            .launchIn(viewModelScope)
    }

    fun setViewMode(mode: NoteViewMode) = updateQuery { it.copy(view = mode) }

    fun setSortOrder(order: NoteSortOrder) = updateQuery { it.copy(sort = order) }

    /** AMOLED is a black level for the dark schemes, independent of base theme and accent. */
    fun setAmoled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTrueDarkMode(enabled)
        }
    }

    fun setThemeBase(base: ThemeBase) {
        viewModelScope.launch {
            settingsRepository.setThemeBase(base)
        }
    }

    fun setAccentColor(accent: AccentColor) {
        viewModelScope.launch {
            settingsRepository.setAccentColor(accent)
        }
    }

    fun setCloudAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCloudAutoSyncEnabled(enabled)
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAppLockEnabled(enabled)
        }
    }

    // ---- search & recent searches ----

    private fun loadRecentSearches() {
        settingsRepository.recentSearches
            .onEach { searches ->
                _state.update { it.copy(recentSearches = searches) }
            }
            .launchIn(viewModelScope)
    }

    fun addRecentSearch(query: String) {
        viewModelScope.launch {
            settingsRepository.addRecentSearch(query)
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            settingsRepository.clearRecentSearches()
        }
    }

    fun onSearchQueryChange(query: String) = onSearchInputChange(query)

    // ---- labels & counts ----

    private fun loadLabels() {
        repository.getLabels()
            .onEach { labels ->
                _state.update { it.copy(allLabels = labels) }
            }
            .launchIn(viewModelScope)
    }

    private fun loadDrawerCounts() {
        repository.getArchivedNotes()
            .map { it.size }
            .onEach { count ->
                _state.update { it.copy(archivedNoteCount = count) }
            }
            .launchIn(viewModelScope)

        repository.getTrashedNotes()
            .map { it.size }
            .onEach { count ->
                _state.update { it.copy(trashedNoteCount = count) }
            }
            .launchIn(viewModelScope)
    }

    private fun loadTotalNoteCount() {
        repository.getActiveNoteCount()
            .onEach { count ->
                _state.update { it.copy(totalNoteCount = count) }
            }
            .launchIn(viewModelScope)
    }

    // ---- list filtering ----

    /**
     * The one way the query changes, and therefore the one thing that triggers a recompute.
     *
     * There used to be six: three `_state.map { }.distinctUntilChanged()` subscriptions plus three
     * direct calls, each able to fire the recompute independently. Adding a filter dimension meant
     * adding a seventh, and two of them firing for one user action meant recomputing twice.
     *
     * Text is debounced; everything else is immediate. A colour tap should land on the frame it
     * happens, and a keystroke should not recompute five times while a word is being typed.
     */
    fun updateQuery(transform: (NoteQuery) -> NoteQuery) {
        val previous = _state.value.baseQuery
        val next = transform(previous)
        if (next == previous) return
        persistQuerySettings(previous, next)
        applyInputs(base = next, searchInput = _state.value.searchInput, textChanged = false)
    }

    /**
     * Records what the user typed, then re-derives the query from it.
     *
     * The box holds operators as well as free text, so this is where `label:work` stops being
     * characters and becomes a filter.
     */
    fun onSearchInputChange(raw: String) {
        if (raw == _state.value.searchInput) return
        applyInputs(base = _state.value.baseQuery, searchInput = raw, textChanged = true)
    }

    /**
     * Recombines the two inputs into the query that runs, in a single state emission.
     *
     * One emission per user action on purpose: writing the raw text and then the derived query
     * separately would publish an intermediate state where the box and the results disagree, and
     * would recompose the list twice for one keystroke.
     *
     * Both inputs are re-read every time rather than accumulated, so deleting `label:work` from
     * the text removes exactly that filter and leaves the chips alone. A merge-in-place could not
     * do that: it would have no way to tell which of the current filters came from text that is
     * no longer there.
     */
    private fun applyInputs(base: NoteQuery, searchInput: String, textChanged: Boolean) {
        val state = _state.value
        val parsed = NoteQueryParser.parse(searchInput, ::startOfDayOffset)

        val operatorLabels = parsed.labelNames.mapNotNull { name ->
            state.allLabels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
        }
        // Colours resolve on the active palette and expand to their counterpart, so `color:green`
        // finds a note coloured green under either theme.
        val operatorColors = parsed.colorNames
            .mapNotNull { noteColorForKeyword(it, isDarkPalette()) }
            .flatMap { argb -> listOfNotNull(argb, noteColorCounterpart(argb)?.takeIf { it != argb }) }

        val next = base.copy(
            text = parsed.text,
            labels = base.labels + operatorLabels,
            colors = base.colors + operatorColors,
            flags = base.flags + parsed.flags,
            scope = parsed.scope ?: base.scope,
            dateField = parsed.dateField ?: base.dateField,
            dateRange = dateRangeOf(parsed.after, parsed.before) ?: base.dateRange
        )

        val scopeChanged = next.scope != state.query.scope
        _state.update {
            it.copy(
                searchInput = searchInput,
                baseQuery = base,
                query = next,
                unknownOperators = parsed.unknown
            )
        }
        when {
            scopeChanged -> observeScope(next.scope)
            textChanged -> scheduleTextRecompute()
            else -> recompute()
        }
    }

    private fun dateRangeOf(after: Long?, before: Long?): DateRange? = when {
        after == null && before == null -> null
        // An open end is a bound far enough out to be unreachable rather than a nullable field on
        // DateRange, which would double every comparison in the matcher.
        else -> DateRange(after ?: Long.MIN_VALUE, before ?: Long.MAX_VALUE)
    }

    /** Epoch millis at the start of the local day [offset] days from today. */
    private fun startOfDayOffset(offset: Int): Long =
        DateUtils.startOfDay(DateUtils.currentTimeMillis() + offset * DateUtils.DAY_IN_MILLIS)

    /**
     * Which half of the palette is on screen, for resolving `color:` keywords.
     *
     * Read from the resolved theme rather than from a composable, because the ViewModel has to
     * answer this without one.
     */
    private fun isDarkPalette(): Boolean =
        _state.value.themePreference.base != ThemeBase.LIGHT

    /** `sort` and `view` are durable preferences; nothing else in the query is. */
    private fun persistQuerySettings(previous: NoteQuery, next: NoteQuery) {
        if (next.sort != previous.sort) {
            viewModelScope.launch { settingsRepository.setNoteSortOrder(next.sort) }
        }
        if (next.view != previous.view) {
            viewModelScope.launch { settingsRepository.setNoteViewMode(next.view) }
        }
    }

    private fun scheduleTextRecompute() {
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            recompute()
        }
    }

    /**
     * Re-runs the query over the notes currently loaded for the scope.
     *
     * [filterGeneration] discards a result whose query has already been superseded, so a slow pass
     * cannot overwrite a newer one -- the recompute runs off the main thread, and typing produces
     * overlapping passes by design.
     */
    private fun recompute() {
        val generation = ++filterGeneration
        val snapshot = _state.value
        val hiddenIds = pendingHiddenIds.toSet()
        val now = DateUtils.currentTimeMillis()
        viewModelScope.launch {
            val visible = withContext(defaultDispatcher) {
                val candidates = snapshot.notes.filter { it.id == null || it.id !in hiddenIds }
                NoteQueryMatcher.apply(candidates, snapshot.query, now)
            }
            if (generation != filterGeneration) return@launch
            _state.update { it.copy(filteredNotes = visible) }
        }
    }

    /**
     * Subscribes to the notes for a scope.
     *
     * Scope decides which DAO Flow is collected, so it is the one query dimension that cannot be
     * applied in memory over an already-loaded list -- changing it means resubscribing.
     */
    private fun observeScope(scope: NoteScope) {
        currentNotesJob?.cancel()
        _state.update { it.copy(selectedNotes = emptySet()) }

        val notesFlow = when (scope) {
            NoteScope.ACTIVE, NoteScope.ALL -> repository.getActiveNotes()
            NoteScope.ARCHIVE -> repository.getArchivedNotes()
            NoteScope.TRASH -> repository.getTrashedNotes()
        }

        currentNotesJob = notesFlow
            .onEach { notes ->
                val emittedIds = notes.mapNotNull { it.id }.toSet()
                pendingHiddenIds.removeIf { it !in emittedIds }
                _state.update { it.copy(notes = notes, isLoading = false) }
                recompute()
            }
            .launchIn(viewModelScope)
    }

    fun setFilter(filter: NoteFilter) = updateQuery { it.copy(scope = filter.toScope()) }

    private fun hideNotesTemporarily(noteIds: Collection<Long>) {
        pendingHiddenIds.addAll(noteIds)
        recompute()
    }

    private fun revealNotes(noteIds: Collection<Long>) {
        if (noteIds.isEmpty()) return
        pendingHiddenIds.removeAll(noteIds.toSet())
        _state.update { it.copy(listRevision = it.listRevision + 1) }
        recompute()
    }

    /**
     * Selects a colour, expanded to its counterpart in the other theme.
     *
     * A note stores whichever palette variant was on screen when it was coloured, so filtering on
     * one variant alone would hide the same colour saved under the other theme. The chosen value
     * is inserted first, so `MainState.selectedColor` reports what the user actually picked.
     */
    fun selectColorFilter(color: Int?) = updateQuery { query ->
        val colors = when (color) {
            null -> emptySet()
            else -> setOfNotNull(color, noteColorCounterpart(color)?.takeIf { it != color })
        }
        query.copy(colors = colors)
    }

    fun selectLabelFilter(labelId: Long?) = updateQuery { query ->
        query.copy(labels = setOfNotNull(labelId))
    }

    /**
     * Clears the chips *and* the search box.
     *
     * Leaving the text would re-apply its operators on the next rebuild, so "Clear filters" would
     * visibly do nothing for anyone filtering by typing.
     */
    fun clearFilters() =
        applyInputs(base = _state.value.baseQuery.cleared(), searchInput = "", textChanged = false)

    // ---- selection & note actions (NoteActionsController) ----

    fun stageEditorUndo(note: Note, type: UndoAction, message: String) =
        noteActions.stageEditorUndo(note, type, message)

    fun clearPendingUndoMessage() = noteActions.clearPendingUndoMessage()

    fun clearPendingActionFailure() = noteActions.clearPendingActionFailure()

    fun toggleNoteSelection(noteId: Long) = noteActions.toggleNoteSelection(noteId)

    fun archiveNote(note: Note) = noteActions.archiveNote(note)

    fun trashNote(note: Note) = noteActions.trashNote(note)

    fun toggleSelectAll() = noteActions.toggleSelectAll()

    fun emptyTrash() = noteActions.emptyTrash()

    fun clearSelection() = noteActions.clearSelection()

    fun deleteSelectedNotes() = noteActions.deleteSelectedNotes()

    fun archiveSelectedNotes() = noteActions.archiveSelectedNotes()

    fun restoreSelectedNotes() = noteActions.restoreSelectedNotes()

    fun setSelectedNotesPinned(pin: Boolean) = noteActions.setSelectedNotesPinned(pin)

    fun undoLastAction() = noteActions.undoLastAction()

    // ---- manual reorder ----

    fun previewMoveNote(fromIndex: Int, toIndex: Int) {
        val filtered = _state.value.filteredNotes
        if (fromIndex !in filtered.indices || toIndex !in filtered.indices) return
        if (filtered[fromIndex].isPinned != filtered[toIndex].isPinned) return

        val fromId = filtered[fromIndex].id ?: return
        val toId = filtered[toIndex].id ?: return

        val fullNotes = _state.value.notes.toMutableList()
        val fromFull = fullNotes.indexOfFirst { it.id == fromId }
        val toFull = fullNotes.indexOfFirst { it.id == toId }
        if (fromFull < 0 || toFull < 0) return

        val item = fullNotes.removeAt(fromFull)
        fullNotes.add(toFull, item)
        _state.update { it.copy(notes = fullNotes) }
        recompute()
    }

    fun commitNoteOrder() {
        viewModelScope.launch {
            val notes = _state.value.notes
            try {
                repository.updateNotePositions(notes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The drag has already been applied to the in-memory list, so without this the
                // order looks saved until the next time the notes flow re-emits.
                AppLog.warn(TAG, "Saving the new note order failed", error)
                _state.update { it.copy(pendingActionFailure = NoteActionFailure.REORDER) }
            }
        }
    }

    // ---- backup & restore ----

    suspend fun exportBackup(): BackupExportResult {
        return try {
            val json = backupExporter.createJson()
            BackupExportResult.Success(json)
        } catch (e: Exception) {
            BackupExportResult.Error(e)
        }
    }

    suspend fun importBackup(json: String): BackupImportResult {
        return try {
            backupImporter.importFromJson(json)
        } catch (e: Exception) {
            BackupImportResult.Error(e)
        }
    }
}
