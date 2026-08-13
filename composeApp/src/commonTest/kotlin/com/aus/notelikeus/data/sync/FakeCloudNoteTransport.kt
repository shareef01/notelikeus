package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Note

/**
 * In-memory [CloudNoteTransport] for testing [NoteSyncEngine].
 *
 * Every operation is synchronous (no real IO); record the calls so tests
 * can assert what the engine asked the transport to do.
 */
class FakeCloudNoteTransport : CloudNoteTransport {

    val notes = mutableMapOf<Long, CloudNoteRecord>()
    val tombstones = mutableMapOf<Long, Long>() // noteId -> deletedAt
    val deletedNoteIds = mutableListOf<Long>()
    val deletedTombstoneIds = mutableListOf<Long>()
    val syncMetaCalls = mutableListOf<Triple<String, Int, String>>() // uid, count, platform
    var deleteSyncMetaCalled = false

    // Configurable: the server timestamp to assign to every write
    var nextServerTimestamp: Long = 100_000L

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> =
        notes.values.toList()

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? =
        notes[noteId]

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?> {
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
            )
            noteId to nextServerTimestamp
        }.toMap()
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        deletedNoteIds.addAll(noteIds)
        noteIds.forEach { notes.remove(it) }
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> =
        tombstones.toMap()

    override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) {
        tombstones[noteId] = deletedAt
    }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) {
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
