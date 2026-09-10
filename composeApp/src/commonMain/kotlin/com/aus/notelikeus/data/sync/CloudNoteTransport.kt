package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Note

/**
 * The result of a full-library read: the records that arrived, and — when the backend can say so
 * — how many there should have been.
 *
 * @property records every note document the transport managed to produce.
 * @property authoritativeNoteCount the server's own count of the account's note rows, derived
 *   independently of [records] (not `records.size` recomputed). `null` means this transport cannot
 *   prove completeness, and the engine reconciles on the records alone as it always has.
 */
data class CloudNoteSnapshot(
    val records: List<CloudNoteRecord>,
    val authoritativeNoteCount: Int? = null,
)

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

    /**
     * A full-library read together with the transport's proof that it is complete.
     *
     * [fetchNotes] returns a bare list, and a list carries no evidence of what it left out. The
     * engine's whole reconciliation reads an absent id as "deleted elsewhere", so a snapshot that
     * lost rows in transit — a truncated aggregate, a page that never arrived, a row the parser
     * could not read — is indistinguishable from a real deletion and gets applied as one. The
     * empty-cloud guards do not help: they only fire when *nothing* came back.
     *
     * [CloudNoteSnapshot.authoritativeNoteCount] is that missing evidence. Transports whose
     * backend can state the true row count independently of the payload (Supabase's
     * `fetch_full_snapshot` returns a separate `note_count`) report it; the engine refuses to
     * reconcile when it disagrees with what actually arrived.
     *
     * Defaulted so transports and test doubles that cannot prove completeness keep working
     * unchanged — they report `null` and the engine behaves exactly as before for them.
     */
    suspend fun fetchNotesSnapshot(uid: String): CloudNoteSnapshot =
        CloudNoteSnapshot(records = fetchNotes(uid), authoritativeNoteCount = null)

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

    /**
     * Atomically removes the owner's tombstone (if any) and writes the live note.
     * Default walks [deleteTombstones] then [putNotes]; Supabase overrides with `restore_note`.
     */
    suspend fun restoreNote(uid: String, note: Note): Map<Long, Long?> {
        val noteId = note.id ?: return emptyMap()
        deleteTombstones(uid, listOf(noteId))
        return putNotes(uid, listOf(note))
    }

    /** Returns every tombstone for [uid] as noteId → deletedAt. */
    suspend fun fetchTombstones(uid: String): Map<Long, Long>

    /**
     * Returns the deletedAt for a single tombstone, or null if the note is not tombstoned.
     *
     * Exists so the single-note paths do not have to list the whole collection to answer a
     * question about one id — [com.aus.notelikeus.data.sync.NoteSyncEngine.uploadNote] runs once
     * per queued note, so on desktop a flush of thirty edits was thirty full collection reads.
     * Defaulted to a filter over [fetchTombstones] so existing transports and test doubles keep
     * working unchanged; transports that can address the document directly should override it.
     */
    suspend fun fetchTombstone(uid: String, noteId: Long): Long? =
        fetchTombstones(uid)[noteId]

    /** Writes or merges a single tombstone. */
    suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long)

    /** Deletes tombstone documents in batches. */
    suspend fun deleteTombstones(uid: String, noteIds: List<Long>)

    /** Writes sync metadata (lastSyncAt, noteCount, platform). */
    suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String)

    /** Deletes sync metadata. */
    suspend fun deleteSyncMeta(uid: String)

    /**
     * Wipes every cloud row for [uid] (notes, tombstones, sync meta).
     * Used by "sign out and delete cloud data". Transports that can do this in one RPC
     * should override; the default walks the existing fetch/delete methods.
     */
    suspend fun deleteAllOwnedCloudData(uid: String) {
        val records = fetchNotes(uid)
        deleteNotes(uid, records.map { it.noteId })
        val tombstones = fetchTombstones(uid)
        deleteTombstones(uid, tombstones.keys.toList())
        deleteSyncMeta(uid)
    }
}
