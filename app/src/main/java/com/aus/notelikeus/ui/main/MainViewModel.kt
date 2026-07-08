package com.aus.notelikeus.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
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

data class MainState(
    val notes: List<Note> = emptyList(),
    val filteredNotes: List<Note> = emptyList(),
    val searchQuery: String = "",
    val selectedColor: Int? = null,
    val selectedLabelId: Long? = null,
    val isSearching: Boolean = false,
    val isListView: Boolean = false,
    val isTrueDarkMode: Boolean = false,
    val selectedNotes: Set<Long> = emptySet(),
    val currentFilter: NoteFilter = NoteFilter.ACTIVE,
    val allLabels: List<Label> = emptyList()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private var currentNotesJob: Job? = null

    init {
        setFilter(NoteFilter.ACTIVE)
        loadSettings()
        loadLabels()
        setupSearchOptimization()
    }

    private fun loadSettings() {
        settingsRepository.isTrueDarkMode
            .onEach { enabled ->
                _state.update { it.copy(isTrueDarkMode = enabled) }
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
            
        // Also trigger on color/label filter changes instantly
        _state
            .map { Triple(it.selectedColor, it.selectedLabelId, it.notes) }
            .distinctUntilChanged()
            .onEach { applyFilters() }
            .launchIn(viewModelScope)
    }

    private fun applyFilters() {
        val s = _state.value
        val filtered = s.notes.filter { note ->
            val matchesSearch = s.searchQuery.isEmpty() || 
                note.title.contains(s.searchQuery, ignoreCase = true) ||
                note.content.contains(s.searchQuery, ignoreCase = true)
            
            val matchesColor = s.selectedColor == null || note.color == s.selectedColor
            
            val matchesLabel = s.selectedLabelId == null || 
                note.labels.any { it.id == s.selectedLabelId }
            
            matchesSearch && matchesColor && matchesLabel
        }
        _state.update { it.copy(filteredNotes = filtered) }
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
                _state.update { it.copy(notes = notes) }
                applyFilters()
            }
            .launchIn(viewModelScope)
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

    fun toggleLayout() {
        _state.update { it.copy(isListView = !it.isListView) }
    }

    fun toggleSearch() {
        _state.update { 
            it.copy(
                isSearching = !it.isSearching, 
                searchQuery = "",
                selectedColor = null,
                selectedLabelId = null
            ) 
        }
    }

    fun setTrueDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTrueDarkMode(enabled)
        }
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
        viewModelScope.launch {
            repository.updateNote(note.copy(isArchived = true, isTrashed = false))
        }
    }

    fun trashNote(note: Note) {
        viewModelScope.launch {
            if (_state.value.currentFilter == NoteFilter.TRASHED) {
                repository.deleteNote(note)
            } else {
                repository.updateNote(note.copy(isTrashed = true, isArchived = false))
            }
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedNotes = emptySet()) }
    }

    fun deleteSelectedNotes() {
        viewModelScope.launch {
            val notesToDelete = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            notesToDelete.forEach { note ->
                if (_state.value.currentFilter == NoteFilter.TRASHED) {
                    repository.deleteNote(note)
                } else {
                    repository.updateNote(note.copy(isTrashed = true, isArchived = false))
                }
            }
            clearSelection()
        }
    }
    
    fun archiveSelectedNotes() {
        viewModelScope.launch {
            val notesToArchive = _state.value.notes.filter { it.id in _state.value.selectedNotes }
            notesToArchive.forEach { note ->
                repository.updateNote(note.copy(isArchived = true, isTrashed = false))
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

    fun onMoveNote(fromIndex: Int, toIndex: Int) {
        val currentNotes = _state.value.notes.toMutableList()
        if (fromIndex !in currentNotes.indices || toIndex !in currentNotes.indices) return
        
        val item = currentNotes.removeAt(fromIndex)
        currentNotes.add(toIndex, item)
        
        _state.update { it.copy(notes = currentNotes) }
        
        viewModelScope.launch {
            currentNotes.forEachIndexed { index, note ->
                repository.updateNote(note.copy(position = index))
            }
        }
    }
}
