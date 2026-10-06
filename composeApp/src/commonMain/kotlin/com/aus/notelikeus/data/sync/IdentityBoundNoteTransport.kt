package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.domain.model.Note

/**
 * A note transport that can execute every protected call under an explicitly captured account
 * identity — R13A, closing F-1 end to end.
 *
 * [CloudNoteTransport] addresses an account by a `uid` *argument* while the credential comes from
 * whatever session is live when the request is built, so an operation that began as A and sent after
 * B signed in went out with B's bearer and A's arguments. The methods here take the identity
 * instead: owner and credential travel together, and a protected call cannot be told to act for one
 * account while authenticating as another.
 *
 * ## Why there are no defaults, and why that is structural rather than conventional
 *
 * Every member here is **abstract**. An earlier revision gave each one a default that forwarded to
 * the `uid`-keyed member of [CloudNoteTransport]; that kept test doubles compiling, but it also meant
 * a production implementation could satisfy this contract while every protected call still resolved
 * its bearer from the live session — the F-1 defect, declared as fixed. That is not reviewable: a
 * default is inherited silently, and by every implementation written afterwards.
 *
 * So the convenience moved to `TestIdentityBoundNoteTransportAdapter` in the **test** source set. A
 * test double (or a delegating test wrapper) extends that adapter and inherits the forwarding; no
 * production type can, because production code cannot see the adapter at all. The interface therefore
 * cannot be satisfied from the live session even by accident, and an implementation that omits a
 * member does not compile rather than quietly reaching the wrong account.
 *
 * This interface deliberately does **not** extend [CloudNoteTransport]. Extending it would hand every
 * implementation the `uid`-keyed members for free, and with them the live-session path this exists to
 * remove. [com.aus.notelikeus.data.remote.SupabaseNoteTransport] implements both interfaces
 * explicitly — it needs the `uid`-keyed bodies as the shared implementation its identity-scoped view
 * runs — but the engine only ever sees this one.
 */
interface IdentityBoundNoteTransport {

    /** Returns every note document for [identity]'s account, in no guaranteed order. */
    suspend fun fetchNotes(identity: OperationRemoteIdentity): List<CloudNoteRecord>

    /** A full-library read for [identity]'s account, carrying the transport's completeness proof. */
    suspend fun fetchNotesSnapshot(identity: OperationRemoteIdentity): CloudNoteSnapshot

    /** Returns the single note document for [identity]'s account, or null if absent. */
    suspend fun fetchNote(identity: OperationRemoteIdentity, noteId: Long): CloudNoteRecord?

    /** Returns every tombstone for [identity]'s account as noteId → deletedAt. */
    suspend fun fetchTombstones(identity: OperationRemoteIdentity): Map<Long, Long>

    /** Returns the deletedAt for a single tombstone of [identity]'s account, or null if absent. */
    suspend fun fetchTombstone(identity: OperationRemoteIdentity, noteId: Long): Long?

    /** Writes every [Note] for [identity]'s account and returns what the server accepted. */
    suspend fun putNotes(
        identity: OperationRemoteIdentity,
        notes: List<Note>,
    ): Map<Long, CloudNoteTransport.PutResult>

    /** Deletes one note of [identity]'s account iff the server's revision matches [baseRevision]. */
    suspend fun deleteNote(
        identity: OperationRemoteIdentity,
        noteId: Long,
        baseRevision: Long,
    ): CloudNoteTransport.DeleteResult

    /** Deletes note documents of [identity]'s account in batches. */
    suspend fun deleteNotes(identity: OperationRemoteIdentity, noteIds: List<Long>)

    /** Atomically removes [identity]'s tombstone for the note and writes the live note. */
    suspend fun restoreNote(
        identity: OperationRemoteIdentity,
        note: Note,
    ): Map<Long, CloudNoteTransport.PutResult>

    /** Writes or merges a single tombstone for [identity]'s account. */
    suspend fun writeTombstone(identity: OperationRemoteIdentity, noteId: Long, deletedAt: Long)

    /** Deletes tombstone documents of [identity]'s account in batches. */
    suspend fun deleteTombstones(identity: OperationRemoteIdentity, noteIds: List<Long>)

    /** Writes sync metadata (lastSyncAt, noteCount, platform) for [identity]'s account. */
    suspend fun writeSyncMeta(identity: OperationRemoteIdentity, noteCount: Int, platform: String)

    /** Wipes every cloud row for [identity]'s account (notes, tombstones, sync meta). */
    suspend fun deleteAllOwnedCloudData(identity: OperationRemoteIdentity)
}
