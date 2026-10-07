package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.AttachmentRemoteMetadata
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.util.AppLog

class AttachmentSyncService(
    private val blobTransport: AttachmentBlobTransport,
    private val metadata: AttachmentRemoteMetadata?,
    private val localStorage: AttachmentLocalStorage,
    private val noteDao: NoteDao,
    private val staging: AttachmentStagingStore,
    /** Current account, or null while signed out. Staged bytes are namespaced by its result. */
    private val ownerIdProvider: () -> String? = { null },
    /**
     * Whether attachments are configured. Injectable so these paths can be exercised without a
     * Worker URL baked into the build under test.
     */
    private val attachmentsEnabled: () -> Boolean = ::isR2AttachmentsEnabled,
) {
    private val cache = PendingAttachmentCache()

    /**
     * [metadata] is deliberately not part of this: the two methods that use it null-check it
     * themselves, and requiring it here made the blob paths — upload, delete, staging — untestable
     * without a live RPC client for a collaborator they never touch.
     *
     * R17: the collaborator is the identity-bound [AttachmentRemoteMetadata] contract, and both
     * metadata operations take the originating operation's immutable [OperationRemoteIdentity] as a
     * parameter. Nothing in this service resolves a credential from the live session, and neither
     * method has a signature that could be called without saying whose account it belongs to.
     */
    private val enabled: Boolean
        get() = attachmentsEnabled() && blobTransport !is NoopAttachmentBlobTransport

    private fun ownerId(): String = ownerIdProvider() ?: GUEST_STAGING_OWNER

    fun mergeAttachmentsIntoNotes(
        notes: List<Note>,
        rows: List<NoteAttachmentMetadata>,
    ): List<Note> = com.aus.notelikeus.data.attachments.mergeAttachmentsIntoNotes(notes, rows)

    /**
     * Stages [bytes] durably so the caller may add the attachment to a note.
     *
     * Returns false when the bytes did not land: the caller must then not reference the
     * attachment, rather than persisting a note that points at bytes which were never written.
     */
    suspend fun stageAttachment(
        attachmentId: String,
        noteId: Long?,
        bytes: ByteArray,
        mimeType: String,
        expectedOwnerId: String? = null,
    ): Boolean {
        val owner = ownerId()
        if (expectedOwnerId != null && expectedOwnerId != owner) {
            AppLog.warn(TAG, "Attachment staging rejected: expected owner $expectedOwnerId but current is $owner")
            return false
        }
        val staged = staging.stage(attachmentId, owner, noteId, bytes, mimeType) ?: return false
        cache.put(owner, attachmentId, PendingAttachment(bytes, staged.mimeType))
        return true
    }

    /** Records the Room id a staged attachment ended up on, once the insert has issued one. */
    suspend fun bindStagedAttachmentsToNote(noteId: Long, attachments: List<Attachment>) {
        val owner = ownerId()
        for (attachment in attachments) {
            if (!isPendingAttachment(attachment.storagePath)) continue
            staging.bindNote(pendingId(attachment.storagePath), owner, noteId)
        }
    }

    /**
     * [bindStagedAttachmentsToNote] for a save whose account/dataset identity was captured up
     * front, refusing the binding once that dataset has moved on.
     *
     * The binding is durable local state — staged metadata is written per owner namespace and read
     * back by restart reconciliation — so a continuation that lost its dataset must not rewrite it
     * into whatever account is current now. There is no network work here, so the whole binding is
     * one commit.
     */
    suspend fun bindStagedAttachmentsToNote(
        noteId: Long,
        attachments: List<Attachment>,
        commitToken: LocalCommitToken,
    ): LocalCommitResult<Unit> = LocalCommitGate.commit(commitToken) {
        bindStagedAttachmentsToNote(noteId, attachments)
        Unit
    }

    /** Drops staged bytes the user explicitly removed, or that a discarded note referenced. */
    suspend fun releaseStagedAttachments(attachments: List<Attachment>) {
        val owner = ownerId()
        for (attachment in attachments) {
            if (!isPendingAttachment(attachment.storagePath)) continue
            val id = pendingId(attachment.storagePath)
            cache.remove(owner, id)
            staging.release(id, owner)
        }
    }

    /**
     * Rebuilds each note's attachment set from the account's attachment metadata.
     *
     * [commitToken] is the enclosing download's generation, and it is *threaded in* rather than
     * captured here: this hydration belongs to the run that asked for it, and it must not mint an
     * authority of its own.
     *
     * The metadata listing is a suspension, so nothing after it may proceed on its authority alone.
     * Both exits are generation-fenced:
     *
     *  - **no rows** -> there is no local work to fence, but the read still suspended, so the
     *    originating token takes an explicit decision before this reports a no-op success. Answering
     *    `Applied(0)` there for a dataset that has since been replaced is the hole this phase closes,
     *    and it is the outcome that looks harmless;
     *  - **rows present** -> every note write is applied inside one commit under the same token, so a
     *    replaced dataset cannot have its rows rewritten from this run's remote listing.
     *
     * The listing stays outside the gate: it is network work, and account isolation must never wait on
     * a round trip. The local writes are still one DAO call per note, as before — this phase changes
     * *authority*, not transaction shape, and it performs no blob or filesystem work at all.
     *
     * R17: the listing is a *protected* remote read, so it is bound to [metadataIdentity] — the
     * immutable owner/credential snapshot the caller captured for this download. The read used to
     * resolve the bearer from the live session at send time, which meant an operation that began under
     * account A could pull account B's attachment rows into local state after B signed in. The
     * identity is a parameter rather than something this helper captures, for the same reason the
     * token is: a capture here would launder whichever session happened to be live by then.
     */
    suspend fun hydrateAllNotes(
        commitToken: LocalCommitToken,
        metadataIdentity: OperationRemoteIdentity,
    ): LocalCommitResult<Int> {
        if (!enabled) return LocalCommitResult.Applied(0)
        val metadataClient = metadata ?: return LocalCommitResult.Applied(0)
        // A suspension: its outcome cannot authorize what follows it, empty or not.
        val rows = metadataClient.listUserAttachments(metadataIdentity)
        if (rows.isEmpty()) {
            return if (LocalCommitGate.commit(commitToken) { } is LocalCommitResult.StaleGeneration) {
                LocalCommitResult.StaleGeneration
            } else {
                LocalCommitResult.Applied(0)
            }
        }
        // One commit for the whole application, under the originating token: every note write below is
        // authorized by the generation that asked for this hydration.
        return LocalCommitGate.commit(commitToken) {
            var updated = 0
            val notes = noteDao.getAllNotesForBackup().map { it.toNote() }
            for (merged in mergeAttachmentsIntoNotes(notes, rows)) {
                val original = notes.firstOrNull { it.id == merged.id } ?: continue
                if (attachmentsKey(original.attachments) == attachmentsKey(merged.attachments)) continue
                noteDao.updateNote(merged.toNoteEntity())
                updated++
            }
            updated
        }
    }

    /**
     * Uploads [note]'s local attachments and reports the note carrying the paths the server accepted.
     *
     * [commitToken] is the generation of the **caller's** logical operation, threaded in rather than
     * captured here: this helper must not mint an authority of its own, because the caller has held
     * one since before the work that produced these bytes. Re-capturing at this boundary is exactly
     * the laundering F-2 is about — it would hand an old operation's continuation whatever generation
     * happens to be current when the helper is reached.
     *
     * Required rather than nullable. It used to be `LocalCommitToken?` for an "explicit legacy
     * manual/direct caller" that uploaded with no generation authority at all, and that caller no
     * longer exists: the editor's saves capture a token (see `EditorViewModel`), and the engine
     * always had one. A nullable parameter here is not a compatibility affordance, it is an
     * un-authorized upload path held open by its own type — the one shape of F-2 that a
     * per-invocation grant cannot cover, because there is no generation to authorize against.
     *
     * **One grant per real upload.** Each [AttachmentBlobTransport.upload] reached from here is one
     * account-owned remote mutation, so each takes its own `RemoteMutationAuthorization` from
     * [commitToken] immediately before it starts. A single grant covering two uploads would let the
     * second start after a boundary that nothing refused, which is the between-object hole this
     * closes. An attachment that results in no invocation — already `r2:`, or bytes that are provably
     * gone — takes no grant at all: authorizing a request that never happens is not authority, it is
     * noise.
     *
     * The grant is taken *after* the bytes are read and `captureContext` has snapshotted the remote
     * identity, so no long unrelated work sits between authorizing and uploading, and the gate itself
     * is released before the request goes out — account isolation is never serialized behind a round
     * trip.
     *
     * R18 gave the two token-aware **deletion** paths this same pair of rules — generation decision
     * first, then [requireContextBelongsToOperation] — so every blob mutation in this service now proves
     * both *whose* account its context belongs to and *when* it may start.
     */
    suspend fun syncNoteAttachments(
        note: Note,
        commitToken: LocalCommitToken,
    ): LocalCommitResult<Note> {
        if (!enabled || note.attachments.isEmpty()) return LocalCommitResult.Applied(note)
        val noteId = note.id?.toString() ?: return LocalCommitResult.Applied(note)
        // One identity snapshot for the whole batch, taken where the operation starts: every upload
        // below runs against the account this call began under, so a session change part-way through
        // cannot retarget the attachments still to come. It suspends — it may refresh credentials —
        // which is exactly why nothing below may run on its authority alone.
        val remote = blobTransport.captureContext()
        // The generation decision comes **first**, before anything is compared against the context
        // that was just captured. This capture suspends, and the session that answers it afterwards
        // belongs to whatever is live then — including another account. Comparing that context against
        // the originating token would report an *incoherence*, when the fact that matters is that the
        // generation this operation belongs to no longer exists. The ordering is not cosmetic: a
        // caller that treats a coherence error as a transient transport failure swallows it and
        // carries on doing account-owned work for the extinct dataset, which is what staged
        // reconciliation used to do here.
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        // Only a generation that is still current may ask whether its own context coheres — and
        // there, a mismatch really is a broken invariant rather than a boundary.
        requireContextBelongsToOperation(remote, commitToken)
        val scope = AttachmentUploadScope(
            commitToken = commitToken,
            remote = remote,
            // The *staging* namespace local bytes are read from — not the remote account above.
            owner = ownerId(),
            noteId = noteId,
        )
        val synced = mutableListOf<Attachment>()
        for (attachment in note.attachments) {
            when (val outcome = resolveAttachment(scope, attachment)) {
                // The originating dataset is gone, so no later attachment — in this note or in a
                // later one this batch would have processed — may start either.
                AttachmentOutcome.Stale -> return LocalCommitResult.StaleGeneration
                AttachmentOutcome.Dropped -> Unit
                is AttachmentOutcome.Kept -> synced.add(outcome.attachment)
            }
        }
        return LocalCommitResult.Applied(note.copy(attachments = synced))
    }

    /**
     * [syncNoteAttachments] for a whole batch, under one originating generation.
     *
     * The result keeps the one-to-one mapping the caller indexes by: position *i* of the applied list
     * is [notes] position *i*, so `putNotes` can still tell "the upload dropped this attachment" from
     * "the user added one while it ran". A refusal ends the batch, because a dataset that is gone
     * cannot have later notes uploaded for it either.
     */
    suspend fun syncNotesAttachments(
        notes: List<Note>,
        commitToken: LocalCommitToken,
    ): LocalCommitResult<List<Note>> {
        val synced = mutableListOf<Note>()
        for (note in notes) {
            when (val result = syncNoteAttachments(note, commitToken)) {
                is LocalCommitResult.Applied -> synced.add(result.value)
                LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
            }
        }
        return LocalCommitResult.Applied(synced)
    }

    /**
     * One attachment, resolved to whatever should be recorded for it.
     *
     * [AttachmentOutcome.Dropped] is reachable only where the underlying store says the bytes are
     * *provably* gone: a store that cannot read them, or cannot say, keeps the reference so a failing
     * disk or a locked encrypted store can never quietly delete a user's picture. Dropping is not an
     * error and must not stop the batch; a refused upload is, and it does.
     */
    private suspend fun resolveAttachment(
        scope: AttachmentUploadScope,
        attachment: Attachment,
    ): AttachmentOutcome = when {
        isPendingAttachment(attachment.storagePath) -> {
            val pendingId = pendingId(attachment.storagePath)
            // A miss here used to mean the bytes belonged to another account's namespace — sign-in
            // now adopts those — so what remains is bytes a pre-staging build never wrote, or bytes
            // that are genuinely gone.
            val pending = loadStaged(scope.owner, pendingId)
            when {
                pending != null -> uploadAndRewrite(
                    scope = scope,
                    attachment = attachment,
                    bytes = pending.bytes,
                    mimeType = pending.mimeType,
                )
                staging.isStaged(pendingId, scope.owner) == false -> {
                    AppLog.warn(TAG, "Dropping attachment ${attachment.id}: staged bytes are gone")
                    AttachmentOutcome.Dropped
                }
                else -> AttachmentOutcome.Kept(attachment)
            }
        }
        isFileAttachment(attachment.storagePath) -> {
            val bytes = localStorage.readBytes(attachment.storagePath)
            when {
                bytes != null -> uploadAndRewrite(
                    scope = scope,
                    attachment = attachment,
                    bytes = bytes,
                    mimeType = attachment.mimeType ?: "image/jpeg",
                )
                // Same rule as staged bytes: drop only on proven absence.
                localStorage.exists(attachment.storagePath) == false -> {
                    AppLog.warn(TAG, "Dropping attachment ${attachment.id}: local file is gone")
                    AttachmentOutcome.Dropped
                }
                else -> AttachmentOutcome.Kept(attachment)
            }
        }
        else -> AttachmentOutcome.Kept(attachment)
    }

    /** Uploads one attachment's bytes and returns it carrying the paths the server accepted. */
    private suspend fun uploadAndRewrite(
        scope: AttachmentUploadScope,
        attachment: Attachment,
        bytes: ByteArray,
        mimeType: String,
    ): AttachmentOutcome = when (val uploaded = uploadAttachment(scope, attachment.id, bytes, mimeType)) {
        is LocalCommitResult.Applied -> AttachmentOutcome.Kept(
            attachment.copy(
                storagePath = "$ATTACHMENT_R2_PREFIX${uploaded.value.objectKey}",
                mimeType = uploaded.value.mimeType,
                sizeBytes = uploaded.value.sizeBytes,
            ),
        )
        LocalCommitResult.StaleGeneration -> AttachmentOutcome.Stale
    }

    /**
     * One real blob upload, under the originating generation.
     *
     * The grant is taken here — after the payload was assembled and the remote identity snapshotted —
     * and consumed by the one invocation it names, so authority is never handed back to a generation
     * that no longer exists and one grant never covers two objects. Every path into this method
     * carries a token, so there is no invocation that skips it: the method is the only site that
     * reaches the blob transport for an upload, and it cannot be reached without a grant.
     */
    private suspend fun uploadAttachment(
        scope: AttachmentUploadScope,
        attachmentId: String,
        bytes: ByteArray,
        mimeType: String,
    ): LocalCommitResult<AttachmentBlobUploadResult> {
        val mutationName = "attachment.uploadBlob(${scope.noteId}/$attachmentId)"
        val upload = LocalCommitGate.authorizeRemoteMutationStart(scope.commitToken, mutationName)
            ?: return LocalCommitResult.StaleGeneration
        return LocalCommitResult.Applied(
            upload.consumeOnce {
                blobTransport.upload(scope.remote, scope.noteId, attachmentId, bytes, mimeType)
            },
        )
    }

    /**
     * The values every attachment in one [syncNoteAttachments] pass shares.
     *
     * Threaded as one value rather than as four loose parameters so the per-attachment helpers stay
     * readable, and so none of them can quietly substitute a different account, note or dataset for
     * the one the pass began with.
     */
    private data class AttachmentUploadScope(
        val commitToken: LocalCommitToken,
        val remote: AttachmentRemoteContext,
        val owner: String,
        val noteId: String,
    )

    /** What resolving one attachment produced. */
    private sealed interface AttachmentOutcome {
        /** The note keeps [attachment]: unchanged, or rewritten with the paths the server accepted. */
        data class Kept(val attachment: Attachment) : AttachmentOutcome

        /** Nothing to record — the bytes are provably gone, so the reference is dropped. */
        data object Dropped : AttachmentOutcome

        /** The originating dataset is gone, so the caller must stop and report stale. */
        data object Stale : AttachmentOutcome
    }

    /**
     * The remote identity a blob upload would run under must belong to the operation that asked for it.
     *
     * The staging namespace, the remote context and the caller's token are three reads of the live
     * session, taken at different times. The context is captured *after* the token, so a session that
     * moved in between yields a context for B under an operation that began as A — and uploading would
     * then write B's namespace from A's operation, which is the identity half of the same defect
     * `AttachmentRemoteContext` exists to prevent. A generation check does not catch it on its own: a
     * uid change that does not isolate (guest → first account) keeps the generation, which is why the
     * owners are compared directly.
     *
     * A `null` [LocalCommitToken.initiatingUid] is the guest/staging case: there is no account to
     * compare against, and generation validation is the whole of that adoption's authority — which is
     * why the check is skipped rather than refusing it.
     *
     * Deliberately reached only *after* the originating generation has been validated: a context that
     * belongs to another account because the session moved across the capture is a stale generation,
     * and that decision must not be masked by this one.
     */
    private fun requireContextBelongsToOperation(
        remote: AttachmentRemoteContext,
        commitToken: LocalCommitToken,
    ) {
        val initiating = commitToken.initiatingUid ?: return
        check(initiating == remote.ownerId) {
            "Refusing an attachment remote operation: it was initiated for account $initiating but " +
                "its remote context belongs to ${remote.ownerId}."
        }
    }

    /**
     * Drops local source copies for the attachments whose bytes the server has now accepted.
     *
     * [snapshot] is the note as it was *before* the upload rewrote storage paths, and [uploaded] is
     * what [syncNoteAttachments] returned for it. An attachment may only be released when it was a
     * local source (`pending:` or `file:`) in the snapshot **and** came back carrying an `r2:` path:
     * that is what proves a recoverable copy exists, the single condition
     * [AttachmentStagingStore.release] allows.
     *
     * Deciding from [uploaded] alone inverts the answer. A `pending:`/`file:` path there means the
     * upload pass deliberately *kept* the attachment because its bytes could not be read — the case
     * [AttachmentStagingStore.isStaged]'s contract exists to protect — so releasing it would delete
     * the only copy of the user's picture while the note still references it. And an `r2:` path
     * needs no local cleanup at all, so reading the paths off [uploaded] alone also leaves every
     * genuinely uploaded local source behind forever.
     */
    suspend fun confirmCommittedAttachments(snapshot: Note, uploaded: Note) {
        if (!enabled) return
        val committedIds = uploaded.attachments
            .filter { isR2Attachment(it.storagePath) }
            .mapTo(mutableSetOf()) { it.id }
        if (committedIds.isEmpty()) return
        val owner = ownerId()
        for (attachment in snapshot.attachments) {
            if (attachment.id !in committedIds) continue
            when {
                isPendingAttachment(attachment.storagePath) -> {
                    val pendingId = pendingId(attachment.storagePath)
                    cache.remove(owner, pendingId)
                    staging.release(pendingId, owner)
                }
                isFileAttachment(attachment.storagePath) -> localStorage.deleteIfLocal(attachment.storagePath)
            }
        }
    }

    suspend fun deleteAttachmentsForNote(noteId: Long, attachments: List<Attachment>) {
        if (!enabled || attachments.isEmpty()) return
        val noteIdStr = noteId.toString()
        // Identity for the whole removal, taken where it starts rather than per object delete.
        val remote = blobTransport.captureContext()
        var remoteFailure: Throwable? = null
        for (attachment in attachments) {
            deleteLocalAttachmentState(attachment)
            when {
                isPendingAttachment(attachment.storagePath) ->
                    // An upload can commit after the user removed the attachment: the snapshot
                    // being uploaded was taken before the removal, so it finishes and writes a
                    // metadata row for an image the note no longer lists. mergeAttachmentsIntoNotes
                    // rebuilds a note's attachments from live rows, so that row would put the
                    // deleted image straight back on the next hydrate. Best effort by design —
                    // usually nothing was ever uploaded and there is no row to remove.
                    runCatching { blobTransport.delete(remote, noteIdStr, attachment.id) }
                isR2Attachment(attachment.storagePath) -> runCatching {
                    blobTransport.delete(remote, noteIdStr, attachment.id)
                }.onFailure { error ->
                    if (remoteFailure == null) remoteFailure = error
                }
            }
        }
        remoteFailure?.let { throw it }
    }

    /**
     * [deleteAttachmentsForNote] for a save whose account/dataset identity was captured up front.
     *
     * Split deliberately around the network: the account-owned deletions run inside the originating
     * token's commit, and the object deletions run after the gate is released, so account isolation
     * can never be serialized behind a round trip. A refused commit skips *both* halves — a cleanup
     * belonging to an abandoned dataset must not delete the current account's attachment state, and
     * it must not issue a remote delete under whatever identity happens to be signed in now either.
     *
     * R18: the one context snapshot taken here is also judged against the token — after that commit and
     * before the first object's grant — so an object deletion can never be redirected into an account
     * the operation did not start under. The decision comes first on purpose: a boundary behind the
     * capture is staleness, not an incoherence.
     *
     * R18.1: that judgement needs an origin to judge against, so a token with **no** originating
     * account deletes nothing remotely. Local cleanup still happens; a `pending:` reference is skipped
     * (best-effort server cleanup belongs to the sweep), and an `r2:` reference — which no guest
     * operation can legitimately hold — is an invariant failure.
     */
    suspend fun deleteAttachmentsForNote(
        noteId: Long,
        attachments: List<Attachment>,
        commitToken: LocalCommitToken,
    ): LocalCommitResult<Unit> {
        if (!enabled || attachments.isEmpty()) return LocalCommitResult.Applied(Unit)

        // Captured before the gate, never inside it: snapshotting may refresh credentials, and a
        // held account-isolation section must not wait on an auth round trip.
        val remote = blobTransport.captureContext()

        val committed = LocalCommitGate.commit(commitToken) {
            attachments.forEach { deleteLocalAttachmentState(it) }
        }
        if (committed is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        // R18: the captured context is judged **after** that authoritative decision, never before it.
        // The capture suspends, so an account boundary can land behind it — and then the dataset this
        // token belongs to no longer exists, which is staleness's answer to give, not a coherence
        // error's. Only a context that is wrong while the operation's generation is still current is an
        // invariant failure, and the same helper, with the same null-uid semantics, states that rule
        // here as it does for uploads.
        requireContextBelongsToOperation(remote, commitToken)

        val noteIdStr = noteId.toString()
        var remoteFailure: Throwable? = null
        for (attachment in attachments) {
            // One decision covers both questions: is this reference remote-eligible at all, and may
            // this operation's origin delete remotely? See [mayDeleteRemotely] for the R18.1 rule.
            if (!mayDeleteRemotely(commitToken, attachment, noteId)) continue
            // One grant per actual object deletion. This loop issues one remote mutation per
            // attachment, and a single authorization covers exactly one invocation — so it is taken
            // here, under the originating token, and never reused across objects.
            val deletion = LocalCommitGate.authorizeRemoteMutationStart(
                commitToken,
                "attachment.deleteBlob($noteIdStr/${attachment.id})",
            ) ?: return LocalCommitResult.StaleGeneration
            val outcome = runCatching {
                deletion.consumeOnce { blobTransport.delete(remote, noteIdStr, attachment.id) }
            }
            // Historical failure policy, preserved: only an R2 object's failure is remembered and
            // rethrown at the end; a `pending:` object delete is best-effort by design and its failure
            // is discarded.
            if (isR2Attachment(attachment.storagePath) && outcome.isFailure && remoteFailure == null) {
                remoteFailure = outcome.exceptionOrNull()
            }
        }
        remoteFailure?.let { throw it }
        return LocalCommitResult.Applied(Unit)
    }

    /**
     * Whether [attachment]'s object may be deleted remotely under [commitToken] — R18.1.
     *
     * Remote deletion needs an authoritative originating account. A token with no originating uid
     * cannot say *whose* objects it is deleting, and reading the live session instead is the credential
     * drift every identity rule in this service exists to prevent. The two remote-eligible paths are not
     * equivalent:
     *
     *  - a `pending:` reference names bytes this device staged. A guest legitimately owns one (staging
     *    works signed out), and the only server-side artefact it could have is a metadata row an
     *    in-flight upload committed — best-effort cleanup the sweep owns. So it is skipped, with no
     *    grant and no call, exactly as a failed best-effort delete already behaved;
     *  - an `r2:` reference names an object that exists only because a signed-in account uploaded it. A
     *    null origin there is not a best-effort miss, it is an invariant failure: nothing here may
     *    delete a remote object on the strength of "the session probably still is who it was".
     */
    private fun mayDeleteRemotely(
        commitToken: LocalCommitToken,
        attachment: Attachment,
        noteId: Long,
    ): Boolean {
        val remoteEligible = isPendingAttachment(attachment.storagePath) ||
            isR2Attachment(attachment.storagePath)
        if (!remoteEligible) return false
        if (commitToken.initiatingUid != null) return true
        check(!isR2Attachment(attachment.storagePath)) {
            "Refusing a remote blob deletion for note $noteId: the operation has no originating " +
                "account, but ${attachment.id} names a remote object."
        }
        return false
    }

    /**
     * The durable, account-owned half of an attachment removal: staged bytes and local files.
     *
     * Shared by the legacy and the token-aware removal so the two cannot drift about which
     * deletions are local — the fence's value is that it wraps exactly this, and nothing else.
     */
    private suspend fun deleteLocalAttachmentState(attachment: Attachment) {
        val owner = ownerId()
        when {
            isPendingAttachment(attachment.storagePath) -> {
                val pendingId = pendingId(attachment.storagePath)
                cache.remove(owner, pendingId)
                staging.release(pendingId, owner)
            }
            isFileAttachment(attachment.storagePath) -> localStorage.deleteIfLocal(attachment.storagePath)
        }
    }

    /**
     * One authoritative generation decision for a moment that followed a suspension.
     *
     * [LocalCommitGate.commit] with an empty block: it takes the gate just long enough to compare the
     * originating token's generation against the live one, and no I/O ever runs inside it. Every call
     * site below sits immediately after a suspension and immediately before either a later mutation or
     * a success/no-op return; the sweep's whole temporal contract is these calls.
     */
    private suspend fun validateGeneration(commitToken: LocalCommitToken): LocalCommitResult<Unit> =
        LocalCommitGate.commit(commitToken) { Unit }

    /**
     * One note's staged bytes, resumed under the reconciliation's own token.
     *
     * `Applied(true)` means this note recorded an upload; `Applied(false)` means there was nothing to
     * do, or that this attempt failed and the next reconciliation will retry. Both failure policies
     * here are the historical ones and are deliberately *not* aliased to stale: an unreachable server
     * and a failed local write both leave the staged bytes and the note exactly as they were.
     *
     * [LocalCommitResult.StaleGeneration] means the reconciliation's dataset is gone, which ends the
     * whole run — the row write is account-owned local state keyed by a per-device autoincrement id,
     * so a replacement generation's row very plausibly reuses it and must not be written.
     */
    private suspend fun resumeStagedUploadsFor(
        note: Note,
        commitToken: LocalCommitToken,
        owner: String,
        referenced: MutableSet<String>,
        onMissingBytes: (Note, Attachment) -> Unit,
    ): LocalCommitResult<Boolean> {
        for (attachment in note.attachments) {
            if (!isPendingAttachment(attachment.storagePath)) continue
            val id = pendingId(attachment.storagePath)
            referenced.add(id)
            if (staging.metadata(id, owner) == null) onMissingBytes(note, attachment)
        }
        if (!enabled) return LocalCommitResult.Applied(false)
        val upload = runCatching { syncNoteAttachments(note, commitToken) }.getOrElse { error ->
            AppLog.warn(TAG, "Resuming staged attachment upload failed", error)
            return LocalCommitResult.Applied(false)
        }
        val synced = when (upload) {
            is LocalCommitResult.Applied -> upload.value
            LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
        }
        if (attachmentsKey(note.attachments) == attachmentsKey(synced.attachments)) {
            return LocalCommitResult.Applied(false)
        }
        // The row write runs inside the originating token's commit, and a refused one both skips the
        // write and ends the caller's loop.
        val stored = runCatching {
            LocalCommitGate.commit(commitToken) {
                noteDao.updateNote(synced.toNoteEntity())
                Unit
            }
        }
        val outcome = stored.getOrNull() ?: return LocalCommitResult.Applied(false)
        return when (outcome) {
            is LocalCommitResult.Applied -> LocalCommitResult.Applied(true)
            LocalCommitResult.StaleGeneration -> LocalCommitResult.StaleGeneration
        }
    }

    /**
     * Deletes R2 objects the server already marked pending-deleted for tombstoned notes.
     * Skips [skipNoteId] (in-flight restores), and preserves the historical remote-failure policy:
     * listing, blob-delete and purge failures are all swallowed exactly as they always were.
     *
     * [commitToken] is the generation of the sync run this sweep belongs to, and it is *threaded in*
     * rather than captured here: the sweep must not mint its own authority, and the caller has held
     * this token since before the uploads that produced the tombstones being swept.
     *
     * [metadataIdentity] is the same run's immutable account/credential snapshot, and it is likewise
     * threaded in rather than captured: **one identity for the whole sweep**, used by the listing and
     * by every purge, so a session change part-way through cannot retarget the later calls. Identity
     * answers *who*; the per-invocation grants below answer *whether this mutation may start now*.
     * Both are required, and neither substitutes for the other.
     *
     * R18.1: the sweep deletes objects the *server* reports, so it requires a token with an originating
     * account ([commitToken]) — a null origin is refused before the listing, because a sweep that
     * cannot say whose rows these are must not read or delete any of them.
     *
     * **Temporal contract.** A decision taken before a suspension does not authorize a later mutation
     * or a success/no-op return after it. Every suspension in this function — the listing, the remote
     * context, each blob delete, each purge — is followed by [validateGeneration] before anything else
     * happens, and the last of those decisions is what the final return reports. Those decisions are
     * moments, not authority: each actual object deletion, and the purge that follows it, takes its
     * own one-shot [LocalCommitGate.RemoteMutationAuthorization] from this same token immediately
     * before it goes out (see [sweepPendingDeletedRecord], which owns that ordering per record), so
     * nothing here can start a remote call merely because a *check* passed earlier. An empty listing,
     * a swallowed listing failure, an all-skipped sweep, a delete that completed across the boundary
     * and a purge that completed or failed across it therefore all end in
     * [LocalCommitResult.StaleGeneration] instead of a false `Applied`.
     *
     * The skip predicate runs first, and deliberately: a record skipped for an in-flight restore must
     * stay untouched — and it still takes no authorization, because nothing is invoked for it. That is
     * no longer a hole, because the final decision covers the all-skipped case.
     */
    suspend fun sweepPendingDeletedAttachments(
        commitToken: LocalCommitToken,
        metadataIdentity: OperationRemoteIdentity,
        skipNoteId: (String) -> Boolean = { false },
    ): LocalCommitResult<Unit> {
        if (!enabled) return LocalCommitResult.Applied(Unit)
        val metadataClient = metadata ?: return LocalCommitResult.Applied(Unit)
        // R18.1: this sweep deletes objects *the server* reports as pending-deleted, so it is only
        // meaningful for an account — and it is only reachable from a download, which cannot run signed
        // out (its `uidProvider().getOrThrow()` and its identity capture both refuse before the tail).
        // A null origin here would mean the sweep cannot say whose objects it is deleting, so it fails
        // loudly instead of being repaired from the live session.
        check(commitToken.initiatingUid != null) {
            "Refusing an attachment sweep: it has no originating account to delete for."
        }

        // The listing is *required*, and its failure is not a sweep that found nothing — F-11. Turning a
        // transport, authentication or backend failure into `Applied` here reported an authoritative
        // "no attachments are pending deletion" for a question the server was never able to answer, and
        // the caller read that as a successful sweep. A thrown failure propagates instead, which is what
        // the row-level handlers below already do for a failed delete: the sync run reports the failure
        // and the next scheduled run tries again.
        //
        // the row-level handlers below already do for a failed delete: the sync run reports the failure
        // and the next scheduled run tries again.
        //
        // The listing suspends, so its outcome cannot be trusted to describe the generation that asked
        // it. Decide as soon as it is back: one decision here covers the empty listing and every row
        // that would otherwise follow.
        val pending = metadataClient.listPendingDeletedAttachments(metadataIdentity)
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }

        // One identity for the sweep, captured before the first delete goes out. It may refresh
        // credentials, so it is a suspension the boundary can land behind — and then no row may run.
        // R18 additionally proves it belongs to the originating operation, once for the whole sweep and
        // after that decision, so no record can delete another account's objects under a colliding id.
        val remote = blobTransport.captureContext()
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        requireContextBelongsToOperation(remote, commitToken)

        for (row in pending) {
            if (skipNoteId(row.noteId)) continue
            val record = sweepPendingDeletedRecord(commitToken, metadataIdentity, row, remote, metadataClient)
            if (record is LocalCommitResult.StaleGeneration) {
                return LocalCommitResult.StaleGeneration
            }
        }
        // Final authoritative decision. This is what covers an all-skipped sweep, which takes no row
        // decision at all; it intentionally doubles as defence-in-depth on the last row's tail rather
        // than tracking whether an earlier check already covered this return.
        return validateGeneration(commitToken)
    }

    /**
     * One pending-deleted record's two remote mutations, each under its own one-shot authorization.
     *
     * A helper for the same reason [syncNoteAttachments] is one function per note: the authority rules
     * are per record, and keeping them beside the calls they authorize is what makes the ordering
     * reviewable. The caller owns the listing, the loop, the skip predicate and the final decision.
     *
     * Ordering, exactly: generation decision, delete grant, blob delete, generation decision, purge
     * grant, metadata purge, generation decision. The deletion and the purge take *separate* grants —
     * one authorization covers exactly one invocation, a deletion that completed does not authorize
     * its purge, and each grant is taken immediately before the call it names rather than before long
     * unrelated work.
     *
     * Returns [LocalCommitResult.StaleGeneration] the moment the dataset is gone: no later mutation,
     * and none at all once a decision has refused. Returns `Applied` when the record is done or when
     * its deletion failed, which is the historical policy — a failed deletion skips its own purge and
     * the sweep carries on to the next record. A transport failure is never aliased to stale.
     */
    private suspend fun sweepPendingDeletedRecord(
        commitToken: LocalCommitToken,
        metadataIdentity: OperationRemoteIdentity,
        row: PendingDeletedAttachment,
        remote: AttachmentRemoteContext,
        metadataClient: AttachmentRemoteMetadata,
    ): LocalCommitResult<Unit> {
        // A decision before the record's first mutation, kept from 3D.3H.1. It is not made redundant by
        // the grant below: the grant is atomic against the *generation*, while this decision also
        // consults the gate's quarantine, so it is what refuses while a dataset isolation is unfinished.
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        // One grant for exactly this object deletion, taken here: the decision above is a moment, not
        // authority — the code between it and this invocation is ordinary code a second thread can
        // advance the generation inside. The grant is created under the same lock isolation takes,
        // which leaves only two orders and both are safe: isolation first issues zero requests,
        // authorization first means this one deletion was already issued and may run.
        val deletion = LocalCommitGate.authorizeRemoteMutationStart(
            commitToken,
            "attachment.pendingDeleteBlob(${row.noteId}/${row.attachmentId})",
        ) ?: return LocalCommitResult.StaleGeneration
        // Deliberately split from the purge: the delete suspends, and the purge is a *fresh* mutation
        // that must not start once the boundary has landed behind the delete.
        val deleted = runCatching {
            deletion.consumeOnce { blobTransport.delete(remote, row.noteId, row.attachmentId) }
        }
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        // Historical: a failed delete skips its own purge and the sweep carries on.
        if (deleted.isFailure) return LocalCommitResult.Applied(Unit)
        // A *separate* grant, never the deletion's: the purge is a fresh remote mutation, and one
        // authorization covers exactly one invocation. A completed deletion does not authorize it, and
        // the same decision-then-call window would otherwise sit between them.
        val purge = LocalCommitGate.authorizeRemoteMutationStart(
            commitToken,
            "attachment.pendingDeleteMetadata(${row.noteId}/${row.attachmentId})",
        ) ?: return LocalCommitResult.StaleGeneration
        runCatching {
            purge.consumeOnce { metadataClient.purgeDeleted(metadataIdentity, row.attachmentId, row.noteId) }
        }
        // The purge suspends too, so no later record and no success return may follow it without a
        // decision of its own.
        return validateGeneration(commitToken)
    }

    /**
     * Re-drives uploads for bytes staged before a restart, and drops staged bytes no live note
     * references any more.
     *
     * Staged bytes are only released when no note claims them: ageing them out would delete the
     * only copy of an image belonging to a note the user can still see. Bytes referenced by a note
     * whose staging is gone (older builds staged in memory) are reported through [onMissingBytes]
     * so the caller can surface a recoverable broken attachment instead of silently erasing it.
     *
     * [commitToken] is the **enclosing download's** generation, threaded in rather than captured here
     * for the same reason [hydrateAllNotes]'s is: this reconciliation belongs to the run that asked
     * for it, and a re-capture would launder an old run's aftermath into the replacement dataset.
     *
     * **Temporal contract.** Every suspension below is followed by an authoritative decision under the
     * originating token before this reports anything, because a decision taken before a suspension
     * cannot authorize a mutation or a success return after it:
     *
     *  - the note listing, whose failure is still a normal no-op, but only after the token has said
     *    the dataset is the one that asked;
     *  - each resumed upload, which takes its own grant inside [syncNoteAttachments];
     *  - the local row write, which is account-owned state and runs inside the token's commit;
     *  - the trailing release of unreferenced staged bytes, which deletes the only copy of those bytes
     *    and so is fenced exactly like the row write;
     *  - the final return, which is an explicit decision rather than an assumed `Applied`.
     *
     * So an empty no-op, a stale listing, an upload refused mid-loop and a completed upload whose
     * dataset then vanished all end in [LocalCommitResult.StaleGeneration] rather than a false success
     * that lets the caller carry on with the rest of its run.
     */
    suspend fun reconcileStagedAttachments(
        commitToken: LocalCommitToken,
        onMissingBytes: (Note, Attachment) -> Unit = { _, _ -> },
    ): LocalCommitResult<Int> {
        val owner = ownerId()
        val notes = runCatching { noteDao.getAllNotesForBackup().map { it.toNote() } }.getOrNull()
        // Historical policy, preserved: an unreadable note table is a normal no-op, not a boundary.
        // It is still a return that follows a suspension, so it takes a decision first.
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        if (notes == null) return LocalCommitResult.Applied(0)

        val referenced = mutableSetOf<String>()
        var resumed = 0

        for (note in notes) {
            when (val outcome = resumeStagedUploadsFor(note, commitToken, owner, referenced, onMissingBytes)) {
                is LocalCommitResult.Applied -> if (outcome.value) resumed++
                // The reconciliation's dataset is gone, so no later note is processed either.
                LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
            }
        }

        // Anything staged that no note references is unreachable — the note was discarded or the
        // attachment removed while the release did not land. The listing suspends and the release is
        // destructive local state, so the read is taken first, the token decides, and only then are
        // the deletions committed under it.
        val staged = staging.list(owner)
        val unreferenced = staged.filterNot { it.attachmentId in referenced }
        if (validateGeneration(commitToken) is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        if (unreferenced.isNotEmpty()) {
            when (LocalCommitGate.commit(commitToken) {
                for (entry in unreferenced) {
                    cache.remove(owner, entry.attachmentId)
                    staging.release(entry.attachmentId, owner)
                }
                LocalCommitResult.Applied(Unit)
            }) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
            }
        }
        // The final decision covers the last suspension and the release batch above, so a no-op run
        // whose dataset vanished is reported as stale rather than as work that finished.
        return when (validateGeneration(commitToken)) {
            is LocalCommitResult.Applied -> LocalCommitResult.Applied(resumed)
            LocalCommitResult.StaleGeneration -> LocalCommitResult.StaleGeneration
        }
    }

    /**
     * Moves bytes staged while signed out into [newOwnerId]'s namespace at sign-in.
     *
     * Guest and signed-in notes share one Room database on these clients, so a note written before
     * signing in keeps its attachments afterwards; without this its staged bytes would sit in a
     * namespace the upload path no longer looks at.
     */
    suspend fun adoptGuestStagedAttachments(newOwnerId: String) {
        if (newOwnerId == GUEST_STAGING_OWNER) return
        for (staged in staging.list(GUEST_STAGING_OWNER)) {
            val bytes = staging.readBytes(staged.attachmentId, GUEST_STAGING_OWNER) ?: continue
            val moved = staging.stage(
                attachmentId = staged.attachmentId,
                ownerId = newOwnerId,
                noteId = staged.noteId,
                bytes = bytes,
                mimeType = staged.mimeType,
            )
            if (moved != null) {
                cache.remove(GUEST_STAGING_OWNER, staged.attachmentId)
                staging.release(staged.attachmentId, GUEST_STAGING_OWNER)
            }
        }
    }

    /** Drops cached bytes so one account's images cannot be read from another's session. */
    suspend fun clearStagingCache() {
        cache.clear()
    }

    suspend fun readAttachmentBytes(attachment: Attachment): ByteArray? {
        return when {
            isPendingAttachment(attachment.storagePath) ->
                loadStaged(ownerId(), pendingId(attachment.storagePath))?.bytes
            isFileAttachment(attachment.storagePath) -> localStorage.readBytes(attachment.storagePath)
            isR2Attachment(attachment.storagePath) -> if (enabled) {
                runCatching {
                    blobTransport.download(
                        context = blobTransport.captureContext(),
                        noteId = attachment.noteId.toString(),
                        attachmentId = attachment.id,
                    )
                }.getOrNull()
            } else {
                null
            }
            else -> null
        }
    }

    private suspend fun loadStaged(owner: String, pendingId: String): PendingAttachment? {
        cache.get(owner, pendingId)?.let { return it }
        val staged = staging.metadata(pendingId, owner) ?: return null
        val bytes = staging.readBytes(pendingId, owner) ?: return null
        val value = PendingAttachment(bytes, staged.mimeType)
        cache.put(owner, pendingId, value)
        return value
    }

    private fun pendingId(storagePath: String): String =
        storagePath.removePrefix(ATTACHMENT_PENDING_PREFIX)

    private companion object {
        const val TAG = "AttachmentSync"
    }
}
