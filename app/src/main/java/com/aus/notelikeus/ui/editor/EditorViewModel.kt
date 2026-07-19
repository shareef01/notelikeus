package com.aus.notelikeus.ui.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.data.remote.CloudNoteSyncCoordinator
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.ui.theme.BackgroundLight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
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
    val timestamp: Long = System.currentTimeMillis(),
    val position: Int = 0,
    val isNoteLoaded: Boolean = false,
    val isAccessGranted: Boolean = true,
    val isSaving: Boolean = false,
    val noteNotFound: Boolean = false
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val cloudNoteSyncCoordinator: CloudNoteSyncCoordinator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val AUTOSAVE_DEBOUNCE_MS = 400L
    }

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var autosaveJob: Job? = null
    private val noteId: Long? = savedStateHandle.get<Long>("noteId")?.takeIf { it != -1L }
    private val routedInitialColor: Int? =
        savedStateHandle.get<Int>("initialColor")?.takeIf { it != Int.MIN_VALUE }
    private var hasAppliedInitialColor = false

    init {
        if (noteId == null) {
            _state.update { it.copy(isNoteLoaded = true) }
        }
        loadNote()
        loadDefaultColorForNewNote()
        loadLabels()
    }

    private fun loadNote() {
        val id = noteId ?: return
        viewModelScope.launch {
            // First, load the initial color based on theme to prevent blinking
            val theme = settingsRepository.appTheme.first()
            val isTrueDark = theme == AppTheme.TRUE_DARK ||
                (theme == AppTheme.AUTO && settingsRepository.isTrueDarkMode.first())

            val themeDefaultColor = if (isTrueDark) {
                Color.Black.toArgb()
            } else {
                BackgroundLight.toArgb()
            }
            val initialColor = if (noteId == null) {
                routedInitialColor ?: themeDefaultColor
            } else {
                themeDefaultColor
            }
            _state.update { it.copy(color = initialColor) }

            if (noteId == null) {
                _state.update { it.copy(isNoteLoaded = true) }
            } else {
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
                            timestamp = note.timestamp,
                            position = note.position,
                            isNoteLoaded = true,
                            isAccessGranted = !note.isLocked
                        )
                    }
                } ?: run {
                    _state.update { it.copy(isNoteLoaded = true, noteNotFound = true) }
                }
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

    fun setInitialNoteColor(color: Int) {
        if (hasAppliedInitialColor || _state.value.id != null || !_state.value.isNoteLoaded) return
        hasAppliedInitialColor = true
        _state.update { it.copy(color = color) }
    }

    fun onTitleChange(title: String) {
        _state.update { it.copy(title = title) }
        triggerAutosave()
    }

    fun onContentValueChange(value: TextFieldValue) {
        val oldValue = _state.value.contentValue
        val result = SmartTextProcessor.process(value, oldValue)
        
        if (result.structureChanged) {
            convertContentToChecklist()
        } else {
            _state.update { it.copy(contentValue = result.value, content = result.value.text) }
            triggerAutosave()
        }
    }

    fun onColorChange(color: Int) {
        _state.update { it.copy(color = color) }
        triggerAutosave()
    }

    fun togglePin() {
        _state.update { it.copy(isPinned = !it.isPinned) }
        triggerAutosave()
    }

    fun toggleArchive(onArchived: ((Note) -> Unit)? = null) {
        val wasArchived = _state.value.isArchived
        _state.update { it.copy(isArchived = !it.isArchived) }
        viewModelScope.launch {
            autosaveJob?.cancel()
            if (!wasArchived) {
                val snapshot = buildNoteFromState(_state.value).copy(isArchived = false)
                persistNote()
                onArchived?.invoke(snapshot)
            } else {
                persistNote()
            }
        }
    }

    fun toggleTrash() {
        _state.update { it.copy(isTrashed = !it.isTrashed) }
        saveNote()
    }

    suspend fun trashNoteForDelete(): Note? {
        autosaveJob?.cancel()
        val state = _state.value
        val snapshot = buildNoteFromState(state).copy(isTrashed = false)
        if (snapshot.title.isEmpty() && snapshot.content.isEmpty() && snapshot.checklist.isEmpty()) {
            return null
        }
        _state.update { it.copy(isTrashed = true) }
        persistNote()
        return snapshot
    }

    private fun buildNoteFromState(state: EditorState): Note {
        return Note(
            id = state.id,
            title = state.title,
            content = state.content,
            timestamp = state.timestamp,
            color = state.color,
            isPinned = state.isPinned,
            isArchived = state.isArchived,
            isTrashed = state.isTrashed,
            position = state.position,
            isLocked = state.isLocked,
            reminderTimestamp = state.reminderTimestamp,
            labels = state.labels,
            attachments = emptyList(),
            checklist = state.checklist
        )
    }

    private suspend fun persistNote(): Long? {
        val currentState = _state.value
        if (currentState.title.isEmpty() && currentState.content.isEmpty() && currentState.checklist.isEmpty()) {
            return null
        }

        val position = if (currentState.id == null) {
            repository.getNextNotePosition()
        } else {
            currentState.position
        }
        val updatedTimestamp = System.currentTimeMillis()
        val note = buildNoteFromState(currentState).copy(
            position = position,
            timestamp = updatedTimestamp
        )
        val savedId = if (note.id == null) {
            val newId = repository.insertNoteWithResult(note)
            _state.update { it.copy(id = newId, position = position, timestamp = updatedTimestamp) }
            newId
        } else {
            repository.updateNote(note)
            _state.update { it.copy(timestamp = updatedTimestamp) }
            note.id
        }
        savedId?.let { cloudNoteSyncCoordinator.scheduleUpload(it) }
        return savedId
    }

    suspend fun undoArchive(snapshot: Note) {
        _state.update { it.copy(isArchived = false) }
        repository.updateNote(snapshot)
    }

    fun toggleLock() {
        _state.update { it.copy(isLocked = !it.isLocked) }
        saveNote()
    }

    fun setReminder(timestamp: Long?) {
        _state.update { it.copy(reminderTimestamp = timestamp) }
        saveNote()
    }

    fun clearReminder() {
        _state.update { it.copy(reminderTimestamp = null) }
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
        val existing = _state.value.allLabels.find { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) {
            if (_state.value.labels.none { it.id == existing.id }) {
                toggleLabel(existing)
            }
            return
        }

        viewModelScope.launch {
            val id = repository.insertLabel(Label(name = trimmed))
            val newLabel = Label(id = id, name = trimmed)
            _state.update { currentState ->
                if (currentState.labels.any { it.id == id }) currentState
                else currentState.copy(labels = currentState.labels + newLabel)
            }
            triggerAutosave()
        }
    }

    fun grantAccess() {
        _state.update { it.copy(isAccessGranted = true) }
    }

    fun revokeAccessIfLocked() {
        if (_state.value.isLocked) {
            _state.update { it.copy(isAccessGranted = false) }
        }
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

    fun applyLinkToSelection(url: String) {
        applyFormatting { TextFormatting.wrapAsLink(it, url) }
    }

    private fun applyFormatting(transform: (TextFieldValue) -> TextFieldValue) {
        _state.update { currentState ->
            val updated = transform(currentState.contentValue)
            currentState.copy(contentValue = updated, content = updated.text)
        }
        triggerAutosave()
    }

    private var nextTempChecklistId = -1L

    private fun sortChecklistItems(items: List<ChecklistItem>): List<ChecklistItem> {
        return items
            .sortedWith(compareBy({ it.isChecked }, { it.position }))
            .mapIndexed { index, item -> item.copy(position = index) }
    }

    fun updateChecklistItem(itemId: Long, text: String, isChecked: Boolean) {
        _state.update { currentState ->
            val newList = currentState.checklist.toMutableList()
            val index = newList.indexOfFirst { it.id == itemId }
            if (index in newList.indices) {
                newList[index] = newList[index].copy(text = text, isChecked = isChecked)
            }
            currentState.copy(checklist = sortChecklistItems(newList))
        }
        triggerAutosave()
    }

    fun addChecklistItem() {
        _state.update { currentState ->
            val newList = currentState.checklist.toMutableList()
            val tempId = nextTempChecklistId--
            newList.add(
                ChecklistItem(
                    id = tempId,
                    text = "",
                    isChecked = false,
                    position = newList.size
                )
            )
            currentState.copy(checklist = newList)
        }
        triggerAutosave()
    }

    fun convertContentToChecklist() {
        _state.update { currentState ->
            if (currentState.checklist.isNotEmpty()) return@update currentState
            val lines = currentState.content.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val items = if (lines.isEmpty()) {
                listOf(
                    ChecklistItem(
                        id = nextTempChecklistId--,
                        text = "",
                        isChecked = false,
                        position = 0
                    )
                )
            } else {
                lines.mapIndexed { index, line ->
                    ChecklistItem(
                        id = nextTempChecklistId--,
                        text = line,
                        isChecked = false,
                        position = index
                    )
                }
            }
            currentState.copy(
                content = "",
                contentValue = TextFieldValue(""),
                checklist = items
            )
        }
        triggerAutosave()
    }

    fun convertChecklistToContent() {
        _state.update { currentState ->
            if (currentState.checklist.isEmpty()) return@update currentState
            val body = currentState.checklist.joinToString("\n") { it.text.trim() }
            currentState.copy(
                content = body,
                contentValue = TextFieldValue(body),
                checklist = emptyList()
            )
        }
        triggerAutosave()
    }

    fun removeChecklistItem(itemId: Long) {
        _state.update { currentState ->
            val newList = currentState.checklist.filterNot { it.id == itemId }
            currentState.copy(checklist = newList)
        }
        triggerAutosave()
    }

    private fun triggerAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            _state.update { it.copy(isSaving = true) }
            try {
                persistNote()
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    suspend fun flushPendingSave() {
        autosaveJob?.cancel()
        autosaveJob = null
        val currentState = _state.value
        if (currentState.title.isEmpty() && currentState.content.isEmpty() && currentState.checklist.isEmpty()) {
            return
        }
        _state.update { it.copy(isSaving = true) }
        try {
            persistNote()
        } finally {
            _state.update { it.copy(isSaving = false) }
        }
    }

    fun saveNote() {
        viewModelScope.launch {
            flushPendingSave()
        }
    }

    private fun syncReminder(noteId: Long, state: EditorState) {
        if (state.isTrashed || state.isArchived || state.isLocked || state.reminderTimestamp == null) {
            reminderScheduler.cancelReminder(noteId)
        } else {
            reminderScheduler.scheduleReminder(
                noteId = noteId,
                timestamp = state.reminderTimestamp
            )
        }
    }
}
