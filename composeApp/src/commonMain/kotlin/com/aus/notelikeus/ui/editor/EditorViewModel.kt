package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.ui.theme.NO_NOTE_COLOR
import com.aus.notelikeus.util.AppLog
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EditorState(
    val id: Long? = null,
    val title: String = "",
    val content: String = "",
    val contentValue: TextFieldValue = TextFieldValue(""),
    val color: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val reminderTimestamp: Long? = null,
    val labels: List<Label> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val timestamp: Long = DateUtils.currentTimeMillis(),
    val position: Int = 0,
    val isNoteLoaded: Boolean = false,
    val noteNotFound: Boolean = false,
    /** A save (autosave included) failed: the editor still holds the only copy of the edit. */
    val saveFailed: Boolean = false
)

class EditorViewModel(
    private val repository: NoteRepository,
    private val reminderManager: ReminderManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var autosaveJob: Job? = null
    private val saveMutex = Mutex()
    private var noteId: Long? = savedStateHandle.get<Long>("noteId")?.takeIf { it != -1L }
    private var routedInitialColor: Int? =
        savedStateHandle.get<Int>("initialColor")?.takeIf { it != Int.MIN_VALUE }
    private var hasAppliedInitialColor = false

    /**
     * Which fields the user has authored, tracked per field.
     *
     * [loadSettingsAndNote] completes asynchronously — it waits on a settings read first — and
     * used to finish by *replacing* the whole state, so anything typed while that was in flight
     * was silently discarded, and the autosave behind it then persisted the blank over what was
     * already saved. The editor auto-focuses the body of a new note, so typing straight away is
     * the normal way to use it, not an edge case.
     *
     * Per field rather than one flag: the load still has to supply everything the user has *not*
     * touched, or typing a body into an existing note before it finished loading would blank its
     * title instead.
     */
    private var titleEdited = false
    private var contentEdited = false
    private var checklistEdited = false
    private var colorEdited = false
    private val userHasEdited: Boolean
        get() = titleEdited || contentEdited || checklistEdited || colorEdited

    init {
        loadNote()
    }

    fun setNoteId(id: Long?) = setRouteArgs(id, routedInitialColor)

    /**
     * Applies the route's arguments.
     *
     * Compose Navigation does not populate [SavedStateHandle] on desktop, so NavGraph parses the
     * arguments itself and pushes them in here. Colour was previously read from the handle only,
     * which meant the colour picked before creating a note was silently dropped on desktop.
     */
    fun setRouteArgs(id: Long?, initialColor: Int?) {
        if (noteId == id && routedInitialColor == initialColor) return
        noteId = id
        routedInitialColor = initialColor
        loadNote()
    }

    private fun loadNote() {
        loadSettingsAndNote()
        loadLabels()
    }

    private fun loadSettingsAndNote() {
        viewModelScope.launch {
            // A new note carries no colour of its own. NO_NOTE_COLOR means "use the theme
            // surface", which follows the active theme for the life of the note.
            //
            // This used to store the *current theme's background* on the note instead — black on
            // OLED, #F0F0F0 everywhere else. That value is persisted and synced, so a note created
            // on Dark, Midnight or Forest was permanently near-white: noteColorForTheme only swaps
            // light/dark variants for palette entries and passes anything else through untouched,
            // so those notes rendered as white cards on a near-black background on every device,
            // and could not follow a later theme change.
            val initialColor = if (noteId == null) routedInitialColor ?: NO_NOTE_COLOR else NO_NOTE_COLOR

            if (noteId == null) {
                // Merge, never replace: this runs after the user may already have typed.
                _state.update { current ->
                    current.copy(
                        isNoteLoaded = true,
                        color = if (colorEdited) current.color else initialColor
                    )
                }
            } else {
                val id = noteId!!
                repository.getNoteById(id)?.let { note ->
                    _state.update { current ->
                        val loaded = EditorState(
                            id = note.id,
                            title = note.title,
                            content = note.content,
                            contentValue = TextFieldValue(note.content),
                            color = note.color,
                            isPinned = note.isPinned,
                            isArchived = note.isArchived,
                            isTrashed = note.isTrashed,
                            reminderTimestamp = note.reminderTimestamp,
                            labels = note.labels,
                            allLabels = current.allLabels, // Preserve loaded labels
                            checklist = note.checklist.sortedWith(compareBy({ it.isChecked }, { it.position })),
                            timestamp = note.timestamp,
                            position = note.position,
                            isNoteLoaded = true,
                        )
                        // The stored note still supplies every field the user has not touched, so
                        // the editor is fully populated either way; it simply cannot overwrite
                        // what they have already authored.
                        if (!userHasEdited) loaded else loaded.copy(
                            title = if (titleEdited) current.title else loaded.title,
                            content = if (contentEdited) current.content else loaded.content,
                            contentValue =
                                if (contentEdited) current.contentValue else loaded.contentValue,
                            checklist = if (checklistEdited) current.checklist else loaded.checklist,
                            color = if (colorEdited) current.color else loaded.color
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
        titleEdited = true
        _state.update { it.copy(title = title) }
        triggerAutosave()
    }

    fun onContentValueChange(value: TextFieldValue) {
        if (_state.value.checklist.isNotEmpty()) return
        val oldValue = _state.value.contentValue
        val result = SmartTextProcessor.process(value, oldValue)
        
        if (result.structureChanged) {
            convertContentToChecklist()
        } else {
            contentEdited = true
            _state.update { it.copy(contentValue = result.value, content = result.value.text) }
            triggerAutosave()
        }
    }

    fun onColorChange(color: Int) {
        colorEdited = true
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
                // Only offer the undo if the archive actually reached the database.
                if (persistNoteReportingFailure()) onArchived?.invoke(snapshot)
            } else {
                persistNoteReportingFailure()
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
            content = if (state.checklist.isEmpty()) state.content else "",
            timestamp = state.timestamp,
            color = state.color,
            isPinned = state.isPinned,
            isArchived = state.isArchived,
            isTrashed = state.isTrashed,
            position = state.position,
            reminderTimestamp = state.reminderTimestamp,
            labels = state.labels,
            attachments = emptyList(),
            checklist = state.checklist
        )
    }

    /**
     * Persists the current state, one save at a time.
     *
     * The mutex is what stops a new note being inserted twice. [triggerAutosave] only cancels its
     * own delay wrapper, not a [persistNote] already running inside the coroutine it launched, so
     * a direct save (pin, reminder, trash) firing alongside a due autosave could put two calls in
     * flight — both reading `state.id == null` and both inserting.
     */
    private suspend fun persistNote(): Long? = saveMutex.withLock {
        val currentState = _state.value
        if (currentState.title.isEmpty() && currentState.content.isEmpty() && currentState.checklist.isEmpty()) {
            return@withLock null
        }

        val position = if (currentState.id == null) {
            repository.getNextNotePosition()
        } else {
            currentState.position
        }
        val updatedTimestamp = DateUtils.currentTimeMillis()
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
        syncReminder(savedId, _state.value)
        return savedId
    }

    /**
     * [persistNote] for the fire-and-forget call sites, which have no caller to propagate to: the
     * exception used to escape into [viewModelScope] with the editor still showing the unsaved
     * text as if it were stored. Reports the failure into the state instead, and returns whether
     * the save landed.
     */
    private suspend fun persistNoteReportingFailure(): Boolean {
        return try {
            persistNote()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.warn(TAG, "Saving the note failed", error)
            _state.update { it.copy(saveFailed = true) }
            false
        }
    }

    fun clearSaveFailure() {
        if (_state.value.saveFailed) _state.update { it.copy(saveFailed = false) }
    }

    suspend fun undoArchive(snapshot: Note) {
        _state.update { it.copy(isArchived = false) }
        repository.updateNote(snapshot)
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

    fun updateChecklistItem(itemId: Long, text: String, isChecked: Boolean) {
        _state.update { currentState ->
            val newList = currentState.checklist.toMutableList()
            val index = newList.indexOfFirst { it.id == itemId }
            if (index in newList.indices) {
                newList[index] = newList[index].copy(text = text, isChecked = isChecked)
            }
            val sortedList = newList.sortedWith(compareBy({ it.isChecked }, { it.position }))
            currentState.copy(checklist = sortedList)
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
        checklistEdited = true
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
            delay(1000)
            // persistNote, not saveNote: saveNote cancels autosaveJob, which from in here would
            // mean cancelling the very coroutine about to do the work.
            persistNoteReportingFailure()
        }
    }

    fun saveNote() {
        val currentState = _state.value
        if (currentState.title.isEmpty() && currentState.content.isEmpty() && currentState.checklist.isEmpty()) return

        // Supersede any autosave still counting down, so this save is the only one in flight.
        autosaveJob?.cancel()
        viewModelScope.launch {
            persistNoteReportingFailure()
        }
    }

    /**
     * Suspending variant that completes only after the note has been persisted.
     * Use in navigation callbacks where the caller must not proceed until the save finishes.
     */
    suspend fun saveNoteAndAwait() {
        val currentState = _state.value
        if (currentState.title.isEmpty() && currentState.content.isEmpty() && currentState.checklist.isEmpty()) return
        autosaveJob?.cancel()
        persistNote()
    }

    private fun syncReminder(noteId: Long, state: EditorState) {
        if (state.isTrashed || state.isArchived || state.reminderTimestamp == null) {
            reminderManager.cancelReminder(noteId)
        } else {
            reminderManager.scheduleReminder(
                noteId = noteId,
                timestamp = state.reminderTimestamp!!
            )
        }
    }

    private companion object {
        const val TAG = "EditorViewModel"
    }
}
