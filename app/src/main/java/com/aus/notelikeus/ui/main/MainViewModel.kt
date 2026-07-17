package com.aus.notelikeus.ui.main

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
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
import com.aus.notelikeus.data.remote.CloudNoteSyncCoordinator
import com.aus.notelikeus.data.remote.FirebaseNoteSync
import com.aus.notelikeus.data.remote.FirebaseSessionManager
import com.aus.notelikeus.data.remote.GoogleSignInHelper
import com.aus.notelikeus.data.remote.NoteSyncStateStore
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.ui.theme.noteColorsMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

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
    val notes: List<Note> = emptyList(),
    val filteredNotes: List<Note> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val searchQuery: String = "",
    val selectedColor: Int? = null,
    val selectedLabelId: Long? = null,
    val appTheme: AppTheme = AppTheme.AUTO,
    val viewMode: NoteViewMode = NoteViewMode.GRID_2,
    val sortOrder: NoteSortOrder = NoteSortOrder.MANUAL,
    val useMonochromeTheme: Boolean = true,
    val isTrueDarkMode: Boolean = false,
    val isAppLockEnabled: Boolean = false,
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
    val cloudSyncError: String? = null,
    val pendingCloudSyncEvent: CloudSyncEvent? = null,
    val cloudAccount: CloudAccount = CloudAccount(),
    val isCloudAutoSyncEnabled: Boolean = true
)

