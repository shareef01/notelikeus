package com.aus.notelikeus.ui.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.hasLabelNamed
import com.aus.notelikeus.domain.model.labelNoteCounts
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.aus.notelikeus.domain.repository.NoteRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LabelsState(
    val labels: List<Label> = emptyList(),
    /** Notes carrying each label, by label id: active and archived notes, not the trash. */
    val noteCounts: Map<Long, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false
)

class LabelsViewModel(
    private val repository: NoteRepository,
    /**
     * Supplied by the composition, never by the caller: a rename's uploads belong to the dataset the
     * user made it in, and capturing here — on the action, before the coroutine is launched — is
     * what makes a boundary during the write refuse them instead of queueing the replacement
     * dataset's notes under the replacement dataset's epoch.
     */
    private val localCommitTokenProvider: LocalCommitTokenProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(LabelsState())
    val state: StateFlow<LabelsState> = _state.asStateFlow()

    init {
        loadLabels()
    }

    private var loading: kotlinx.coroutines.Job? = null

    fun loadLabels() {
        loading?.cancel()
        // Archived notes count: deleting the label takes it off them too. Trashed notes don't.
        val notes = combine(repository.getActiveNotes(), repository.getArchivedNotes()) { active, archived ->
            active + archived
        }
        loading = combine(repository.getLabels(), notes) { labels, all -> labels to labelNoteCounts(all) }
            .onEach { (labels, counts) ->
                _state.update {
                    it.copy(labels = labels, noteCounts = counts, isLoading = false, loadFailed = false)
                }
            }
            .catch {
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
            .launchIn(viewModelScope)
    }

    fun createLabel(name: String) {
        val trimmed = name.trim()
        // The dialog already refuses a taken name; this keeps a second label of the same name out
        // even if a stale dialog submits one.
        if (trimmed.isEmpty() || _state.value.labels.hasLabelNamed(trimmed)) return
        viewModelScope.launch {
            repository.insertLabel(Label(name = trimmed))
        }
    }

    fun updateLabel(label: Label, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == label.name) return
        if (_state.value.labels.hasLabelNamed(trimmed, exceptId = label.id)) return
        // Captured synchronously on the user's own action, before the coroutine is launched.
        val commitToken = localCommitTokenProvider.capture()
        viewModelScope.launch {
            repository.updateLabel(label.copy(name = trimmed), commitToken)
        }
    }

    fun deleteLabel(label: Label) {
        // Captured synchronously on the user's own action, before the coroutine is launched.
        val commitToken = localCommitTokenProvider.capture()
        viewModelScope.launch {
            repository.deleteLabel(label, commitToken)
        }
    }
}
