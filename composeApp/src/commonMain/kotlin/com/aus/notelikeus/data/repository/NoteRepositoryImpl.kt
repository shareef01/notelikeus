package com.aus.notelikeus.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.*
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.domain.platform.ScheduleOutcome
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val WIDGET_REFRESH_DEBOUNCE_MS = 250L

class NoteRepositoryImpl(
    private val database: NotelikeusDatabase,
    private val noteDao: NoteDao,
    private val labelDao: LabelDao,
    private val reminderManager: ReminderManager,
    private val widgetManager: PlatformWidgetManager,
    private val syncCoordinator: SyncCoordinator,
    private val ioDispatcher: CoroutineDispatcher
) : NoteRepository {

    private val widgetScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val widgetRefreshLock = Any()
    private var widgetRefreshJob: Job? = null

    /**
     * Runs [block] against the writer connection inside a real transaction.
     *
     * `useWriterConnection` on its own only checks out the connection — it does not begin a
     * transaction — so every multi-statement write has to go through here. Without the
     * `immediateTransaction`, a failure part-way through e.g. [updateNote] would leave the note
     * with its label/checklist rows deleted and not yet re-inserted.
     */
    override suspend fun <R> withWriteTransaction(block: suspend () -> R): R =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }

    private suspend fun <R> writeTransaction(block: suspend () -> R): R = withWriteTransaction(block)

    private fun refreshWidget() {
        synchronized(widgetRefreshLock) {
            widgetRefreshJob?.cancel()
            widgetRefreshJob = widgetScope.launch {
                delay(WIDGET_REFRESH_DEBOUNCE_MS)
                widgetManager.refreshWidgets()
            }
        }
    }

    override fun getActiveNotes(): Flow<List<Note>> {
        return noteDao.getActiveNotes().map { entities ->
            entities.map { it.toNote() }
        }
    }

    override fun getArchivedNotes(): Flow<List<Note>> {
        return noteDao.getArchivedNotes().map { entities ->
            entities.map { it.toNote() }
        }
    }

    override fun getTrashedNotes(): Flow<List<Note>> {
        return noteDao.getTrashedNotes().map { entities ->
            entities.map { it.toNote() }
        }
    }

    override suspend fun getNoteById(id: Long): Note? {
        return noteDao.getNoteById(id)?.toNote()
    }

    override suspend fun insertNote(note: Note) {
        insertNoteWithResult(note)
    }

    override suspend fun insertNoteWithResult(note: Note): Long {
        // Unfenced legacy path: no originating-identity check. Callers that can capture a token
        // should use the overload below; see D11 in docs/AUDIT_DEEPSEEK_2026-09-20.md.
        val noteId = innerInsert(note)
        syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, noteId)
        return noteId
    }

    override suspend fun insertNoteWithResult(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Long> {
        // The WHOLE synchronous local creation is the critical section, not just the Room write: a
        // stale insertion must not escape through its reminder/widget side effects either.
        // `bindAttachmentIds` keeps the generated-id binding of the denormalized attachment
        // metadata inside the same transaction and the same gate acquisition, which is what lets
        // the editor drop its second, ungated rewrite after a successful insert.
        val committed = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
            innerInsert(note, bindAttachmentIds = true)
        }
        // The cloud upload is scheduled *outside* the gate — a durable queue write or a WorkManager
        // enqueue inside it would let account isolation wait on disk I/O — with the token the
        // insertion was authorized under, so the scheduler can refuse it if the dataset moved on in
        // between. The local row itself stays fenced either way.
        if (committed is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied) {
            syncCoordinator.scheduleUploadFromAction(committed.value, commitToken)
        }
        return committed
    }

    override suspend fun insertNoteWithoutSync(note: Note): Long = insertNoteRows(note)

    override suspend fun finalizeImportedNotes(ids: List<Long>) {
        for (id in ids) {
            getNoteById(id)?.let { syncReminderForNote(it) }
            syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, id)
        }
        if (ids.isNotEmpty()) refreshWidget()
    }

    override suspend fun finalizeImportedNotes(
        ids: List<Long>,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit> {
        // One gate acquisition per note, not one for the whole import: holding the gate across the
        // import would block account isolation for as long as the file takes, and the point is only
        // that each note's upload is decided in the dataset that asked for it.
        for (id in ids) {
            val loaded = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
                noteDao.getNoteById(id)
            }
            when (loaded) {
                is com.aus.notelikeus.domain.repository.LocalCommitResult.StaleGeneration ->
                    return com.aus.notelikeus.domain.repository.LocalCommitResult.StaleGeneration
                is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied -> {
                    val entity = loaded.value ?: continue
                    syncReminderForNote(entity.toNote())
                    // Refused here means the import's dataset is gone; the caller reports the import
                    // as superseded rather than as a failure, and nothing further is queued.
                    if (
                        syncCoordinator.scheduleUploadFromAction(id, commitToken) ==
                        ScheduleOutcome.RefusedStaleOrigin
                    ) {
                        return com.aus.notelikeus.domain.repository.LocalCommitResult.StaleGeneration
                    }
                }
            }
        }
        if (ids.isNotEmpty()) refreshWidget()
        return com.aus.notelikeus.domain.repository.LocalCommitResult.Applied(Unit)
    }

    override suspend fun restoreNote(note: Note): Long {
        // Unfenced legacy path: no originating-identity check, so the queue can only be asked for a
        // command stamped with whatever dataset is current. See D11/R15 in
        // docs/AUDIT_DEEPSEEK_2026-09-20.md.
        val noteId = innerInsert(note)
        syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.RESTORE, noteId)
        return noteId
    }

    override suspend fun restoreNote(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Long> {
        // The WHOLE logical restore is the critical section, exactly as for the fenced update above:
        // the re-insert and its reminder/widget effects belong to the dataset that asked for them,
        // and a refusal must leave none of them behind. The cloud restore is scheduled after the
        // gate is released, carrying this token, so a boundary that lands in between refuses it.
        val committed = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
            innerInsert(note)
        }
        if (committed is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied) {
            syncCoordinator.scheduleRestoreFromAction(committed.value, commitToken)
        }
        return committed
    }

    private suspend fun insertNoteRows(note: Note, bindAttachmentIds: Boolean = false): Long {
        val insertedId = noteDao.insertNote(note.toNoteEntity())

        noteDao.deleteNoteLabelCrossRefs(insertedId)
        note.labels.forEach { label ->
            label.id?.let { labelId ->
                noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(insertedId, labelId))
            }
        }

        noteDao.deleteChecklistItems(insertedId)
        note.checklist.forEach { item ->
            noteDao.insertChecklistItem(item.toChecklistItemEntity(insertedId))
        }

        // A new note's attachments are denormalized into the row as JSON and cannot name their
        // owning note before the insert has issued an id, so the generated id is bound here rather
        // than by a second write afterwards. Two reasons: the bind stays inside the same
        // transaction as the row it belongs to, and it stays inside the caller's account-generation
        // fence when there is one — see the token-aware insertNoteWithResult overload.
        if (bindAttachmentIds) {
            val bound = note.attachments.map { it.copy(noteId = insertedId) }
            if (bound != note.attachments) {
                noteDao.updateNote(note.copy(id = insertedId, attachments = bound).toNoteEntity())
            }
        }

        return insertedId
    }

    private suspend fun innerInsert(note: Note, bindAttachmentIds: Boolean = false): Long {
        val noteId = writeTransaction { insertNoteRows(note, bindAttachmentIds) }
        syncReminderForNote(note.copy(id = noteId))
        refreshWidget()
        return noteId
    }

    /**
     * Updates a note without letting the caller silently clear its sync stamp.
     *
     * `serverUpdatedAt` is written by the sync engine, never by the UI, and nothing in the editor
     * carries it: `EditorState` has no such field, so `buildNoteFromState` produces a Note with it
     * defaulting to null. Room's @Update rewrites the whole row, so every save from the editor
     * used to blank the column.
     *
     * That is not a cosmetic loss. `NoteSyncEngine.cloudWinsConflict` reads "remote has a server
     * stamp, local has none" as the cloud holding the only confirmed revision and returns true
     * outright — so the very next sync overwrote the note the user had just edited with the stale
     * cloud copy. Editing an already-synced note reverted it, every time.
     *
     * A null stamp on an existing row therefore means "the caller does not know", not "clear it".
     * The sync engine always passes a real value when it means to move the stamp forward.
     */
    override suspend fun updateNote(note: Note) {
        // Unfenced legacy path: no originating-identity check. Callers that can capture a token
        // should use the overload below; see D11 in docs/AUDIT_DEEPSEEK_2026-09-20.md.
        applyExistingNoteUpdate(note)
    }

    override suspend fun updateNote(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit> {
        // The WHOLE logical update is the critical section, not just the Room write: a stale
        // mutation must not escape through its reminder/widget side effects either. The token is
        // validated while the gate is held, so an isolation that lands first turns this into a
        // refusal rather than a late write into the new account's dataset. The upload is scheduled
        // after the gate is released — see the fenced insert above — under the same token.
        val committed = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
            applyExistingNoteUpdateLocally(note)
        }
        if (committed is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied) {
            note.id?.let { syncCoordinator.scheduleUploadFromAction(it, commitToken) }
        }
        return committed
    }

    /** The unfenced path's whole effect: the local save plus a command from the current dataset. */
    private suspend fun applyExistingNoteUpdate(note: Note) {
        applyExistingNoteUpdateLocally(note)
        note.id?.let { syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, it) }
    }

    /**
     * The whole device-local effect of saving an existing note, minus telling the cloud.
     *
     * One body for the token-aware and the legacy path so the two cannot drift apart — the fence's
     * value is that it wraps *this*, exactly — and the cloud command is the one step that must sit
     * outside the gate, so it is the one step left to the two callers.
     */
    private suspend fun applyExistingNoteUpdateLocally(note: Note) {
        val noteId = note.id ?: return
        writeTransaction {
            val entity = note.toNoteEntity()
            val preserved = if (entity.serverUpdatedAt == null) {
                noteDao.getNoteById(noteId)?.note?.serverUpdatedAt
            } else {
                null
            }
            noteDao.updateNote(
                if (preserved != null) entity.copy(serverUpdatedAt = preserved) else entity
            )

            // Handle labels
            noteDao.deleteNoteLabelCrossRefs(noteId)
            note.labels.forEach { label ->
                label.id?.let { labelId ->
                    noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(noteId, labelId))
                }
            }

            // Handle checklists
            noteDao.deleteChecklistItems(noteId)
            note.checklist.forEach { item ->
                noteDao.insertChecklistItem(item.toChecklistItemEntity(noteId))
            }
        }
        syncReminderForNote(note)
        refreshWidget()
    }

    override suspend fun updateNotePositions(notes: List<Note>) {
        // Unfenced legacy path: no originating-identity check, so the uploads take the current
        // dataset. Account-bound callers use the overload below.
        writeNotePositions(notes)
            .forEach { syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, it) }
        refreshWidget()
    }

    override suspend fun updateNotePositions(
        notes: List<Note>,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit> {
        // The reorder and the list of notes it moved are one contiguous effect, so the transaction
        // runs inside the gate and the uploads are scheduled after it — carrying this token, so a
        // boundary that lands in between refuses them instead of stamping the old drag with the
        // replacement dataset's epoch.
        val committed = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
            writeNotePositions(notes)
        }
        if (committed is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied) {
            committed.value.forEach { syncCoordinator.scheduleUploadFromAction(it, commitToken) }
            refreshWidget()
        }
        return commitOutcomeOf(committed)
    }

    /**
     * Applies the new manual order, returning the notes whose position actually changed.
     *
     * Scheduling happens after this returns, so a rolled-back reorder never enqueues an upload of a
     * position the local DB never accepted.
     */
    private suspend fun writeNotePositions(notes: List<Note>): List<Long> = writeTransaction {
        val changed = mutableListOf<Long>()
        notes.forEachIndexed { index, note ->
            val noteId = note.id ?: return@forEachIndexed
            if (note.position != index) {
                // Bump the client timestamp so uploadNote's conflict guard lets the
                // new position through to the cloud (matches web commitNotePositions).
                noteDao.updateNotePosition(noteId, index, DateUtils.currentTimeMillis())
                changed += noteId
            }
        }
        changed
    }

    override suspend fun deleteNote(note: Note) {
        // Unfenced legacy path: no originating-identity check. Account-bound action callers use the
        // overload below; see D11/R15 in docs/AUDIT_DEEPSEEK_2026-09-20.md.
        applyExistingNoteDelete(note)
    }

    override suspend fun deleteNote(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit> {
        // The WHOLE logical delete is the critical section: the row removal, the label cross-refs and
        // the reminder cancellation all belong to the dataset that asked for them, and a refusal
        // leaves none of them behind. The cloud delete is scheduled after the gate is released,
        // carrying this token: it used to run *inside* the refused block, which did keep a stale
        // action from queueing a delete, but put a durable queue write under the gate.
        val committed = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
            applyExistingNoteDeleteLocally(note)
        }
        if (committed is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied) {
            note.id?.let { syncCoordinator.scheduleDeleteFromAction(it, commitToken) }
        }
        return committed
    }

    /** The unfenced path's whole effect: the local delete plus a command from the current dataset. */
    private suspend fun applyExistingNoteDelete(note: Note) {
        applyExistingNoteDeleteLocally(note)
        // After the local delete commits: if the transaction throws, we must not have already
        // told the cloud to drop a note that still exists on this device.
        note.id?.let { syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.DELETE, it) }
    }

    /**
     * The whole device-local effect of deleting a note, minus telling the cloud.
     *
     * One body for the token-aware and the legacy path so the two cannot drift apart — the fence's
     * value is that it wraps *this*, exactly.
     */
    private suspend fun applyExistingNoteDeleteLocally(note: Note) {
        note.id?.let { reminderManager.cancelReminder(it) }
        writeTransaction {
            note.id?.let {
                noteDao.deleteNoteLabelCrossRefs(it)
            }
            noteDao.deleteNote(note.toNoteEntity())
        }
        refreshWidget()
    }

    override suspend fun clearAllUserData() {
        val notes = noteDao.getAllNotesForBackup()
        notes.forEach { entity ->
            reminderManager.cancelReminder(entity.note.id)
        }
        syncCoordinator.clearPending()
        writeTransaction {
            noteDao.deleteAllChecklistItems()
            noteDao.deleteAllNoteLabelCrossRefs()
            noteDao.deleteAllNotes()
            labelDao.deleteAllLabels()
        }
        refreshWidget()
    }

    override suspend fun getNextNotePosition(): Int = noteDao.getNextNotePosition()

    override fun getActiveNoteCount(): Flow<Int> = noteDao.getActiveNoteCount()

    override suspend fun getNotesWithActiveReminders(now: Long): List<Note> {
        return noteDao.getNotesWithActiveReminders(now).map { it.toNote() }
    }

    override suspend fun getNotesWithMissedReminders(now: Long): List<Note> {
        return noteDao.getNotesWithMissedReminders(now).map { it.toNote() }
    }

    /**
     * Clears a reminder that has been dealt with, and tells the cloud.
     *
     * The upload is the point. The cloud document still carries the old timestamp, and a note with
     * no pending change loses to the cloud on the next pull, so a purely local clear was undone
     * within seconds: the bell came back and the catch-up fired the same reminder again on every
     * later launch. Queuing an upload makes the cleared state the newer revision.
     */
    override suspend fun clearReminderTimestamp(noteId: Long) {
        noteDao.clearReminderTimestamp(noteId)
        refreshWidget()
        syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, noteId)
    }

    override suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long) {
        noteDao.updateServerTimestamp(noteId, serverUpdatedAt)
    }

    override suspend fun getAllNotesForBackup(): List<Note> {
        return noteDao.getAllNotesForBackup().map { it.toNote() }
    }

    override suspend fun getCloudEligibleNoteCount(): Int = noteDao.getCloudEligibleNoteCount()

    override suspend fun getAllLabelsSnapshot(): List<Label> {
        return labelDao.getAllLabelsOnce().map { it.toLabel() }
    }

    override fun getLabels(): Flow<List<Label>> {
        return labelDao.getAllLabels().map { entities ->
            entities.map { it.toLabel() }
        }
    }

    override suspend fun insertLabel(label: Label): Long {
        val id = labelDao.insertLabel(label.toLabelEntity())
        refreshWidget()
        return id
    }

    override suspend fun updateLabel(label: Label) {
        // Unfenced legacy path: no originating-identity check, so the fan-out uploads take the
        // current dataset. Account-bound callers use the overload below.
        writeLabelRename(label)
        readNotesLabelled(label)
            .forEach { syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, it) }
        refreshWidget()
    }

    override suspend fun updateLabel(
        label: Label,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit> {
        // The rename and the cross-references that decide which notes it affects are read and written
        // in one critical section; the uploads they imply are scheduled after the gate is released,
        // under this token. A rename that lost a race with account isolation therefore queues
        // nothing at all rather than queueing the replacement dataset's notes.
        val committed = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
            writeLabelRename(label)
            readNotesLabelled(label)
        }
        if (committed is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied) {
            committed.value.forEach { syncCoordinator.scheduleUploadFromAction(it, commitToken) }
            refreshWidget()
        }
        return commitOutcomeOf(committed)
    }

    override suspend fun deleteLabel(label: Label) {
        // Unfenced legacy path: see updateLabel.
        deleteLabelLocally(label)
            .forEach { syncCoordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, it) }
        refreshWidget()
    }

    override suspend fun deleteLabel(
        label: Label,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit> {
        val committed = com.aus.notelikeus.data.sync.LocalCommitGate.commit(commitToken) {
            deleteLabelLocally(label)
        }
        if (committed is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied) {
            committed.value.forEach { syncCoordinator.scheduleUploadFromAction(it, commitToken) }
            refreshWidget()
        }
        return commitOutcomeOf(committed)
    }

    /**
     * Narrows a committed multi-note outcome to the `Unit` these fan-out APIs report.
     *
     * A rename, a delete and a reorder all end by answering the same question — did the local
     * mutation belong to a dataset that still exists — so they share one mapping instead of three
     * line-for-line copies of it.
     */
    private fun commitOutcomeOf(
        committed: com.aus.notelikeus.domain.repository.LocalCommitResult<*>,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit> = when (committed) {
        is com.aus.notelikeus.domain.repository.LocalCommitResult.Applied ->
            com.aus.notelikeus.domain.repository.LocalCommitResult.Applied(Unit)
        com.aus.notelikeus.domain.repository.LocalCommitResult.StaleGeneration ->
            com.aus.notelikeus.domain.repository.LocalCommitResult.StaleGeneration
    }

    private suspend fun writeLabelRename(label: Label) {
        labelDao.updateLabel(label.toLabelEntity())
    }

    private suspend fun readNotesLabelled(label: Label): List<Long> =
        label.id?.let { noteDao.getNoteIdsForLabel(it) }.orEmpty()

    /** Deletes the label and its cross-references, returning the notes that carried it. */
    private suspend fun deleteLabelLocally(label: Label): List<Long> {
        val affectedNoteIds = label.id?.let { noteDao.getNoteIdsForLabel(it) }.orEmpty()
        writeTransaction {
            label.id?.let { labelDao.deleteCrossRefsForLabel(it) }
            labelDao.deleteLabel(label.toLabelEntity())
        }
        // Captured before the cross-refs were removed, since afterwards the link is gone.
        return affectedNoteIds
    }

    private fun syncReminderForNote(note: Note) {
        val noteId = note.id ?: return
        val shouldCancel =
            note.isTrashed || note.isArchived || note.reminderTimestamp == null
        if (shouldCancel) {
            reminderManager.cancelReminder(noteId)
        } else {
            reminderManager.scheduleReminder(
                noteId = noteId,
                timestamp = note.reminderTimestamp
            )
        }
    }
}
