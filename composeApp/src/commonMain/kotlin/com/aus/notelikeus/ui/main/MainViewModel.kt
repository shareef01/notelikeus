package com.aus.notelikeus.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.BackupExportResult
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.theme.noteColorsMatch
import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MainViewModel"
private const val AUTO_PULL_MIN_INTERVAL_MS = 30_000L

enum class NoteFilter {
    ACTIVE, ARCHIVED, TRASHED
}

enum class UndoAction {
    ARCHIVE, TRASH, PERMANENT_DELETE
}

private data class PendingUndo(
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
    val isTrueDarkMode: Boolean = false,
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
    val isSigningIn: Boolean = false
)

class MainViewModel(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val backupExporter: NoteBackupExporter,
    private val backupImporter: NoteBackupImporter,
    private val syncManager: SyncManager,
    private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private var currentNotesJob: Job? = null
    private var pendingUndo: PendingUndo? = null
    private val pendingHiddenIds = mutableSetOf<Long>()
    private var filterGeneration = 0
    private var lastAutoPullElapsedMs = 0L

    init {
        setFilter(NoteFilter.ACTIVE)
        loadSettings()
        loadLabels()
        loadTotalNoteCount()
        loadDrawerCounts()
        setupSearchOptimization()
        loadRecentSearches()
        
        syncManager.cloudAccount.onEach { account ->
            _state.update { it.copy(cloudAccount = account) }
        }.launchIn(viewModelScope)
        
        syncManager.syncStatus.onEach { status ->
            _state.update { it.copy(cloudSyncStatus = status) }
        }.launchIn(viewModelScope)
    }

    fun enterOfflineMode() {
        _state.update {
            it.copy(
                cloudAccount = CloudAccount(
                    email = null,
                    isGoogleAccount = false,
                    isAnonymous = false,
                    isOfflineMode = true
                )
            )
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSigningIn = true) }
            val result = syncManager.signInWithGoogle(idToken)
            if (result.isSuccess) {
                // Immediately pull cloud notes after successful sign-in
                downloadNotesFromCloud()
            }
            _state.update { state ->
                state.copy(
                    isSigningIn = false,
                    // Surfaced through pendingCloudSyncEvent, which SignInGate already renders as
                    // externalError. This used to drop the Result on the floor, so a failed
                    // sign-in just silently returned the user to the gate.
                    pendingCloudSyncEvent = result.exceptionOrNull()
                        ?.let { CloudSyncEvent.Failure(signInFailureMessage(it)) }
                        ?: state.pendingCloudSyncEvent
                )
            }
        }
    }

    /**
     * Reports a failure from the platform sign-in UI itself (before any token exists) — user
     * cancellation, no Google account on the device, no Play Services, and so on.
     */
    fun reportGoogleSignInFailure(error: Throwable) {
        _state.update {
            it.copy(
                isSigningIn = false,
                pendingCloudSyncEvent = CloudSyncEvent.Failure(signInFailureMessage(error))
            )
        }
    }

    private fun signInFailureMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "Google sign-in failed"

    fun signInWithEmailPassword(email: String, password: String, createAccount: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isSigningIn = true) }
            syncManager.signInWithEmail(email, password, createAccount)
            _state.update { it.copy(isSigningIn = false) }
        }
    }

    fun signOutFromCloud(deleteCloudData: Boolean = false) {
        viewModelScope.launch {
            syncManager.signOut(deleteCloudData)
        }
    }

    fun syncNotesToCloud() {
        viewModelScope.launch {
            syncManager.syncNotes()
        }
    }

    fun downloadNotesFromCloud() {
        viewModelScope.launch {
            syncManager.downloadNotes()
        }
    }

    /**
     * Best-effort pull when the app returns to the foreground.
     */
    fun autoSyncOnForeground() {
        if (_state.value.cloudSyncStatus == CloudSyncStatus.Syncing) return
        if (!_state.value.cloudAccount.isGoogleAccount) return
        if (!_state.value.isCloudAutoSyncEnabled) return
        val now = DateUtils.currentTimeMillis()
        if (now - lastAutoPullElapsedMs < AUTO_PULL_MIN_INTERVAL_MS) return
        lastAutoPullElapsedMs = now
        viewModelScope.launch {
            syncManager.downloadNotes()
        }
    }

    fun clearPendingCloudSyncEvent() {
        syncManager.clearPendingEvent()
        _state.update { it.copy(pendingCloudSyncEvent = null) }
    }

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

    private fun loadSettings() {
        settingsRepository.appTheme
            .onEach { theme ->
                _state.update { it.copy(appTheme = theme) }
            }
            .launchIn(viewModelScope)

        settingsRepository.isTrueDarkMode
            .onEach { enabled ->
                _state.update { it.copy(isTrueDarkMode = enabled) }
            }
            .launchIn(viewModelScope)

        settingsRepository.isAppLockEnabled
            .catch { error ->
                emit(true)
            }
            .onEach { enabled ->
                // Gated on platform support so a persisted `true` (or the fail-closed `true`
                // above) can't put the UI behind a lock screen the platform cannot verify.
                val locked = enabled && AppConfig.supportsAppLock
                _state.update { it.copy(isAppLockEnabled = locked, areSettingsLoaded = true) }
            }
            .launchIn(viewModelScope)

        settingsRepository.noteViewMode
            .onEach { mode ->
                _state.update { it.copy(viewMode = mode) }
            }
            .launchIn(viewModelScope)

        settingsRepository.noteSortOrder
            .onEach { order ->
                _state.update { it.copy(sortOrder = order) }
                applyFilters()
            }
            .launchIn(viewModelScope)

        settingsRepository.isCloudAutoSyncEnabled
            .onEach { enabled ->
                _state.update { it.copy(isCloudAutoSyncEnabled = enabled) }
            }
            .launchIn(viewModelScope)
    }

    private fun loadLabels() {
        repository.getLabels()
            .onEach { labels ->
                _state.update { it.copy(allLabels = labels) }
            }
            .launchIn(viewModelScope)
    }

    @OptIn(FlowPreview::class)
    private fun setupSearchOptimization() {
        _state
            .map { it.searchQuery }
            .distinctUntilChanged()
            .debounce(300.milliseconds)
            .onEach { applyFilters() }
            .launchIn(viewModelScope)

        _state
            .map { Triple(it.selectedColor, it.selectedLabelId, it.notes) }
            .distinctUntilChanged()
            .onEach { applyFilters() }
            .launchIn(viewModelScope)

        _state
            .map { it.sortOrder }
            .distinctUntilChanged()
            .onEach { applyFilters() }
            .launchIn(viewModelScope)
    }

    private fun applyFilters() {
        val generation = ++filterGeneration
        val snapshot = _state.value
        val hiddenIds = pendingHiddenIds.toSet()
        viewModelScope.launch {
            val sorted = withContext(defaultDispatcher) { filterAndSort(snapshot, hiddenIds) }
            if (generation != filterGeneration) return@launch
            _state.update { it.copy(filteredNotes = sorted) }
        }
    }

    private fun filterAndSort(s: MainState, hiddenIds: Set<Long>): List<Note> {
        val filtered = s.notes.filter { note ->
            val noteId = note.id
            if (noteId != null && noteId in hiddenIds) return@filter false

            val matchesSearch = s.searchQuery.isEmpty() ||
                note.title.contains(s.searchQuery, ignoreCase = true) ||
                note.content.contains(s.searchQuery, ignoreCase = true) ||
                note.checklist.any { it.text.contains(s.searchQuery, ignoreCase = true) } ||
                note.labels.any { it.name.contains(s.searchQuery, ignoreCase = true) }

            val matchesColor = s.selectedColor == null || noteColorsMatch(note.color, s.selectedColor)

            val matchesLabel = s.selectedLabelId == null ||
                note.labels.any { it.id == s.selectedLabelId }

            matchesSearch && matchesColor && matchesLabel
        }
        return when (s.sortOrder) {
            NoteSortOrder.MANUAL -> {
                filtered.filter { it.isPinned } + filtered.filter { !it.isPinned }
            }
            NoteSortOrder.NEWEST -> {
                filtered.filter { it.isPinned }.sortedByDescending { it.timestamp } +
                    filtered.filter { !it.isPinned }.sortedByDescending { it.timestamp }
            }
            NoteSortOrder.OLDEST -> {
                filtered.filter { it.isPinned }.sortedBy { it.timestamp } +
                    filtered.filter { !it.isPinned }.sortedBy { it.timestamp }
            }
        }
    }

    fun setFilter(filter: NoteFilter) {
        currentNotesJob?.cancel()
        _state.update { it.copy(currentFilter = filter, selectedNotes = emptySet()) }

        val notesFlow = when (filter) {
            NoteFilter.ACTIVE -> repository.getActiveNotes()
            NoteFilter.ARCHIVED -> repository.getArchivedNotes()
            NoteFilter.TRASHED -> repository.getTrashedNotes()
        }

        currentNotesJob = notesFlow
            .onEach { notes ->
                val emittedIds = notes.mapNotNull { it.id }.toSet()
                pendingHiddenIds.removeIf { it !in emittedIds }
                _state.update { it.copy(notes = notes, isLoading = false) }
                applyFilters()
            }
            .launchIn(viewModelScope)
    }

    private fun hideNoteTemporarily(noteId: Long) {
        pendingHiddenIds.add(noteId)
        applyFilters()
    }

    private fun hideNotesTemporarily(noteIds: Collection<Long>) {
        pendingHiddenIds.addAll(noteIds)
        applyFilters()
    }

    private fun revealNotes(noteIds: Collection<Long>) {
        if (noteIds.isEmpty()) return
        pendingHiddenIds.removeAll(noteIds.toSet())
        _state.update { it.copy(listRevision = it.listRevision + 1) }
        applyFilters()
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun selectColorFilter(color: Int?) {
        _state.update { it.copy(selectedColor = color) }
    }

    fun selectLabelFilter(labelId: Long?) {
        _state.update { it.copy(selectedLabelId = labelId) }
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                selectedColor = null,
                selectedLabelId = null,
                searchQuery = ""
            )
        }
        applyFilters()
    }

    fun setViewMode(mode: NoteViewMode) {
        _state.update { it.copy(viewMode = mode) }
        viewModelScope.launch {
            settingsRepository.setNoteViewMode(mode)
        }
    }

    fun setSortOrder(order: NoteSortOrder) {
        _state.update { it.copy(sortOrder = order) }
        viewModelScope.launch {
            settingsRepository.setNoteSortOrder(order)
        }
        applyFilters()
    }

    fun setAppTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.setAppTheme(theme)
        }
    }

    fun setTrueDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTrueDarkMode(enabled)
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

    fun stageEditorUndo(note: Note, type: UndoAction, message: String) {
        pendingUndo = PendingUndo(listOf(note), type)
        _state.update { it.copy(pendingUndoMessage = message) }
    }

    fun clearPendingUndoMessage() {
        _state.update { it.copy(pendingUndoMessage = null) }
    }

    fun toggleNoteSelection(noteId: Long) {
        _state.update { currentState ->
            val newSelection = if (currentState.selectedNotes.contains(noteId)) {
                currentState.selectedNotes - noteId
            } else {
                currentState.selectedNotes + noteId
            }
            currentState.copy(selectedNotes = newSelection)
        }
    }

    fun archiveNote(note: Note) {
        val noteId = note.id ?: return
        pendingUndo = PendingUndo(listOf(note), UndoAction.ARCHIVE)
        hideNoteTemporarily(noteId)
        viewModelScope.launch {
            repository.updateNote(
                note.copy(
                    isArchived = true,
                    isTrashed = false,
                    timestamp = DateUtils.currentTimeMillis()
                )
            )
        }
    }

    fun trashNote(note: Note) {
        val noteId = note.id ?: return
        viewModelScope.launch {
            if (_state.value.currentFilter == NoteFilter.TRASHED) {
                pendingUndo = PendingUndo(listOf(note), UndoAction.PERMANENT_DELETE)
                hideNoteTemporarily(noteId)
                repository.deleteNote(note)
            } else {
                pendingUndo = PendingUndo(listOf(note), UndoAction.TRASH)
                hideNoteTemporarily(noteId)
                repository.updateNote(
                    note.copy(
                        isTrashed = true,
                        isArchived = false,
                        timestamp = DateUtils.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun toggleSelectAll() {
        val visibleIds = _state.value.filteredNotes.mapNotNull { it.id }.toSet()
        if (visibleIds.isEmpty()) return
        _state.update { currentState ->
            val allSelected = visibleIds.all { it in currentState.selectedNotes }
            currentState.copy(
                selectedNotes = if (allSelected) emptySet() else visibleIds
            )
        }
    }

    fun emptyTrash() {
        if (_state.value.currentFilter != NoteFilter.TRASHED) return
        viewModelScope.launch {
            val notesToDelete = _state.value.notes.toList()
            if (notesToDelete.isEmpty()) return@launch
            pendingUndo = PendingUndo(notesToDelete, UndoAction.PERMANENT_DELETE)
            hideNotesTemporarily(notesToDelete.mapNotNull { it.id })
            notesToDelete.forEach { note ->
                repository.deleteNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
            }
            clearSelection()
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedNotes = emptySet()) }
    }

    fun deleteSelectedNotes() {
        viewModelScope.launch {
            val notesToDelete = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            val type = if (_state.value.currentFilter == NoteFilter.TRASHED) {
                UndoAction.PERMANENT_DELETE
            } else {
                UndoAction.TRASH
            }
            pendingUndo = PendingUndo(notesToDelete, type)
            hideNotesTemporarily(notesToDelete.mapNotNull { it.id })
            notesToDelete.forEach { note ->
                if (_state.value.currentFilter == NoteFilter.TRASHED) {
                    repository.deleteNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
                } else {
                    repository.updateNote(
                        note.copy(
                            isTrashed = true,
                            isArchived = false,
                            timestamp = DateUtils.currentTimeMillis()
                        )
                    )
                }
            }
            clearSelection()
        }
    }

    fun archiveSelectedNotes() {
        viewModelScope.launch {
            val notesToArchive = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            pendingUndo = PendingUndo(notesToArchive, UndoAction.ARCHIVE)
            hideNotesTemporarily(notesToArchive.mapNotNull { it.id })
            notesToArchive.forEach { note ->
                repository.updateNote(
                    note.copy(
                        isArchived = true,
                        isTrashed = false,
                        timestamp = DateUtils.currentTimeMillis()
                    )
                )
            }
            clearSelection()
        }
    }

    fun restoreSelectedNotes() {
        viewModelScope.launch {
            val notesToRestore = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            notesToRestore.forEach { note ->
                repository.updateNote(note.copy(isArchived = false, isTrashed = false))
            }
            clearSelection()
        }
    }

    fun setSelectedNotesPinned(pin: Boolean) {
        viewModelScope.launch {
            val notesToUpdate = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            notesToUpdate.forEach { note ->
                repository.updateNote(note.copy(isPinned = pin))
            }
            clearSelection()
        }
    }

    fun undoLastAction() {
        val undo = pendingUndo ?: return
        viewModelScope.launch {
            val restoredIds = undo.notes.mapNotNull { it.id }
            revealNotes(restoredIds)
            when (undo.type) {
                UndoAction.ARCHIVE, UndoAction.TRASH -> {
                    undo.notes.forEach { note ->
                        repository.updateNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
                    }
                }
                UndoAction.PERMANENT_DELETE -> {
                    undo.notes.forEach { note ->
                        repository.restoreNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
                    }
                    _state.update { it.copy(listRevision = it.listRevision + 1) }
                }
            }
            pendingUndo = null
        }
    }

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
        applyFilters()
    }

    fun commitNoteOrder() {
        viewModelScope.launch {
            val notes = _state.value.notes
            repository.updateNotePositions(notes)
        }
    }

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
