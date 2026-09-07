package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.MAX_ATTACHMENT_BYTES
import com.aus.notelikeus.data.attachments.createAttachmentId
import com.aus.notelikeus.data.attachments.isPendingAttachment
import com.aus.notelikeus.data.attachments.isR2AttachmentsEnabled
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.domain.model.Attachment
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
    val attachments: List<Attachment> = emptyList(),
    val timestamp: Long = DateUtils.currentTimeMillis(),
    val position: Int = 0,
    val isNoteLoaded: Boolean = false,
    val noteNotFound: Boolean = false,
    /** A save (autosave included) failed: the editor still holds the only copy of the edit. */
    val saveFailed: Boolean = false,
    /**
     * The note is saved on this device, but an attachment has not reached the cloud yet. This is
     * a sync state, never a save failure — the bytes are staged locally and upload is retried.
     */
    val attachmentSyncPending: Boolean = false,
    /** Adding an attachment failed before it was referenced, so nothing was added to the note. */
    val attachmentStagingFailed: Boolean = false,
    /** Title or body was shortened to the Postgres / sync caps. */
    val truncatedToSyncLimit: Boolean = false,
)

class EditorViewModel(
    private val repository: NoteRepository,
    private val reminderManager: ReminderManager,
    private val savedStateHandle: SavedStateHandle,
    private val attachmentSync: AttachmentSyncService? = null,
    /**
     * Whether attachments are configured. Injectable so the attachment paths can be exercised
     * without a Worker URL baked into the build under test.
     */
    private val attachmentsEnabled: () -> Boolean = ::isR2AttachmentsEnabled,
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
    private var attachmentsEdited = false
    private val removedAttachments = mutableListOf<Attachment>()
    private val userHasEdited: Boolean
        get() = titleEdited || contentEdited || checklistEdited || colorEdited || attachmentsEdited

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

    fun setInitialContent(title: String?, content: String?) {
        if (noteId != null) return
        val t = title?.trim() ?: ""
        val c = content?.trim() ?: ""
        if (t.isEmpty() && c.isEmpty()) return
        if (t.isNotEmpty()) titleEdited = true
        if (c.isNotEmpty()) contentEdited = true
        _state.update { current ->
            current.copy(
                title = if (current.title.isEmpty()) t else current.title,
                content = if (current.content.isEmpty()) c else current.content,
                contentValue = if (current.content.isEmpty()) TextFieldValue(c) else current.contentValue
            )
        }
        triggerAutosave()
    }

    fun formatForSharing(): String {
        val s = _state.value
        val sb = StringBuilder()
        if (s.title.isNotBlank()) {
            sb.append(s.title.trim()).append("\n\n")
        }
        if (s.checklist.isNotEmpty()) {
            s.checklist.forEach { item ->
                val mark = if (item.isChecked) "[x]" else "[ ]"
                sb.append("- ").append(mark).append(" ").append(item.text).append("\n")
            }
        } else if (s.content.isNotBlank()) {
            sb.append(s.content.trim())
        }
        return sb.toString().trim()
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
                            attachments = note.attachments,
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
                            attachments = if (attachmentsEdited) current.attachments else loaded.attachments,
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
        val clamped = title.take(NoteBackupImporter.MAX_FIELD_CHARS)
        _state.update {
            it.copy(
                title = clamped,
                truncatedToSyncLimit = it.truncatedToSyncLimit || clamped.length < title.length,
            )
        }
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
            val clamped = clampTextField(result.value, NoteBackupImporter.MAX_CONTENT_CHARS)
            _state.update {
                it.copy(
                    contentValue = clamped,
                    content = clamped.text,
                    truncatedToSyncLimit = it.truncatedToSyncLimit ||
                        clamped.text.length < result.value.text.length,
                )
            }
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
            attachments = state.attachments,
            checklist = state.checklist
        )
    }

    fun isAttachmentsEnabled(): Boolean = attachmentsEnabled()

    /**
     * Stages the bytes durably, then references them from the note.
     *
     * The order matters: the note is autosaved with a `pending:` reference, and if the bytes only
     * ever lived in process memory that reference outlives them — the attachment survives a
     * restart as metadata pointing at nothing. Nothing is added to the note unless the bytes are
     * on disk.
     */
    fun addAttachment(bytes: ByteArray, mimeType: String) {
        if (!attachmentsEnabled()) return
        if (bytes.size > MAX_ATTACHMENT_BYTES) return
        if (!mimeType.startsWith("image/")) return
        val attachmentId = createAttachmentId()
        viewModelScope.launch {
            // No sync service means no durable staging, so there is nowhere for the bytes to live
            // — referencing them anyway is exactly the metadata-without-bytes case this avoids.
            val staged = attachmentSync?.let { sync ->
                runCatching {
                    sync.stageAttachment(attachmentId, _state.value.id, bytes, mimeType)
                }.getOrDefault(false)
            } ?: false
            if (!staged) {
                _state.update { it.copy(attachmentStagingFailed = true) }
                return@launch
            }
            attachmentsEdited = true
            val attachment = Attachment(
                id = attachmentId,
                noteId = _state.value.id ?: 0L,
                storagePath = pendingStoragePath(attachmentId),
                type = "image",
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
            )
            _state.update { it.copy(attachments = it.attachments + attachment) }
            triggerAutosave()
        }
    }

    fun removeAttachment(attachment: Attachment) {
        attachmentsEdited = true
        removedAttachments.add(attachment)
        _state.update { it.copy(attachments = it.attachments.filterNot { item -> item.id == attachment.id }) }
        // The user removed it deliberately, so the staged bytes are no longer the only copy of
        // anything they can still see.
        viewModelScope.launch {
            runCatching { attachmentSync?.releaseStagedAttachments(listOf(attachment)) }
        }
        triggerAutosave()
    }

    fun clearAttachmentStagingFailure() {
        if (_state.value.attachmentStagingFailed) {
            _state.update { it.copy(attachmentStagingFailed = false) }
        }
    }

    suspend fun loadAttachmentPreview(attachment: Attachment): ByteArray? {
        attachmentSync?.readAttachmentBytes(attachment)?.let { return it }
        return null
    }

    /**
     * Persists the current state locally, one save at a time.
     *
     * The mutex is what stops a new note being inserted twice. [triggerAutosave] only cancels its
     * own delay wrapper, not a [persistNote] already running inside the coroutine it launched, so
     * a direct save (pin, reminder, trash) firing alongside a due autosave could put two calls in
     * flight — both reading `state.id == null` and both inserting.
     *
     * Ordering is local-first and deliberate. The note reaches Room, the editor adopts the id Room
     * issued, and only then is anything attempted over the network. Uploading before the insert
     * had been adopted meant a failed upload threw past the state update: the editor still thought
     * the note was new, so the next save inserted it a second time, and on the update path the
     * user's text never reached Room at all because the write came after the upload.
     */
    private suspend fun persistNote(): Long? = saveMutex.withLock {
        val currentState = clampStateToSyncLimits(_state.value)
        if (currentState.title.isEmpty() &&
            currentState.content.isEmpty() &&
            currentState.checklist.isEmpty() &&
            currentState.attachments.isEmpty()
        ) {
            return@withLock null
        }

        val position = if (currentState.id == null) {
            repository.getNextNotePosition()
        } else {
            currentState.position
        }
        val updatedTimestamp = DateUtils.currentTimeMillis()
        var note = buildNoteFromState(currentState).copy(
            position = position,
            timestamp = updatedTimestamp,
        )
        val savedId = if (note.id == null) {
            val draftForInsert = note.copy(
                attachments = note.attachments.map { it.copy(noteId = 0L) },
            )
            val newId = repository.insertNoteWithResult(draftForInsert)
            note = note.copy(
                id = newId,
                attachments = note.attachments.map { it.copy(noteId = newId) },
            )
            // The note exists locally from here on. Adopt the id before anything can fail, so a
            // retry updates this row instead of inserting another one.
            _state.update {
                it.copy(
                    id = newId,
                    position = position,
                    timestamp = updatedTimestamp,
                    attachments = note.attachments,
                )
            }
            // Bind the generated id to the staged bytes and re-write the note with the bound
            // attachments, so the local record is coherent without waiting on R2.
            repository.updateNote(note)
            newId
        } else {
            note = note.copy(attachments = note.attachments.map { it.copy(noteId = note.id!!) })
            repository.updateNote(note)
            _state.update { it.copy(timestamp = updatedTimestamp, attachments = note.attachments) }
            note.id
        }

        // Local save has succeeded. Everything below is remote or best-effort cleanup, and must
        // not be able to turn this into a failed save.
        if (savedId != null) {
            runCatching { attachmentSync?.bindStagedAttachmentsToNote(savedId, note.attachments) }
            syncRemoteAttachments(note)
        }

        val noteIdForCleanup = savedId ?: note.id
        if (noteIdForCleanup != null && removedAttachments.isNotEmpty()) {
            runCatching {
                attachmentSync?.deleteAttachmentsForNote(noteIdForCleanup, removedAttachments.toList())
            }
            removedAttachments.clear()
        }
        syncReminder(savedId ?: return@withLock null, _state.value)
        return savedId
    }

    /**
     * Uploads pending attachment bytes and records the resulting R2 paths.
     *
     * Runs only after the note is durably local. A failure here means the image has not reached
     * the cloud yet — the note, and the staged bytes behind the attachment, are both still on the
     * device — so it sets the pending-sync flag rather than reporting a failed save.
     */
    private suspend fun syncRemoteAttachments(note: Note) {
        val sync = attachmentSync ?: return
        if (note.attachments.none { isPendingAttachment(it.storagePath) }) return
        try {
            val synced = sync.syncNoteAttachments(note)
            if (synced.attachments == note.attachments) {
                _state.update { it.copy(attachmentSyncPending = false) }
                return
            }
            repository.updateNote(synced)
            _state.update { current ->
                // Only adopt the uploaded paths if the editor is still on this note and the user
                // has not since changed the attachment set.
                if (current.id != synced.id) current
                else current.copy(attachments = synced.attachments, attachmentSyncPending = false)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.warn(TAG, "Attachment upload deferred; note is saved on this device", error)
            _state.update { it.copy(attachmentSyncPending = true) }
        }
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

    fun clearTruncationNotice() {
        if (_state.value.truncatedToSyncLimit) _state.update { it.copy(truncatedToSyncLimit = false) }
    }

    private fun clampTextField(value: TextFieldValue, maxChars: Int): TextFieldValue {
        if (value.text.length <= maxChars) return value
        val text = value.text.take(maxChars)
        val start = value.selection.start.coerceIn(0, text.length)
        val end = value.selection.end.coerceIn(0, text.length)
        return value.copy(text = text, selection = TextRange(start, end))
    }

    private fun clampStateToSyncLimits(state: EditorState): EditorState {
        val title = state.title.take(NoteBackupImporter.MAX_FIELD_CHARS)
        val contentValue = clampTextField(state.contentValue, NoteBackupImporter.MAX_CONTENT_CHARS)
        val truncated =
            title.length < state.title.length || contentValue.text.length < state.content.length
        if (!truncated) return state
        val next = state.copy(
            title = title,
            content = contentValue.text,
            contentValue = contentValue,
            truncatedToSyncLimit = true,
        )
        _state.value = next
        return next
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
        contentEdited = true
        _state.update { currentState ->
            val transformed = transform(currentState.contentValue)
            val updated = clampTextField(transformed, NoteBackupImporter.MAX_CONTENT_CHARS)
            currentState.copy(
                contentValue = updated,
                content = updated.text,
                truncatedToSyncLimit = currentState.truncatedToSyncLimit ||
                    updated.text.length < transformed.text.length,
            )
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

    /**
     * Local save with an unambiguous outcome, for callers that must decide whether it is safe to
     * leave the editor. Cancellation is rethrown rather than reported as a failed write: a
     * cancelled coroutine has not established that Room rejected anything.
     */
    suspend fun saveLocallyAndAwait(): LocalSaveResult {
        val currentState = _state.value
        if (currentState.title.isEmpty() &&
            currentState.content.isEmpty() &&
            currentState.checklist.isEmpty() &&
            currentState.attachments.isEmpty()
        ) {
            return LocalSaveResult.Unchanged
        }
        autosaveJob?.cancel()
        return try {
            val id = persistNote()
            if (id == null) {
                LocalSaveResult.Unchanged
            } else {
                if (_state.value.saveFailed) _state.update { it.copy(saveFailed = false) }
                LocalSaveResult.Saved(id)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.warn(TAG, "Saving the note failed", error)
            _state.update { it.copy(saveFailed = true) }
            LocalSaveResult.Failed(error)
        }
    }

    /**
     * Drops the edit the user chose not to keep, including any bytes staged for it that no saved
     * note references. Only reachable from an explicit discard.
     */
    suspend fun discardUnsavedChanges() {
        autosaveJob?.cancel()
        val staged = _state.value.attachments.filter { isPendingAttachment(it.storagePath) }
        if (staged.isNotEmpty()) {
            val savedNote = _state.value.id?.let { id -> runCatching { repository.getNoteById(id) }.getOrNull() }
            val stillReferenced = savedNote?.attachments.orEmpty().map { it.id }.toSet()
            val orphaned = staged.filterNot { it.id in stillReferenced }
            runCatching { attachmentSync?.releaseStagedAttachments(orphaned) }
        }
        _state.update { it.copy(saveFailed = false) }
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
