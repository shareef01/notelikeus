package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.domain.model.Note

/**
 * Test-only: the `uid`-keyed forwarding that [IdentityBoundNoteTransport] deliberately does not ship.
 *
 * This class is in `commonTest` on purpose, and the asymmetry is the whole guarantee. The production
 * interface has no defaults, so no shipped type can satisfy it by inheriting the live-session path;
 * a test double needs exactly that convenience, and cannot take it from production code because
 * production code cannot see this type. A double that omits a member fails to compile rather than
 * quietly authenticating as whoever is signed in.
 *
 * Implementing [CloudNoteTransport] as well means a double keeps whatever `uid`-keyed behaviour it
 * already had — including the interface's own defaults for `fetchNotesSnapshot`, `fetchTombstone`,
 * `restoreNote` and `deleteAllOwnedCloudData` — and gains the identity API the engine calls, with no
 * per-member boilerplate at each construction site.
 */
abstract class TestIdentityBoundNoteTransportAdapter :
    IdentityBoundNoteTransport,
    CloudNoteTransport {

    override suspend fun fetchNotes(identity: OperationRemoteIdentity): List<CloudNoteRecord> =
        fetchNotes(identity.ownerId)

    override suspend fun fetchNotesSnapshot(identity: OperationRemoteIdentity): CloudNoteSnapshot =
        fetchNotesSnapshot(identity.ownerId)

    override suspend fun fetchNote(identity: OperationRemoteIdentity, noteId: Long): CloudNoteRecord? =
        fetchNote(identity.ownerId, noteId)

    override suspend fun fetchTombstones(identity: OperationRemoteIdentity): Map<Long, Long> =
        fetchTombstones(identity.ownerId)

    override suspend fun fetchTombstone(identity: OperationRemoteIdentity, noteId: Long): Long? =
        fetchTombstone(identity.ownerId, noteId)

    override suspend fun putNotes(
        identity: OperationRemoteIdentity,
        notes: List<Note>,
    ): Map<Long, CloudNoteTransport.PutResult> = putNotes(identity.ownerId, notes)

    override suspend fun deleteNote(
        identity: OperationRemoteIdentity,
        noteId: Long,
        baseRevision: Long,
    ): CloudNoteTransport.DeleteResult = deleteNote(identity.ownerId, noteId, baseRevision)

    override suspend fun deleteNotes(identity: OperationRemoteIdentity, noteIds: List<Long>) =
        deleteNotes(identity.ownerId, noteIds)

    override suspend fun restoreNote(
        identity: OperationRemoteIdentity,
        note: Note,
    ): Map<Long, CloudNoteTransport.PutResult> = restoreNote(identity.ownerId, note)

    override suspend fun writeTombstone(identity: OperationRemoteIdentity, noteId: Long, deletedAt: Long) =
        writeTombstone(identity.ownerId, noteId, deletedAt)

    override suspend fun deleteTombstones(identity: OperationRemoteIdentity, noteIds: List<Long>) =
        deleteTombstones(identity.ownerId, noteIds)

    override suspend fun writeSyncMeta(identity: OperationRemoteIdentity, noteCount: Int, platform: String) =
        writeSyncMeta(identity.ownerId, noteCount, platform)

    override suspend fun deleteAllOwnedCloudData(identity: OperationRemoteIdentity) =
        deleteAllOwnedCloudData(identity.ownerId)
}
