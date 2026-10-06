package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Note

/**
 * In-memory [CloudNoteTransport] for testing [NoteSyncEngine].
 *
 * Every operation is synchronous (no real IO); record the calls so tests
 * can assert what the engine asked the transport to do.
 */
// Identity-bound as well, through the test-only adapter: its forwarding reaches the uid-keyed
// members below, so a double keeps its behaviour while answering the identity API the engine uses
// for every protected call. The adapter lives in commonTest precisely so that no production type can
// inherit that forwarding — the interface itself has no defaults (see IdentityBoundNoteTransport).
open class FakeCloudNoteTransport : TestIdentityBoundNoteTransportAdapter() {

    val notes = mutableMapOf<Long, CloudNoteRecord>()
    val tombstones = mutableMapOf<Long, Long>() // noteId -> deletedAt
    val deletedNoteIds = mutableListOf<Long>()
    val deletedTombstoneIds = mutableListOf<Long>()
    val syncMetaCalls = mutableListOf<Triple<String, Int, String>>() // uid, count, platform
    var deleteSyncMetaCalled = false

    data class DeleteAttempt(val noteId: Long, val baseRevision: Long)
    val deleteAttempts = mutableListOf<DeleteAttempt>()
    var deleteNoteResult: CloudNoteTransport.DeleteResult = CloudNoteTransport.DeleteResult.Success

    // Configurable: the server timestamp to assign to every write
    var nextServerTimestamp: Long = 100_000L
    var nextServerRevision: Long = 1L

    /**
     * When set, [deleteTombstones] throws instead of deleting. Models the offline / expired-token
     * case that strands a cloud tombstone after a restore.
     */
    var deleteTombstonesFailure: Throwable? = null
    var deleteNotesFailure: Throwable? = null
    var restoreNoteFailure: Throwable? = null
    var restoreNoteCalls = 0

    /**
     * When set, [fetchNotesSnapshot] drops this many records from the end of the snapshot while
     * still reporting the full count. Models the payload a truncated aggregate or an unparseable
     * row produces: a non-empty library that is quietly missing notes.
     */
    var truncateSnapshotBy: Int = 0

    /**
     * When false, the fake reports no authoritative count — the legacy transport shape, where the
     * engine has only the records to go on.
     */
    var reportsAuthoritativeCount: Boolean = true

    var fetchNotesSnapshotCalls = 0

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> =
        notes.values.toList()

    override suspend fun fetchNotesSnapshot(uid: String): CloudNoteSnapshot {
        fetchNotesSnapshotCalls++
        val all = notes.values.toList()
        val delivered = all.dropLast(truncateSnapshotBy.coerceIn(0, all.size))
        return CloudNoteSnapshot(
            records = delivered,
            authoritativeNoteCount = if (reportsAuthoritativeCount) all.size else null,
        )
    }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? =
        notes[noteId]

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, CloudNoteTransport.PutResult> {
        return notes.mapNotNull { note ->
            val noteId = note.id ?: return@mapNotNull null
            this.notes[noteId] = CloudNoteRecord(
                noteId = noteId,
                serverUpdatedAt = nextServerTimestamp,
                clientTimestamp = note.timestamp,
                title = note.title,
                content = note.content,
                timestamp = note.timestamp,
                color = note.color,
                isPinned = note.isPinned,
                isArchived = note.isArchived,
                isTrashed = note.isTrashed,
                position = note.position,
                reminderTimestamp = note.reminderTimestamp,
                labels = note.labels.map { it.name },
                checklistItems = note.checklist.map { item ->
                    ChecklistItemData(
                        text = item.text,
                        isChecked = item.isChecked,
                        position = item.position
                    )
                }
            , revision = nextServerRevision)
            noteId to CloudNoteTransport.PutResult(nextServerRevision, nextServerTimestamp)
        }.toMap()
    }

    override suspend fun deleteNote(uid: String, noteId: Long, baseRevision: Long): CloudNoteTransport.DeleteResult {
        deleteNotesFailure?.let { throw it }
        deleteAttempts.add(DeleteAttempt(noteId, baseRevision))
        if (deleteNoteResult == CloudNoteTransport.DeleteResult.Success) {
            notes.remove(noteId)
            deletedNoteIds.add(noteId)
        }
        return deleteNoteResult
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        deleteNotesFailure?.let { throw it }
        deletedNoteIds.addAll(noteIds)
        noteIds.forEach { notes.remove(it) }
    }

    override suspend fun restoreNote(uid: String, note: Note): Map<Long, CloudNoteTransport.PutResult> {
        restoreNoteCalls++
        restoreNoteFailure?.let { throw it }
        deleteTombstonesFailure?.let { throw it }
        val noteId = note.id ?: return emptyMap()
        tombstones.remove(noteId)
        deletedTombstoneIds.add(noteId)
        return putNotes(uid, listOf(note))
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> =
        tombstones.toMap()

    override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) {
        tombstones[noteId] = deletedAt
    }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) {
        deleteTombstonesFailure?.let { throw it }
        deletedTombstoneIds.addAll(noteIds)
        noteIds.forEach { tombstones.remove(it) }
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        syncMetaCalls.add(Triple(uid, noteCount, platform))
    }

    override suspend fun deleteSyncMeta(uid: String) {
        deleteSyncMetaCalled = true
    }
}
