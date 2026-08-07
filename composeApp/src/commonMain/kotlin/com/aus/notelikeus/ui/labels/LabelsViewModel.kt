package com.aus.notelikeus.ui.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.repository.NoteRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LabelsState(
    val labels: List<Label> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class LabelsViewModel(
    private val repository: NoteRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LabelsState())
    val state: StateFlow<LabelsState> = _state.asStateFlow()

    init {
        loadLabels()
    }

    fun loadLabels() {
        repository.getLabels()
            .onEach { labels ->
                _state.update { it.copy(labels = labels, isLoading = false, error = null) }
            }
            .catch { e ->
                _state.update { it.copy(isLoading = false, error = "Failed to load labels") }
            }
            .launchIn(viewModelScope)
    }

    fun createLabel(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.insertLabel(Label(name = trimmed))
        }
    }

    fun updateLabel(label: Label, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == label.name) return
        viewModelScope.launch {
            repository.updateLabel(label.copy(name = trimmed))
        }
    }

    fun deleteLabel(label: Label) {
        viewModelScope.launch {
            repository.deleteLabel(label)
        }
    }
}
