package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Note

/**
 * Platform-agnostic transport for cloud note sync.
 *
 * Every method takes an explicit [uid] so the engine (commonMain) controls
 * which account's data is accessed, while the transport (androidMain /
 * desktopMain) only moves bytes and has no policy of its own.
 */
interface CloudNoteTransport {

    /** Returns every note document for [uid], in no guaranteed order. */
    suspend fun fetchNotes(uid: String): List<CloudNoteRecord>

    /** Returns the single note document, or null if absent. */
    suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord?

    /**
     * Writes every [Note] in the list and returns the server-resolved
     * [Note.serverUpdatedAt] for each successfully written id. A null value
     * means the server timestamp could not be read back for that note (the
     * note was still written; this is a readback failure, not a write
     * failure).
     */
    suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?>

    /** Deletes note documents in batches (transport handles chunking). */
    suspend fun deleteNotes(uid: String, noteIds: List<Long>)

    /** Returns every tombstone for [uid] as noteId → deletedAt. */
    suspend fun fetchTombstones(uid: String): Map<Long, Long>

    /** Writes or merges a single tombstone. */
    suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long)

    /** Deletes tombstone documents in batches. */
    suspend fun deleteTombstones(uid: String, noteIds: List<Long>)

    /** Writes sync metadata (lastSyncAt, noteCount, platform). */
    suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String)

    /** Deletes sync metadata. */
    suspend fun deleteSyncMeta(uid: String)
}