private const val TAG = "MainViewModel"

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val backupExporter: NoteBackupExporter,
    private val backupImporter: NoteBackupImporter,
    private val firebaseSessionManager: FirebaseSessionManager,
    private val firebaseNoteSync: FirebaseNoteSync,
    private val googleSignInHelper: GoogleSignInHelper,
    private val cloudNoteSyncCoordinator: CloudNoteSyncCoordinator,
    private val noteSyncStateStore: NoteSyncStateStore
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private var currentNotesJob: Job? = null
    private var pendingUndo: PendingUndo? = null
    private val pendingHiddenIds = mutableSetOf<Long>()

    init {
        setFilter(NoteFilter.ACTIVE)
        loadSettings()
        loadLabels()
        loadTotalNoteCount()
        loadDrawerCounts()
        setupSearchOptimization()
        refreshCloudAccount()
        loadRecentSearches()
    }

    private fun refreshCloudAccount() {
        val account = firebaseSessionManager.getCurrentAccount()
        _state.update {
            it.copy(
                cloudAccount = CloudAccount(
                    email = account.email,
                    isGoogleAccount = account.isGoogleAccount,
                    isAnonymous = account.isAnonymous
                )
            )
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            firebaseSessionManager.signInWithGoogle(idToken)
                .onSuccess {
                    onSignInSuccess()
                }
                .onFailure { error ->
                    val message = firebaseSessionManager.diagnose(error)
                    _state.update {
                        it.copy(pendingCloudSyncEvent = CloudSyncEvent.Failure(message))
                    }
                }
        }
    }

    fun signInWithEmailPassword(email: String, password: String, createAccount: Boolean) {
        viewModelScope.launch {
            firebaseSessionManager.signInWithEmailPassword(email, password, createAccount)
                .onSuccess {
                    onSignInSuccess()
                }
                .onFailure { error ->
                    val message = firebaseSessionManager.diagnose(error)
                    _state.update {
                        it.copy(pendingCloudSyncEvent = CloudSyncEvent.Failure(message))
                    }
                }
        }
    }

    private suspend fun onSignInSuccess() {
        refreshCloudAccount()
        verifyFirebaseConnection()
        if (_state.value.isCloudAutoSyncEnabled) {
            // Download first so a fresh account (after local wipe) fills from cloud.
            firebaseNoteSync.downloadAllNotes()
                .onSuccess {
                    uploadAllNotesSilently()
                }
                .onFailure {
                    uploadAllNotesSilently()
                }
        }
        _state.update { it.copy(pendingCloudSyncEvent = CloudSyncEvent.SignedIn) }
    }

    fun signOutFromCloud(deleteCloudData: Boolean = false) {
        viewModelScope.launch {
            if (deleteCloudData) {
                firebaseNoteSync.deleteAllCloudData()
                    .onFailure { error ->
                        val message = firebaseSessionManager.diagnose(error)
                        _state.update {
                            it.copy(pendingCloudSyncEvent = CloudSyncEvent.Failure(message))
                        }
                        return@launch
                    }
            }
            googleSignInHelper.signOutFromGoogle()
            firebaseSessionManager.signOut()
                .onSuccess {
                    repository.clearAllUserData()
                    noteSyncStateStore.clear()
                    cloudNoteSyncCoordinator.clearPending()
                    refreshCloudAccount()
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Unknown,
                            cloudSyncedNoteCount = 0,
                            notes = emptyList(),
                            filteredNotes = emptyList(),
                            allLabels = emptyList(),
                            totalNoteCount = 0,
                            archivedNoteCount = 0,
                            trashedNoteCount = 0,
                            listRevision = it.listRevision + 1,
                            pendingCloudSyncEvent = CloudSyncEvent.SignedOut(cloudDataDeleted = deleteCloudData)
                        )
                    }
                    verifyFirebaseConnection()
                }
                .onFailure { error ->
                    val message = firebaseSessionManager.diagnose(error)
                    _state.update {
                        it.copy(pendingCloudSyncEvent = CloudSyncEvent.Failure(message))
                    }
                }
        }
    }

    private fun verifyFirebaseConnection() {
        if (!firebaseSessionManager.getCurrentAccount().isGoogleAccount) {
            _state.update {
                it.copy(
                    cloudSyncStatus = CloudSyncStatus.Unknown,
                    cloudSyncError = null
                )
            }
            return
        }
        viewModelScope.launch {
            firebaseSessionManager.verifyConnection()
                .onSuccess {
                    Log.i(TAG, "Firebase connected")
                    refreshCloudAccount()
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Connected,
                            cloudSyncError = null
                        )
                    }
                }
                .onFailure { error ->
                    val message = firebaseSessionManager.diagnose(error)
                    Log.w(TAG, message)
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Offline,
                            cloudSyncError = message
                        )
                    }
                }
        }
    }

    fun syncNotesToCloud() {
        if (_state.value.cloudSyncStatus == CloudSyncStatus.Syncing) return
        if (!_state.value.cloudAccount.isGoogleAccount) {
            _state.update {
                it.copy(pendingCloudSyncEvent = CloudSyncEvent.SignInRequired)
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    cloudSyncStatus = CloudSyncStatus.Syncing,
                    cloudSyncError = null
                )
            }
            firebaseNoteSync.uploadAllNotes()
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Synced,
                            cloudSyncedNoteCount = count,
                            cloudSyncError = null,
                            pendingCloudSyncEvent = CloudSyncEvent.Uploaded(count)
                        )
                    }
                }
                .onFailure { error ->
                    val message = firebaseSessionManager.diagnose(error)
                    Log.w(TAG, message, error)
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Error,
                            cloudSyncError = message,
                            pendingCloudSyncEvent = CloudSyncEvent.Failure(message)
                        )
                    }
                }
        }
    }

    fun downloadNotesFromCloud() {
        if (_state.value.cloudSyncStatus == CloudSyncStatus.Syncing) return
        if (!_state.value.cloudAccount.isGoogleAccount) {
            _state.update {
                it.copy(pendingCloudSyncEvent = CloudSyncEvent.SignInRequired)
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    cloudSyncStatus = CloudSyncStatus.Syncing,
                    cloudSyncError = null
                )
            }
            firebaseNoteSync.downloadAllNotes()
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Synced,
                            cloudSyncedNoteCount = count,
                            cloudSyncError = null,
                            listRevision = it.listRevision + 1,
                            pendingCloudSyncEvent = CloudSyncEvent.Downloaded(count)
                        )
                    }
                }
                .onFailure { error ->
                    val message = firebaseSessionManager.diagnose(error)
                    Log.w(TAG, message, error)
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Error,
                            cloudSyncError = message,
                            pendingCloudSyncEvent = CloudSyncEvent.Failure(message)
                        )
                    }
                }
        }
    }

    fun clearPendingCloudSyncEvent() {
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

    private fun uploadAllNotesSilently() {
        viewModelScope.launch {
            firebaseNoteSync.uploadAllNotes()
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            cloudSyncStatus = CloudSyncStatus.Synced,
                            cloudSyncedNoteCount = count,
                            cloudSyncError = null
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, firebaseSessionManager.diagnose(error), error)
                }
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
            .onEach { enabled ->
                _state.update { it.copy(isAppLockEnabled = enabled) }
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

        settingsRepository.useMonochromeTheme
            .onEach { enabled ->
                _state.update { it.copy(useMonochromeTheme = enabled) }
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
        // Runs on the caller's dispatcher (no withContext hop) so concurrent
        // triggers (search/color/label/sort each launch independently) can't
        // race and let a stale computation overwrite a newer one.
        viewModelScope.launch {
            val s = _state.value
            val filtered = s.notes.filter { note ->
                val noteId = note.id
                if (noteId != null && noteId in pendingHiddenIds) return@filter false

                val matchesSearch = s.searchQuery.isEmpty() || (
                    !note.isLocked && (
                        note.title.contains(s.searchQuery, ignoreCase = true) ||
                        note.content.contains(s.searchQuery, ignoreCase = true) ||
                        note.checklist.any { it.text.contains(s.searchQuery, ignoreCase = true) } ||
                        note.labels.any { it.name.contains(s.searchQuery, ignoreCase = true) }
                    )
                )

                val matchesColor = s.selectedColor == null || noteColorsMatch(note.color, s.selectedColor)

                val matchesLabel = s.selectedLabelId == null ||
                    note.labels.any { it.id == s.selectedLabelId }

                matchesSearch && matchesColor && matchesLabel
            }
            val sorted = when (s.sortOrder) {
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
            _state.update { it.copy(filteredNotes = sorted) }
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
                _state.update { it.copy(notes = notes) }
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

    fun setCloudAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCloudAutoSyncEnabled(enabled)
        }
    }

    private fun syncNoteToCloud(noteId: Long) {
        cloudNoteSyncCoordinator.scheduleUpload(noteId)
    }

    private fun deleteNoteFromCloud(noteId: Long) {
        noteSyncStateStore.markDeleted(noteId)
        cloudNoteSyncCoordinator.scheduleDelete(noteId)
    }

    fun setUseMonochromeTheme(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUseMonochromeTheme(enabled)
        }
    }

    fun setTrueDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTrueDarkMode(enabled)
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
            repository.updateNote(note.copy(isArchived = true, isTrashed = false))
            syncNoteToCloud(noteId)
        }
    }

    fun trashNote(note: Note) {
        val noteId = note.id ?: return
        viewModelScope.launch {
            if (_state.value.currentFilter == NoteFilter.TRASHED) {
                pendingUndo = PendingUndo(listOf(note), UndoAction.PERMANENT_DELETE)
                hideNoteTemporarily(noteId)
                repository.deleteNote(note)
                deleteNoteFromCloud(noteId)
            } else {
                pendingUndo = PendingUndo(listOf(note), UndoAction.TRASH)
                hideNoteTemporarily(noteId)
                repository.updateNote(note.copy(isTrashed = true, isArchived = false))
                syncNoteToCloud(noteId)
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
                repository.deleteNote(note)
                note.id?.let { deleteNoteFromCloud(it) }
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
                val noteId = note.id
                if (_state.value.currentFilter == NoteFilter.TRASHED) {
                    repository.deleteNote(note)
                    noteId?.let { deleteNoteFromCloud(it) }
                } else {
                    repository.updateNote(note.copy(isTrashed = true, isArchived = false))
                    noteId?.let { syncNoteToCloud(it) }
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
                repository.updateNote(note.copy(isArchived = true, isTrashed = false))
                note.id?.let { syncNoteToCloud(it) }
            }
            clearSelection()
        }
    }

    fun restoreSelectedNotes() {
        viewModelScope.launch {
            val notesToRestore = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            notesToRestore.forEach { note ->
                repository.updateNote(note.copy(isArchived = false, isTrashed = false))
                note.id?.let { syncNoteToCloud(it) }
            }
            clearSelection()
        }
    }

    fun setSelectedNotesPinned(pin: Boolean) {
        viewModelScope.launch {
            val notesToUpdate = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            notesToUpdate.forEach { note ->
                repository.updateNote(note.copy(isPinned = pin))
                note.id?.let { syncNoteToCloud(it) }
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
                        repository.updateNote(note)
                        note.id?.let { syncNoteToCloud(it) }
                    }
                }
                UndoAction.PERMANENT_DELETE -> {
                    undo.notes.forEach { note ->
                        val restoredId = repository.insertNoteWithResult(note)
                        syncNoteToCloud(restoredId)
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
            // Only the notes whose position actually changed need a cloud sync — matches
            // the same note.position != index check updateNotePositions uses for the DB
            // write, instead of fanning a sync out to every note on any reorder.
            notes.forEachIndexed { index, note ->
                val noteId = note.id ?: return@forEachIndexed
                if (note.position != index) {
                    syncNoteToCloud(noteId)
                }
            }
        }
    }

    suspend fun exportBackup(resolver: ContentResolver, uri: Uri): BackupExportResult {
        return try {
            val json = backupExporter.createJson()
            val wrote = resolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                true
            } ?: false
            if (wrote) BackupExportResult.Success else BackupExportResult.WriteFailed
        } catch (error: Exception) {
            BackupExportResult.Error(error)
        }
    }

    suspend fun importBackup(resolver: ContentResolver, uri: Uri): BackupImportResult {
        return try {
            val json = resolver.openInputStream(uri)?.use { stream ->
                readBackupBytes(stream, NoteBackupImporter.MAX_BACKUP_CHARS)
                    .toString(Charsets.UTF_8)
            } ?: return BackupImportResult.ReadFailed
            val result = backupImporter.importFromJson(json)
            BackupImportResult.Success(result.notesImported, result.labelsCreated)
        } catch (error: IllegalArgumentException) {
            BackupImportResult.InvalidFormat(error.message ?: "Invalid backup")
        } catch (error: Exception) {
            BackupImportResult.Error(error)
        }
    }

    private fun readBackupBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (total + read > maxBytes) {
                throw IllegalArgumentException("Backup file is too large")
            }
            output.write(chunk, 0, read)
            total += read
        }
        return output.toByteArray()
    }
}
