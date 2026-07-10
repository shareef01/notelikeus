package com.aus.notelikeus.ui.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LabelsState(
    val labels: List<Label> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class LabelsViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LabelsState())
    val state: StateFlow<LabelsState> = _state.asStateFlow()

    init {
        loadLabels()
    }

    private fun loadLabels() {
        repository.getLabels()
            .onEach { labels ->
                _state.update { it.copy(labels = labels, isLoading = false) }
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
