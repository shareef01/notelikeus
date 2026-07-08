package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.data.remote.ReminderScheduler
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.ui.theme.BackgroundLight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditorState(
    val id: Long? = null,
    val title: String = "",
    val content: String = "",
    val contentValue: TextFieldValue = TextFieldValue(""),
    val color: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val isLocked: Boolean = false,
    val reminderTimestamp: Long? = null,
    val labels: List<Label> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isNoteLoaded: Boolean = false,
    val isAccessGranted: Boolean = true
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val reminderScheduler: ReminderScheduler,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var autosaveJob: Job? = null
    private val noteId: Long? = savedStateHandle.get<Long>("noteId")?.takeIf { it != -1L }
    private val skipLockCheck: Boolean = savedStateHandle.get<Boolean>("skipLockCheck") ?: false

    init {
        _state.update { it.copy(color = BackgroundLight.toArgb()) }
        loadNote()
        loadLabels()
    }

    private fun loadNote() {
        if (noteId == null) {
            _state.update { it.copy(isNoteLoaded = true) }
            return
        }
        viewModelScope.launch {
            repository.getNoteById(noteId)?.let { note ->
                _state.update {
                    it.copy(
                        id = note.id,
                        title = note.title,
                        content = note.content,
                        contentValue = TextFieldValue(note.content),
                        color = note.color,
                        isPinned = note.isPinned,
                        isArchived = note.isArchived,
                        isTrashed = note.isTrashed,
                        isLocked = note.isLocked,
                        reminderTimestamp = note.reminderTimestamp,
                        labels = note.labels,
                        checklist = note.checklist.sortedWith(compareBy({ it.isChecked }, { it.position })),
                        attachments = note.attachments,
                        timestamp = note.timestamp,
                        isNoteLoaded = true,
                        isAccessGranted = !note.isLocked || skipLockCheck
                    )
                }
            } ?: run {
                _state.update { it.copy(isNoteLoaded = true) }
            }
        }
    }

    private fun loadLabels() {
        repository.getLabels()
            .onEach { labels ->
                _state.update { it.copy(allLabels = labels) }
            }
            .launchIn(viewModelScope)
    }

    fun onTitleChange(title: String) {
        _state.update { it.copy(title = title) }
        triggerAutosave()
    }

    fun onContentValueChange(value: TextFieldValue) {
        _state.update { it.copy(contentValue = value, content = value.text) }
        triggerAutosave()
    }

    fun onColorChange(color: Int) {
        _state.update { it.copy(color = color) }
        triggerAutosave()
    }

    fun togglePin() {
        _state.update { it.copy(isPinned = !it.isPinned) }
        triggerAutosave()
    }

    fun toggleArchive() {
        _state.update { it.copy(isArchived = !it.isArchived) }
        saveNote()
    }

    fun toggleTrash() {
        _state.update { it.copy(isTrashed = !it.isTrashed) }
        saveNote()
    }

    fun toggleLock() {
        _state.update { it.copy(isLocked = !it.isLocked) }
        saveNote()
    }

    fun setReminder(timestamp: Long?) {
        _state.update { it.copy(reminderTimestamp = timestamp) }
        saveNote()
    }

    fun toggleLabel(label: Label) {
        _state.update { currentState ->
            val newLabels = if (currentState.labels.any { it.id == label.id }) {
                currentState.labels.filter { it.id != label.id }
            } else {
                currentState.labels + label
            }
            currentState.copy(labels = newLabels)
        }
        triggerAutosave()
    }

    fun createLabel(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.allLabels.any { it.name.equals(trimmed, ignoreCase = true) }) return

        viewModelScope.launch {
            repository.insertLabel(Label(name = trimmed))
        }
    }

    fun grantAccess() {
        _state.update { it.copy(isAccessGranted = true) }
    }

    fun applyBoldToSelection() {
        applyFormatting { TextFormatting.wrapSelection(it, "**") }
    }

    fun applyItalicToSelection() {
        applyFormatting { TextFormatting.wrapSelection(it, "_") }
    }

    fun applyBulletListToSelection() {
        applyFormatting { TextFormatting.prefixLinesWithBullet(it) }
    }

    private fun applyFormatting(transform: (TextFieldValue) -> TextFieldValue) {
        _state.update { currentState ->
            val updated = transform(currentState.contentValue)
            currentState.copy(contentValue = updated, content = updated.text)
        }
        triggerAutosave()
    }

    fun updateChecklistItem(index: Int, text: String, isChecked: Boolean) {
        _state.update { currentState ->
            val newList = currentState.checklist.toMutableList()
            if (index in newList.indices) {
                newList[index] = newList[index].copy(text = text, isChecked = isChecked)
            }
            // Smart reordering: Checked items to bottom
            val sortedList = newList.sortedWith(compareBy({ it.isChecked }, { it.position }))
            currentState.copy(checklist = sortedList)
        }
        triggerAutosave()
    }

    fun addChecklistItem() {
        _state.update { currentState ->
            val newList = currentState.checklist.toMutableList()
            newList.add(ChecklistItem(text = "", isChecked = false, position = newList.size))
            currentState.copy(checklist = newList)
        }
        triggerAutosave()
    }

    fun removeChecklistItem(index: Int) {
        _state.update { currentState ->
            val newList = currentState.checklist.toMutableList()
            if (index in newList.indices) {
                newList.removeAt(index)
            }
            currentState.copy(checklist = newList)
        }
        triggerAutosave()
    }

    fun addAttachment(uri: String) {
        _state.update { currentState ->
            val currentId = currentState.id ?: -1L
            val newAttachment = Attachment(
                noteId = currentId,
                uri = uri,
                type = "image"
            )
            currentState.copy(attachments = currentState.attachments + newAttachment)
        }
        triggerAutosave()
    }

    private fun triggerAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(1000)
            saveNote()
        }
    }

    fun saveNote() {
        val currentState = _state.value
        if (currentState.title.isEmpty() && currentState.content.isEmpty() && currentState.checklist.isEmpty()) return

        viewModelScope.launch {
            val note = Note(
                id = currentState.id,
                title = currentState.title,
                content = currentState.content,
                timestamp = currentState.timestamp,
                color = currentState.color,
                isPinned = currentState.isPinned,
                isArchived = currentState.isArchived,
                isTrashed = currentState.isTrashed,
                position = 0, // Fallback position
                isLocked = currentState.isLocked,
                reminderTimestamp = currentState.reminderTimestamp,
                labels = currentState.labels,
                attachments = currentState.attachments,
                checklist = currentState.checklist
            )
            val savedId = if (note.id == null) {
                val newId = repository.insertNoteWithResult(note)
                _state.update { it.copy(id = newId) }
                newId
            } else {
                repository.updateNote(note)
                note.id
            }
            syncReminder(savedId, currentState)
        }
    }

    private fun syncReminder(noteId: Long, state: EditorState) {
        if (state.isTrashed || state.reminderTimestamp == null) {
            reminderScheduler.cancelReminder(noteId)
        } else {
            reminderScheduler.scheduleReminder(
                noteId = noteId,
                title = state.title,
                content = state.content,
                timestamp = state.reminderTimestamp
            )
        }
    }
}
