package com.aus.notelikeus.domain.repository

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.Label
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun getActiveNotes(): Flow<List<Note>>
    fun getArchivedNotes(): Flow<List<Note>>
    fun getTrashedNotes(): Flow<List<Note>>
    suspend fun getNoteById(id: Long): Note?
    suspend fun insertNote(note: Note)
    suspend fun insertNoteWithResult(note: Note): Long
    /**
     * Creates [note] and binds the id it generated, refusing the whole synchronous local creation
     * when the local dataset has moved on since [commitToken] was captured.
     *
     * `insertNoteWithResult(note)` above is the **unfenced legacy path**: it checks no originating
     * identity, so a note drafted under one account and inserted after an account switch lands in
     * the new account's dataset — see D11 in docs/AUDIT_DEEPSEEK_2026-09-20.md.
     *
     * The returned value is the generated note id, exactly as the legacy overload returns it; the
     * fence only decides *whether* the creation happens. The repository owns the whole logical
     * creation under the token — the note row, its labels and checklist, the generated-id binding
     * of its attachment metadata, and its reminder/upload/widget effects — so a refusal leaves no
     * partial note behind.
     */
    suspend fun insertNoteWithResult(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Long>
    /**
     * Inserts [note] without enqueueing a cloud upload. Used inside [withWriteTransaction]
     * so a rolled-back import cannot schedule uploads for rows that never committed.
     */
    suspend fun insertNoteWithoutSync(note: Note): Long
    /**
     * Runs [block] in one writer transaction. Nested calls join the outer transaction.
     */
    suspend fun <R> withWriteTransaction(block: suspend () -> R): R
    /** Reminder + upload + widget refresh after a successful import transaction. */
    suspend fun finalizeImportedNotes(ids: List<Long>)

    /**
     * Finishes an import that began under [commitToken], refusing it when that dataset is gone.
     *
     * An import is a dataset mutation the user asked for, so its uploads carry the same originating
     * authority any other action's do: `finalizeImportedNotes(ids)` above stamps the dataset that
     * happens to be current when it runs, which for an import that was *interrupted by a
     * sign-out/sign-in of the same account* is the replacement dataset — exactly the stamping
     * R15.2 closes. The refusal is reported to the caller as
     * [com.aus.notelikeus.data.backup.BackupImportResult.Superseded] rather than swallowed.
     *
     * The rows themselves are inserted by [insertNoteWithoutSync], deliberately unfenced: it cannot
     * enqueue anything, and it runs inside the import's own write transaction, where taking the
     * account gate per row would invert a lock order that isolation also takes (gate → database).
     */
    suspend fun finalizeImportedNotes(
        ids: List<Long>,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit>
    /** Re-inserts a previously deleted note and ensures cloud tombstones are cleared. */
    suspend fun restoreNote(note: Note): Long
    /**
     * Re-inserts a previously deleted note, refusing the whole logical mutation when the local
     * dataset has moved on since [commitToken] was captured.
     *
     * `restoreNote(note)` above is the **unfenced legacy path**: it checks no originating identity,
     * so a restore delayed across a same-uid dataset replacement re-inserts the old note over
     * whatever the replacement dataset put at that id — see R15 in
     * docs/AUDIT_DEEPSEEK_2026-09-20.md. Account-bound action callers must use this overload.
     */
    suspend fun restoreNote(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Long>
    suspend fun updateNote(note: Note)
    /**
     * Updates [note], refusing the whole logical mutation when the local dataset has moved on since
     * [commitToken] was captured.
     *
     * `updateNote(note)` above is the **unfenced legacy path**: it checks no originating identity,
     * so work delayed across an account switch commits into the new account's row when the two
     * accounts reuse the same `noteId`. Prefer this overload wherever the caller can capture a
     * token — see D11 in docs/AUDIT_DEEPSEEK_2026-09-20.md.
     */
    suspend fun updateNote(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit>
    suspend fun updateNotePositions(notes: List<Note>)

    /**
     * Writes a new manual order and re-uploads the notes whose position changed, refusing the whole
     * reorder when the local dataset has moved on since [commitToken] was captured.
     *
     * `updateNotePositions(notes)` above is the **unfenced legacy path**; the drag that produced
     * [notes] happened in one dataset, and an upload scheduled after that dataset was replaced would
     * be stamped with the replacement's epoch and pushed against its colliding ids.
     */
    suspend fun updateNotePositions(
        notes: List<Note>,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit>
    suspend fun deleteNote(note: Note)
    /**
     * Deletes [note], refusing the whole logical mutation when the local dataset has moved on since
     * [commitToken] was captured.
     *
     * `deleteNote(note)` above is the **unfenced legacy path**: it checks no originating identity, so
     * a permanent delete delayed across a same-uid dataset replacement removes the *replacement*
     * dataset's row at the colliding id — see R15 in docs/AUDIT_DEEPSEEK_2026-09-20.md.
     * Account-bound action callers must use this overload.
     */
    suspend fun deleteNote(
        note: Note,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit>
    /** Wipes notes/labels/reminders for account switch. Does not touch the SQLCipher key. */
    suspend fun clearAllUserData()
    suspend fun getNextNotePosition(): Int
    suspend fun getAllNotesForBackup(): List<Note>
    suspend fun getCloudEligibleNoteCount(): Int
    suspend fun getAllLabelsSnapshot(): List<Label>
    suspend fun getNotesWithActiveReminders(now: Long): List<Note>
    suspend fun getNotesWithMissedReminders(now: Long): List<Note>
    suspend fun clearReminderTimestamp(noteId: Long)
    /** Refreshes only the cached sync-conflict clock — see Note.serverUpdatedAt. */
    suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long)
    fun getActiveNoteCount(): Flow<Int>

    fun getLabels(): Flow<List<Label>>
    suspend fun insertLabel(label: Label): Long
    suspend fun updateLabel(label: Label)

    /**
     * Renames [label] and re-uploads every note that carries it, refusing the whole mutation when the
     * local dataset has moved on since [commitToken] was captured.
     *
     * Labels are denormalized into each cloud note as `{name}`, so a rename has to re-upload every
     * affected note — a fan-out of account-owned work, decided from the cross-references this
     * mutation reads. `updateLabel(label)` above is the **unfenced legacy path**: it queues those
     * uploads against whatever dataset is current when it gets there, so a rename formed in one
     * dataset and scheduled after a same-uid boundary queued the replacement dataset's notes under
     * the replacement dataset's epoch.
     */
    suspend fun updateLabel(
        label: Label,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit>

    suspend fun deleteLabel(label: Label)

    /**
     * Deletes [label] and re-uploads the notes that carried it, refusing the whole mutation when the
     * local dataset has moved on since [commitToken] was captured. See [updateLabel].
     */
    suspend fun deleteLabel(
        label: Label,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): com.aus.notelikeus.domain.repository.LocalCommitResult<Unit>
}
