package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.attachments.NoteAttachmentMetadata
import com.aus.notelikeus.data.attachments.PendingDeletedAttachment

/**
 * The attachment-metadata RPCs one logical operation may perform — every one of them bound to the
 * immutable [OperationRemoteIdentity] that operation was captured under. R17, closing F-7.
 *
 * F-6 fixed *when* a metadata mutation may start (a one-shot generation grant). It deliberately did
 * not touch *whose credential* the request carries: the implementation resolved the bearer from the
 * live session while the request was being built, so an operation that began under account A and
 * suspended could physically send its listing or its purge after account B signed in — as B, with
 * A's attachment ids in the body. The generation grant cannot see this: it answers "may this mutation
 * start now?", never "as whom?".
 *
 * **The identity is a parameter of every method, and that is the whole point.** A secure and an
 * insecure invocation must not be interchangeable, so there is no overload that omits it, no nullable
 * variant, and no `if (identity != null) … else liveSession` inside an implementation. A caller that
 * wants to perform one of these RPCs has to say which account and which credential it belongs to —
 * and the value it passes is the immutable snapshot its operation captured, not a freshly read
 * session.
 *
 * **Reads are protected exactly like mutations.** Hydration's `listUserAttachments` and the sweep's
 * `listPendingDeletedAttachments` decide which account's rows this process writes into local state;
 * drifting their credential exposes B's metadata to an A continuation just as surely as a purge
 * mutating B's rows would.
 *
 * This is deliberately a separate contract from `AttachmentRemoteContext` (the R2 blob model): the two
 * services have independent transports, and an attachment operation legitimately holds both at once —
 * one identity for the metadata RPCs, one context for the object operations. They are not merged, and
 * a change to either must not silently retarget the other.
 */
interface AttachmentRemoteMetadata {

    /**
     * Registers a freshly uploaded object's metadata row.
     *
     * Takes the row rather than its fields: the value is the same shape [listUserAttachments] returns,
     * so a row read from the account can be handed straight back, and the identity stays the only
     * second argument. [NoteAttachmentMetadata.createdAt] is not part of the registration payload —
     * the server stamps it.
     */
    suspend fun register(
        identity: OperationRemoteIdentity,
        metadata: NoteAttachmentMetadata,
    )

    /** Removes one metadata row. */
    suspend fun delete(
        identity: OperationRemoteIdentity,
        attachmentId: String,
        noteId: String,
    )

    /** Lists the account's attachment rows — the input to hydration. */
    suspend fun listUserAttachments(identity: OperationRemoteIdentity): List<NoteAttachmentMetadata>

    /** Lists the rows the server has already marked pending-deleted — the input to the sweep. */
    suspend fun listPendingDeletedAttachments(identity: OperationRemoteIdentity): List<PendingDeletedAttachment>

    /** Clears one row the sweep has finished with. */
    suspend fun purgeDeleted(
        identity: OperationRemoteIdentity,
        attachmentId: String,
        noteId: String,
    )
}
