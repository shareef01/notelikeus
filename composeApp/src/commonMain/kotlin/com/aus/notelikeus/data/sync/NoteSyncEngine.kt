package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.sync.DatasetAuthority
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.attachmentsKey
import com.aus.notelikeus.data.attachments.decodeAttachments
import com.aus.notelikeus.data.attachments.encodeAttachments
import com.aus.notelikeus.data.attachments.isFileAttachment
import com.aus.notelikeus.data.attachments.isPendingAttachment
import com.aus.notelikeus.data.migration.AccountUidBridge
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.toChecklistItemEntity
import com.aus.notelikeus.data.mapper.toLabel
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.RemoteIdentityProvider
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.CancellationException
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The cloud returned no notes for an account that is known to have them.
 *
 * A fetch that fails open — an expired token, a truncated page, an offline transport returning a
 * default — loses data in both directions. [NoteSyncEngine.downloadAllNotes] treats an absent note
 * as deleted-elsewhere and removes the local copy; [NoteSyncEngine.uploadAllNotes] reads the same
 * empty result as "no remote is newer" and pushes every local note over the cloud copy. Failing the
 * sync is the safe answer in both; the next successful one reconciles normally.
 */
class SuspectEmptyCloudException(
    val knownCloudNoteCount: Int
) : Exception(
    "Cloud returned no notes but $knownCloudNoteCount were expected — refusing to delete local " +
        "copies. Check the connection or sign in again."
)

/**
 * A full snapshot came back holding fewer notes than the server says the account has.
 *
 * The dangerous case [SuspectEmptyCloudException] does not cover. That guard only fires when the
 * cloud returns *nothing*; a snapshot that lost some of its rows still looks like a perfectly good
 * library, and [NoteSyncEngine.downloadAllNotes] reads every previously-known id missing from it as
 * deleted-elsewhere — deleting the local copy, writing a tombstone, and propagating that deletion
 * to every other device. Nothing about the payload distinguishes "these notes were deleted" from
 * "these notes did not arrive", so only a count the server produced separately can tell them apart.
 *
 * Refusing costs one skipped sync; the next successful one reconciles normally.
 */
class IncompleteCloudSnapshotException(
    val expectedNoteCount: Int,
    val receivedNoteCount: Int,
) : Exception(
    "Cloud snapshot is incomplete: the server reports $expectedNoteCount notes but only " +
        "$receivedNoteCount arrived — refusing to delete local copies. Check the connection or " +
        "try again."
)

/**
 * Local sync state still belongs to a different Google account than the current session.
 *
 * Native note ids are small autoincrements and tombstones are keyed by those ids, so applying
 * leftover deletes or uploads under the new uid can erase or overwrite the wrong cloud notes.
 * [LocalAccountIsolator] wipes the device first; the engine refuses rather than mixing.
 */
class WrongAccountSyncException(
    val lastMergedUserId: String,
    val currentUserId: String,
) : Exception(
    "This device still has another account's notes. Sign out and back in to isolate it before syncing."
)

/**
 * An operation that started under one account resumed after a different one signed in.
 *
 * Each operation here reads the account once, at the top, and then crosses network and database
 * suspension points before writing device-local state. That state is keyed by note id — a small
 * per-device autoincrement — and by caller-generated attachment id, so both routinely collide
 * across accounts: B's first note very plausibly reuses the id A was uploading, and an attachment
 * id is a string any client can repeat. Without this check A's server timestamp, A's confirmed
 * revision and A's attachment paths all land on B's rows.
 *
 * The sign-out path cancels the sync coordinator, but cancellation is cooperative: a request that
 * has already been sent still completes, and a cancelled coroutine still runs to its next
 * suspension point — which can be after a write. Re-reading the session before the write is what
 * actually makes the commit safe.
 */
class AccountChangedDuringSyncException(
    val startedAs: String,
    val nowSignedInAs: String,
) : Exception(
    "Sync started as account $startedAs but the session is now $nowSignedInAs — refusing to write " +
        "another account's local state."
)

/**
 * Policy-only cloud-sync engine.
 *
 * Broken cycle: NoteSyncEngine now depends on DAOs instead of the high-level Repository,
 * which in turn depends on the SyncCoordinator (implementing this engine).
 */
class NoteSyncEngine(
    /**
     * The identity-bound note transport. Typed as [IdentityBoundNoteTransport] rather than
     * [CloudNoteTransport] so this cannot compile against a `uid`-keyed transport at all: the
     * `uid`-keyed call is what resolves its bearer from the live session at send time, which is F-1.
     * There is no `is IdentityBoundNoteTransport` check and no fallback — the compiler is the fence.
     */
    private val transport: IdentityBoundNoteTransport,
    /**
     * Captures the [OperationRemoteIdentity] a protected operation executes under, once per logical
     * operation, from that operation's originating [LocalCommitToken].
     *
     * Mandatory and non-nullable: a nullable or defaulted provider would be an opt-out from identity
     * binding, and the engine would then have to fall back to the live session — which is the defect.
     * [capture] returns `null` to *refuse*, and that refusal is an abandonment, never a fallback.
     */
    private val remoteIdentityProvider: RemoteIdentityProvider,
    private val noteDao: NoteDao,
    private val labelDao: LabelDao,
    private val syncStateStore: NoteSyncStateStore,
    private val uidProvider: suspend () -> Result<String>,
    private val platform: String = "android",
    /**
     * Wraps a multi-statement block in a database transaction. Defaults to a no-op so existing
     * tests that use fake (in-memory) DAOs continue to work without changes; production DI
     * modules supply the real Room [androidx.room.immediateTransaction] wrapper.
     */
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    accountUidBridge: AccountUidBridge? = null,
    /**
     * Wall clock, injectable so tests can observe *when* the engine reads it. That matters for
     * [reconcileUploads], whose correctness is entirely about reading it before the note snapshot
     * rather than after — a property no assertion on the result can express.
     */
    private val now: () -> Long = { DateUtils.currentTimeMillis() },
    private val attachmentSync: AttachmentSyncService? = null,
    /**
     * Test hook for attachment GC. Production uses [attachmentSync].
     *
     * Must never run before authoritative server note deletion succeeds, and must never run inside
     * the commit gate: the object deletes are network work.
     *
     * [commitToken] is the generation the GC belongs to. It goes to the token-aware
     * [com.aus.notelikeus.data.attachments.AttachmentSyncService.deleteAttachmentsForNote], which
     * snapshots its remote identity at entry and refuses the whole removal when that dataset is
     * gone — so a session that changed before this point makes the deletion a no-op instead of
     * re-targeting it at whatever account is signed in by then.
     *
     * The cleanup's own [LocalCommitResult] is part of the seam rather than swallowed inside it.
     * A refusal there means the originating dataset is gone, which its callers must tell apart from
     * a *thrown* cleanup failure: the first ends the enclosing operation, the second is retried on
     * the next sync. Signing this as `Unit` is exactly what made those two indistinguishable before
     * 3D.3G, and it is the regression 3D.4B.1 later had to undo.
     */
    private val deleteNoteAttachments: suspend (
        noteId: Long,
        attachments: List<Attachment>,
        commitToken: LocalCommitToken,
    ) -> LocalCommitResult<Unit> = { noteId, attachments, commitToken ->
        attachmentSync?.deleteAttachmentsForNote(noteId, attachments, commitToken)
            ?: LocalCommitResult.Applied(Unit)
    },
    /**
     * Test-only seam: parks an operation in the window between its last local generation decision and
     * the remote-start authorization that follows it, so a concurrency test can interleave account
     * isolation into exactly that window from a real second thread.
     *
     * It exists because the defect 3D.4A fixes is a *scheduling* fact, not a missing branch: the window
     * is ordinary code between the gate release and the request, so a test that cannot park inside it
     * can only assert against a single-threaded schedule — which would pass on the unfixed code too.
     * No production caller sets it, and it reorders nothing: the authorization still happens
     * immediately after it returns.
     */
    private val beforeRemoteMutationStart: suspend (String) -> Unit = {},
    /**
     * Excludes account isolation for the duration of a device-local commit. Defaulted to the shared
     * process-wide gate so no construction can be left unprotected; see [LocalCommitGate] for why a
     * per-object mutex would be the wrong shape.
     */
    private val localCommitGate: Mutex = LocalCommitGate.mutex,
    /**
     * Supplies the device-local dataset generation a sync operation belongs to.
     *
     * Captured **once at the start** of a migrated operation, before its remote work, and carried
     * unchanged into every local commit that follows — that generation, not the account uid, is what
     * authorizes a local write. Production injects the session-backed
     * [SessionLocalCommitTokenProvider], whose uid read is bracketed by two generation loads so a
     * sign-out/sign-in cannot pair one account's uid with the next dataset's generation.
     *
     * The default is generation-only and deliberately non-suspending: it cannot interleave with its
     * own call, so it can never capture a generation from a dataset it did not also start in.
     * `initiatingUid` is diagnostic — it is never a commit condition (see [LocalCommitToken]).
     */
    private val localCommitTokenProvider: LocalCommitTokenProvider = LocalCommitTokenProvider {
        LocalCommitGate.capture(initiatingUid = null)
    },
    /**
     * The durable dataset authority a *scheduled* command's origin is validated against — R15.1,
     * closing F-4.
     *
     * Nullable-and-refusing rather than nullable-and-permissive: an engine constructed without it
     * cannot execute scheduled work at all, which is the fail-closed direction. Only the platform
     * compositions that actually run queued work (Android's `SyncWorker`, desktop's coordinator)
     * supply it, and they pass the same instance the scheduler stamps commands from — two
     * authorities would be two datasets.
     */
    private val datasetEpochAuthority: DatasetEpochAuthority? = null,
) {
    private val accountUidBridge = accountUidBridge ?: AccountUidBridge(syncStateStore)

    suspend fun uploadAllNotes(): Result<Int> {
        return runCatching {
            // The dataset this run belongs to, captured before anything suspends. Every local commit
            // below is authorized by this generation — not by whichever account happens to be signed
            // in when the remote work finally returns.
            val commitToken = localCommitTokenProvider.capture()
            val uid = uidProvider().getOrThrow()
            requireCurrentAccount(uid)
            // This operation's one identity, captured from its own token before its first remote call.
            val remoteIdentity = remoteIdentityProvider.capture(commitToken) ?: return@runCatching 0
            requireOwnerBinding(uid, remoteIdentity)
            when (mergeCloudTombstones(commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> Unit
                // Same rule as the download: a refused merge means this dataset is gone, so stop before
                // the snapshot read and the uploads below start anything else for it.
                LocalCommitResult.StaleGeneration -> return@runCatching 0
            }
            val localNotes = noteDao.getAllNotesForBackup().map { it.toNote() }
            when (purgeLocalTombstonedNotes(localNotes, commitToken)) {
                is LocalCommitResult.Applied -> Unit
                // The dataset that decided is gone, so stop before the uploads below: they are
                // account-owned remote mutations decided from this dataset's state, and the caller's
                // contract ("notes uploaded") is honestly served by reporting none.
                LocalCommitResult.StaleGeneration -> return@runCatching 0
            }
            val notes = localNotes.filter { note ->
                val id = note.id ?: return@filter false
                !syncStateStore.isDeleted(id)
            }

            if (notes.isEmpty()) {
                // The metadata document is still an account-owned remote write, so it takes its own
                // authorization rather than riding on the earlier local decisions.
                val emptyMetaMutation = "upload.writeSyncMeta(empty)"
                beforeRemoteMutationStart(emptyMetaMutation)
                val emptyMeta = LocalCommitGate.authorizeRemoteMutationStart(commitToken, emptyMetaMutation)
                    ?: return@runCatching 0
                emptyMeta.consumeOnce { transport.writeSyncMeta(remoteIdentity, 0, platform) }
                return@runCatching 0
            }

            val remoteRecords = fetchCompleteSnapshot(remoteIdentity)

            // The same hazard downloadAllNotes guards against, pointing the other way. Here an
            // empty fetch does not delete anything directly — it empties the timestamp maps below,
            // and cloudWinsConflict reads a null remote timestamp as "local wins". Every local note
            // would then be pushed over whatever the cloud actually holds, discarding newer edits
            // made on another device. A transport that fails open (an empty cached snapshot rather
            // than throwing) makes that reachable, so refuse the sync instead; the next successful
            // one reconciles normally.
            if (accountUidBridge.isSameAccountAsLastMerge(uid)) {
                val unexplained = unexplainedMissingCloudIds(syncStateStore.knownCloudIds())
                if (remoteRecords.isEmpty() && unexplained.isNotEmpty()) {
                    throw SuspectEmptyCloudException(unexplained.size)
                }
            }

            val remoteTimestamps = remoteRecords.associate { it.noteId to (it.clientTimestamp ?: 0L) }
            val remoteServerTimestamps = remoteRecords.associate { it.noteId to it.serverUpdatedAt }

            var uploaded = 0
            val toPush = mutableListOf<Note>()

            for (note in notes) {
                val noteId = note.id ?: continue
                val remoteServerTs = remoteServerTimestamps[noteId]
                val remoteTs = remoteTimestamps[noteId]
                if (cloudWinsConflict(remoteServerTs, note.serverUpdatedAt, remoteTs, note.timestamp)) {
                    continue
                }
                toPush.add(note)
            }

            if (toPush.isNotEmpty()) {
                when (putNotes(toPush, commitToken, remoteIdentity)) {
                    is LocalCommitResult.Applied -> uploaded = toPush.size
                    // The batch is already on the account's cloud, but the dataset that issued it is
                    // gone: nothing about it may be recorded locally, and the sync metadata below must
                    // not be written for a dataset that no longer exists.
                    LocalCommitResult.StaleGeneration -> return@runCatching 0
                }
            }

            val uploadMetaMutation = "upload.writeSyncMeta"
            beforeRemoteMutationStart(uploadMetaMutation)
            val uploadMeta = LocalCommitGate.authorizeRemoteMutationStart(commitToken, uploadMetaMutation)
                ?: return@runCatching uploaded
            uploadMeta.consumeOnce { transport.writeSyncMeta(remoteIdentity, eligibleNoteCount(), platform) }
            uploaded
        }
    }

    suspend fun reconcileUploads(): Result<Int> {
        return runCatching {
            // Captured before the snapshot, the tombstone merge and the uploads below.
            val commitToken = localCommitTokenProvider.capture()
            val uid = uidProvider().getOrThrow()
            if (!accountUidBridge.isSameAccountAsLastMerge(uid)) {
                return@runCatching 0
            }
            // The operation's one remote identity, captured from the token above once the cheap
            // "is this still the merged account" guard has passed and before any remote work.
            val remoteIdentity = remoteIdentityProvider.capture(commitToken) ?: return@runCatching 0
            requireOwnerBinding(uid, remoteIdentity)
            // Both marks are taken before anything is read, and the ordering matters. `highWater`
            // becomes the next run's `since`, and the filter below is `timestamp > since` — so an
            // edit saved after the snapshot but before this call would carry a timestamp <=
            // highWater *and* be absent from that snapshot, and every future reconcile would skip
            // it. Reading the clock early only risks re-examining a note that was already pushed,
            // which costs one comparison; reading it late silently drops the edit.
            val since = syncStateStore.lastReconciledAt()
            val highWater = now()

            when (mergeCloudTombstones(commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> Unit
                // Same rule as the other two callers: a refused merge means this dataset is gone, so
                // nothing below — snapshot, purges, per-note uploads, sync meta — may start for it.
                LocalCommitResult.StaleGeneration -> return@runCatching 0
            }
            val localNotes = noteDao.getAllNotesForBackup().map { it.toNote() }
            when (purgeLocalTombstonedNotes(localNotes, commitToken)) {
                is LocalCommitResult.Applied -> Unit
                // Same rule as the other two callers: a stale purge means this dataset is gone, so the
                // per-note uploads below and the sync-meta write must not start. Nothing has been
                // uploaded at this point, so reporting zero is exact.
                LocalCommitResult.StaleGeneration -> return@runCatching 0
            }

            val changed = localNotes.filter { note ->
                val id = note.id ?: return@filter false
                note.timestamp > since && !syncStateStore.isDeleted(id)
            }

            // Belongs to this account: the lastMergedUserId check above already returned otherwise.
            val knownCloudIds = syncStateStore.knownCloudIds()

            var uploaded = 0
            if (changed.isNotEmpty()) {
                val remoteRecords = fetchCompleteSnapshot(remoteIdentity)
                val remoteMap = remoteRecords.associateBy { it.noteId }

                for (note in changed) {
                    val noteId = note.id ?: continue
                    val remote = remoteMap[noteId]
                    // A null remote usually means "never synced", the legitimate answer for a new note,
                    // so this cannot key off null alone the way the collection-level guards do. But when
                    // the last full download recorded this id as present in the cloud and no tombstone
                    // has since explained its absence — mergeCloudTombstones ran above, and a genuine
                    // remote delete leaves one — a failed-open fetch is likelier than the document
                    // vanishing. Pushing on that null would resolve the conflict in local's favour and
                    // overwrite whatever is actually there.
                    if (remote == null && noteId in knownCloudIds) {
                        throw SuspectEmptyCloudException(knownCloudIds.size)
                    }
                    val remoteServerTs = remote?.serverUpdatedAt
                    val localServerTs = note.serverUpdatedAt
                    val remoteTs = remote?.clientTimestamp
                    if (cloudWinsConflict(remoteServerTs, localServerTs, remoteTs, note.timestamp)) {
                        continue
                    }
                    // A refused result means this dataset is gone: stop before another note is uploaded
                    // and before the sync-meta write and the reconcile marker below. Notes whose results
                    // were applied before this point keep their count — the upload of *this* note is
                    // already on the account's cloud, it just cannot be recorded against this dataset.
                    when (putNote(note, commitToken, remoteIdentity)) {
                        is LocalCommitResult.Applied -> uploaded++
                        LocalCommitResult.StaleGeneration -> return@runCatching uploaded
                    }
                }
            }

            val reconcileMetaMutation = "reconcile.writeSyncMeta"
            beforeRemoteMutationStart(reconcileMetaMutation)
            val reconcileMeta = LocalCommitGate.authorizeRemoteMutationStart(commitToken, reconcileMetaMutation)
                ?: return@runCatching uploaded
            reconcileMeta.consumeOnce { transport.writeSyncMeta(remoteIdentity, eligibleNoteCount(), platform) }
            // The reconcile marker is device-local state belonging to the dataset this run started
            // in. If that dataset is gone, advancing the marker would tell the *new* dataset that a
            // window it never examined has been reconciled — so the continuation is discarded and the
            // next sync re-examines the same window (an idempotent re-upload, never a lost one).
            when (commitLocally(commitToken) { syncStateStore.markReconciled(highWater) }) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return@runCatching uploaded
            }
            uploaded
        }
    }

    suspend fun uploadNote(noteId: Long): Result<Unit> =
        uploadNoteWithToken(noteId, localCommitTokenProvider.capture())

    /**
     * The single-note upload for a command that arrived from the delayed-sync queue.
     *
     * Exists so queued work cannot reach the fresh-capturing entry above. Capturing a token there
     * would authorize a command from a replaced dataset against whatever is current when the worker
     * finally runs — the F-4 defect — so the scheduled path resolves the command's *own* epoch into
     * a token first, and abandons the command when that dataset is gone.
     */
    suspend fun uploadNoteFromScheduledWork(
        noteId: Long,
        origin: ScheduledWorkOrigin,
    ): ScheduledWorkOutcome =
        runScheduledWork(origin) { commitToken -> uploadNoteWithToken(noteId, commitToken) }

    private suspend fun uploadNoteWithToken(
        noteId: Long,
        commitToken: LocalCommitToken,
    ): Result<Unit> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            requireCurrentAccount(uid)
            // One identity for this upload *and* for the delegated delete below, captured from the
            // token this upload owns at entry — the delegation must never mint a newer one.
            val remoteIdentity = remoteIdentityProvider.capture(commitToken) ?: return@runCatching
            requireOwnerBinding(uid, remoteIdentity)
            when (refreshCloudTombstone(noteId, commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> Unit
                // The dataset that asked is gone, so nothing below it may start: not the deleted-note
                // delegation, not the remote note read, not the upload.
                LocalCommitResult.StaleGeneration -> return@runCatching
            }
            if (syncStateStore.isDeleted(noteId)) {
                // Delegated with the token *and* identity this upload captured at entry, never fresh
                // ones: the continuation belongs to the generation that began the upload, and A's
                // delete must not go out bearing B's live credential.
                return deleteNoteWithToken(noteId, commitToken, remoteIdentity)
            }
            val note = noteDao.getNoteById(noteId)?.toNote()
                ?: return@runCatching
            val remote = transport.fetchNote(remoteIdentity, noteId)
            if (remote != null) {
                val remoteServerTs = remote.serverUpdatedAt
                val remoteTs = remote.clientTimestamp
                if (cloudWinsConflict(remoteServerTs, note.serverUpdatedAt, remoteTs, note.timestamp)) {
                    return@runCatching
                }
            }
            // The single-note upload ends here: a refusal means this dataset is gone, so the operation
            // stops with nothing recorded. Branching rather than discarding keeps the propagation
            // explicit, so any continuation added below this line cannot silently outlive the dataset.
            when (putNote(note, commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return@runCatching
            }
        }
    }

    /**
     * Brings a permanently-deleted note back, locally and in the cloud.
     *
     * The restore marker is written first so a crash cannot re-import the cloud tombstone and
     * purge the row. The marker is cleared only after [CloudNoteTransport.restoreNote] confirms
     * the live remote note (tombstone removal + note write in one server transaction).
     *
     * Both halves of that pair are local, and they run either side of a remote suspension, so both
     * are committed under the one generation captured at entry: a dataset replaced in between must
     * not have the restore's markers — or the server stamp it just read back — applied to it.
     */
    suspend fun restoreNote(noteId: Long): Result<Unit> =
        restoreNoteWithToken(noteId, localCommitTokenProvider.capture())

    /** The restore, under a commit token **its caller owns**. See [uploadNoteFromScheduledWork]. */
    suspend fun restoreNoteFromScheduledWork(
        noteId: Long,
        origin: ScheduledWorkOrigin,
    ): ScheduledWorkOutcome =
        runScheduledWork(origin) { commitToken -> restoreNoteWithToken(noteId, commitToken) }

    private suspend fun restoreNoteWithToken(
        noteId: Long,
        commitToken: LocalCommitToken,
    ): Result<Unit> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            requireCurrentAccount(uid)
            // The one identity this restore sends under, captured from its own token before the local
            // bookkeeping and the grant below.
            val remoteIdentity = remoteIdentityProvider.capture(commitToken) ?: return@runCatching
            requireOwnerBinding(uid, remoteIdentity)

            // Pre-network bookkeeping as one commit. Stale here means the dataset is already gone,
            // and then there is no point reaching the cloud either: the restore is abandoned whole.
            val marked = commitLocally(commitToken) {
                syncStateStore.markRestored(noteId)
                syncStateStore.clearDeleted(listOf(noteId))
                syncStateStore.clearPendingAttachmentGc(noteId)
            }
            if (marked is LocalCommitResult.StaleGeneration) return@runCatching

            val note = noteDao.getNoteById(noteId)?.toNote()
                ?: return@runCatching
            // Remote, deliberately outside the gate: account isolation must never wait on a round
            // trip. Its *authority* is still the generation this restore was started in, so a
            // boundary that lands before the request refuses it here rather than publishing a
            // restore into an account that no longer belongs to this dataset.
            val restoreMutation = "restore.restoreNote($noteId)"
            beforeRemoteMutationStart(restoreMutation)
            val restore = LocalCommitGate.authorizeRemoteMutationStart(commitToken, restoreMutation)
                ?: return@runCatching
            val results = restore.consumeOnce { transport.restoreNote(remoteIdentity, note) }
            commitLocally(commitToken) {
                for ((id, result) in results) {
                    if (result.serverUpdatedAt != null) {
                        noteDao.updateServerTimestamp(id, result.serverUpdatedAt)
                    }
                    syncStateStore.updateKnownServerRevision(id, result.revision)
                }
                // Marker survives until the live remote note is confirmed.
                syncStateStore.clearRestored(listOf(noteId))
            }
        }
    }

    suspend fun deleteNote(noteId: Long): Result<Unit> =
        deleteNoteForToken(noteId, localCommitTokenProvider.capture())

    /** The delete, under a commit token **its caller owns**. See [uploadNoteFromScheduledWork]. */
    suspend fun deleteNoteFromScheduledWork(
        noteId: Long,
        origin: ScheduledWorkOrigin,
    ): ScheduledWorkOutcome =
        runScheduledWork(origin) { commitToken -> deleteNoteForToken(noteId, commitToken) }

    private suspend fun deleteNoteForToken(
        noteId: Long,
        commitToken: LocalCommitToken,
    ): Result<Unit> {
        return runCatching {
            // One token per call, minted inside the same failure boundary as the delete itself, and
            // the one remote identity that goes with it. Never re-captured here: a caller that
            // already owns a token — a scheduled command, or an upload's deleted-note branch — must
            // see its own generation honoured, not whichever one is current by the time this runs.
            val remoteIdentity = remoteIdentityProvider.capture(commitToken) ?: return@runCatching
            deleteNoteWithToken(noteId, commitToken, remoteIdentity).getOrThrow()
        }
    }

    /**
     * Runs one queued command, after resolving the authority it was queued under.
     *
     * Three steps, in this order and no other:
     *
     *  1. the durable epoch is loaded into memory — outside `LocalCommitGate`, because a disk read
     *     inside it would let a sign-in wait on file I/O;
     *  2. a cheap in-memory comparison refuses a command whose dataset is already gone, without even
     *     asking the session for an account;
     *  3. the epoch comparison and the token capture happen **together**, inside the gate, so
     *     isolation cannot land between the check that authorizes the command and the token that
     *     executes it.
     *
     * Step 3 is the part a worker-level epoch check cannot replace. A worker that only compares
     * epochs and then calls an ordinary engine method re-introduces the defect with one more step in
     * it: the method captures a fresh token for the *replacement* dataset, and the old command runs
     * against it. A refusal abandons the command outright — no local write, no remote grant, no
     * request.
     *
     * A session that cannot name an account is deliberately *not* folded into that refusal. It is a
     * credential condition that resolves itself the next time the user signs in, and treating it as
     * stale would silently drop a queued upload instead of retrying it.
     */
    private suspend fun runScheduledWork(
        origin: ScheduledWorkOrigin,
        operation: suspend (LocalCommitToken) -> Result<Unit>,
    ): ScheduledWorkOutcome {
        val authority = datasetEpochAuthority ?: return ScheduledWorkOutcome.RefusedStaleOrigin
        when (val current = authority.awaitAuthority()) {
            // Between datasets: the command's own epoch cannot be current, and neither can the one the
            // unfinished transition is heading for, so this refuses without comparing anything else.
            is DatasetAuthority.Isolating -> {
                AppLog.warn(
                    TAG,
                    "Refused queued work captured in dataset '${origin.datasetEpoch.value}': an " +
                        "isolation from '${current.from?.value ?: "<unknown>"}' to " +
                        "'${current.next.value}' is incomplete.",
                )
                return ScheduledWorkOutcome.RefusedStaleOrigin
            }

            is DatasetAuthority.Stable -> if (current.epoch != origin.datasetEpoch) {
                AppLog.warn(
                    TAG,
                    "Refused queued work captured in dataset '${origin.datasetEpoch.value}': this " +
                        "device is on '${current.epoch.value}'.",
                )
                return ScheduledWorkOutcome.RefusedStaleOrigin
            }
        }

        val uid = uidProvider()
        if (uid.isFailure) {
            return ScheduledWorkOutcome.Retryable
        }
        val commitToken = LocalCommitGate.resolveScheduledOrigin(
            origin = origin,
            currentUid = uid.getOrNull(),
            epochProvider = { authority.currentEpochOrNull() },
        ) ?: return ScheduledWorkOutcome.RefusedStaleOrigin

        return operation(commitToken).toScheduledWorkOutcome()
    }

    /**
     * A cancelled operation is re-queued but not counted as a failure.
     *
     * [NoteSyncEngine] wraps operations in `runCatching`, which swallows `CancellationException`
     * along with everything else, so the two arrive here looking the same. They are not the same:
     * one is a cloud that is not answering, the other is the user still typing.
     */
    private fun Result<Unit>.toScheduledWorkOutcome(): ScheduledWorkOutcome =
        fold(
            onSuccess = { ScheduledWorkOutcome.Applied },
            onFailure = { error ->
                if (error is CancellationException) {
                    ScheduledWorkOutcome.Cancelled
                } else {
                    ScheduledWorkOutcome.Retryable
                }
            },
        )

    /**
     * The delete itself, under a generation token **its caller owns** — and the remote identity that
     * goes with it. Captures neither.
     *
     * Split out so an operation that already holds a token can delegate here without recapturing.
     * [uploadNote]'s deleted-note branch belongs to the generation it captured at entry, so minting a
     * second, newer token at this point would re-authorise that continuation under whatever generation
     * is current by the time the branch is reached: the delete would then run for a dataset the upload
     * never belonged to, and its local tombstone work would land on the replacement dataset at
     * colliding ids. That is the laundering this split closes.
     *
     * [remoteIdentity] is passed for exactly the same reason and is the other half of that guarantee.
     * A delete that captured its own identity here would authenticate as whichever account is live
     * when the branch is reached, which is the F-1 defect reintroduced one level down: the caller's
     * generation would be honoured while the caller's *credentials* were not. So the originating
     * operation hands both down together, and [deleteNote]/[uploadNote] are the only two callers.
     *
     * The dataset the delete belongs to is the caller's token, captured before the tombstone write and
     * the server delete; every local commit inside carries it, and the blob GC at the end is refused
     * outright once it is stale.
     *
     * `internal` so the delegation can be driven directly in tests: through [uploadNote] the boundary
     * has to land between the refresh and this call, where there is no suspension to park on.
     */
    internal suspend fun deleteNoteWithToken(
        noteId: Long,
        commitToken: LocalCommitToken,
        remoteIdentity: OperationRemoteIdentity,
    ): Result<Unit> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            requireOwnerBinding(uid, remoteIdentity)
            requireCurrentAccount(uid)
            val note = noteDao.getNoteById(noteId)?.toNote()
            val deletedAt = now()

            // Capture baseline BEFORE local purge
            val baseline = syncStateStore.knownServerRevisionById()[noteId]

            // Local tombstone first, so a crash mid-network still treats the note as deleted. This is
            // device-local state of the dataset this operation started in — if that dataset is gone
            // the delete is abandoned whole, and nothing is written to the one that replaced it.
            when (commitLocally(commitToken) {
                // A later delete must cancel an in-flight restore so merge cannot resurrect it.
                syncStateStore.clearRestored(listOf(noteId))
                // Atomic baseline capture
                syncStateStore.markDeleted(noteId, deletedAt, baselineRevision = baseline)
            }) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return@runCatching
            }
            // The local tombstone above is committed, but that verdict authorizes nothing about the
            // network: the gate was released on return from the commit, and this is ordinary code. The
            // grant below is created under that same mutex, so isolation that wins it refuses the start
            // instead of letting a generation-N delete publish into the dataset that replaced it.
            val tombstoneMutation = "delete.writeTombstone($noteId)"
            beforeRemoteMutationStart(tombstoneMutation)
            val tombstoneWrite = LocalCommitGate.authorizeRemoteMutationStart(commitToken, tombstoneMutation)
                ?: return@runCatching
            tombstoneWrite.consumeOnce { transport.writeTombstone(remoteIdentity, noteId, deletedAt) }

            if (baseline != null) {
                // Immediate online delete path uses explicit base-revision API. A **separate** grant:
                // the tombstone grant above authorized exactly one invocation and was consumed by it,
                // so it cannot also cover this request — one authorization, one mutation.
                val serverDeleteMutation = "delete.deleteNote($noteId)"
                val serverDelete = LocalCommitGate.authorizeRemoteMutationStart(commitToken, serverDeleteMutation)
                    ?: return@runCatching
                val result = serverDelete.consumeOnce {
                    transport.deleteNote(remoteIdentity, noteId, baseRevision = baseline)
                }
                if (result == CloudNoteTransport.DeleteResult.Success) {
                    // Local bookkeeping only: the blob GC below is network work and runs after the
                    // gate is released, so account isolation can never wait on a round trip.
                    val recorded = commitLocally(commitToken) {
                        syncStateStore.clearKnownServerRevisions(listOf(noteId))
                        // Blob GC only after authoritative server deletion. Failure is retryable.
                        if (note != null && note.attachments.isNotEmpty()) {
                            syncStateStore.markPendingAttachmentGc(noteId, note.attachments.map { it.id })
                        }
                    }
                    if (recorded is LocalCommitResult.Applied && note != null && note.attachments.isNotEmpty()) {
                        // Outside the gate. The removal's own local half is fenced on this same
                        // token, and it snapshots its remote identity inside that call, so a session
                        // change before reaching here refuses it rather than re-targeting A's
                        // deletion at the new account's colliding ids.
                        // The cleanup's own result is part of the seam, so it is taken here rather
                        // than swallowed: `runCatching { … }.onSuccess` used to treat a *refused*
                        // cleanup as an applied one, because a nested StaleGeneration is still a
                        // successful `Result` — and the marker was then dropped for a dataset that no
                        // longer owned it. Three outcomes, kept apart exactly as the retry loop below
                        // keeps them (3D.4B.1 undid the collapse that used to flatten the first two):
                        //   Applied -> the cleanup ran for this dataset, so its marker may be dropped;
                        //   stale   -> the dataset that asked for this delete is gone, so the marker is
                        //              not cleared — not even attempted, because this operation does
                        //              not own it and the next sweep does;
                        //   thrown  -> historical behaviour, unchanged: the marker stays for the next
                        //              sweep, which is what re-attempts the blob GC.
                        val cleanup = runCatching {
                            deleteNoteAttachments(noteId, note.attachments, commitToken)
                        }
                        if (cleanup.isSuccess) {
                            when (cleanup.getOrThrow()) {
                                is LocalCommitResult.Applied -> {
                                    // Post-remote bookkeeping, still under the original token: only the
                                    // dataset that set the retry marker may clear it.
                                    commitLocally(commitToken) {
                                        syncStateStore.clearPendingAttachmentGc(noteId)
                                    }
                                }
                                LocalCommitResult.StaleGeneration -> Unit
                            }
                        }
                    }
                }
                // On Conflict/network failure, we keep the tombstone+baseline pending for next authoritative sync.
            } else {
                // Legacy / ambiguous / local-only delete
                // If it's local-only, we don't need to do anything remotely.
                // If remote exists but baseline is missing, we must let cloud win (on next sync).
                // Do not delete remote here.
            }
        }
    }

    suspend fun downloadAllNotes(): Result<Int> {
        return runCatching {
            // Captured before the tombstone merge and the snapshot fetch: everything this run writes
            // locally afterwards is authorized by the generation it started in.
            val commitToken = localCommitTokenProvider.capture()
            val uid = uidProvider().getOrThrow()
            requireCurrentAccount(uid)
            // The download's one remote identity: every read below — the tombstones, the snapshot, the
            // per-note restores — and every write it makes, use this and no other. Captured from the
            // token above, before the first remote call.
            val remoteIdentity = remoteIdentityProvider.capture(commitToken) ?: return@runCatching 0
            requireOwnerBinding(uid, remoteIdentity)
            val cloudTombstones = when (val merged = mergeCloudTombstones(commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> merged.value
                // The merge's dataset has been replaced, so this download stops before it reads, purges,
                // uploads or writes anything else for it — the cloud work the merge already did stands.
                LocalCommitResult.StaleGeneration -> return@runCatching 0
            }
            val localNotesBeforePurge = noteDao.getAllNotesForBackup().map { it.toNote() }
            val purgedIds = when (val purge = purgeLocalTombstonedNotes(localNotesBeforePurge, commitToken)) {
                is LocalCommitResult.Applied -> purge.value
                // The dataset that decided is gone, so the run stops here. Everything after this point
                // is either a write to that dataset or an account-owned remote mutation decided from its
                // state — the record loop's server deletes and tombstones, putNotes, the sync-meta
                // write. Nothing was reconciled for it, which is what the caller is told.
                LocalCommitResult.StaleGeneration -> return@runCatching 0
            }
            var changes = purgedIds.size
            // Before anything is compared, let alone deleted: a short snapshot must not reach the
            // reconciliation loops below, which read every absent known id as a remote deletion.
            val remoteRecords = fetchCompleteSnapshot(remoteIdentity)
            // Everything below writes device-local state (notes, labels, revision maps, known ids).
            // Another account may have signed in while that snapshot was on the wire.
            requireSameSession(uid)

            val labelMap = labelDao.getAllLabelsOnce()
                .associateBy { it.name.lowercase() }
                .toMutableMap()

            // Returns the commit result rather than a bare label: a refused label insert means this
            // download belongs to a dataset that is gone, and the record loop must stop rather than
            // build a note around a label that exists nowhere.
            suspend fun ensureLabel(name: String): LocalCommitResult<Label> {
                val key = name.trim().lowercase()
                labelMap[key]?.let { return LocalCommitResult.Applied(it.toLabel()) }
                // Creating a label is a device-local write decided from a remote snapshot, so it is
                // committed under the generation this *download* started in — the same token every
                // other local write in this run uses.
                val entity = com.aus.notelikeus.data.local.entity.LabelEntity(name = name.trim())
                return when (val inserted = commitLocally(commitToken) { labelDao.insertLabel(entity) }) {
                    is LocalCommitResult.Applied -> {
                        val label = entity.copy(id = inserted.value)
                        labelMap[key] = label
                        LocalCommitResult.Applied(label.toLabel())
                    }
                    LocalCommitResult.StaleGeneration -> LocalCommitResult.StaleGeneration
                }
            }

            val cloudNoteIds = mutableSetOf<Long>()
            // Locally-winning notes are collected and sent in one batch at the end. Pushing each
            // one as its own single-note commit inside the loop cost a round trip per note, while
            // uploadAllNotes batched the identical work.
            val toPushBack = mutableListOf<Note>()
            val allLocalNotes = localNotesBeforePurge.filter { note -> note.id !in purgedIds }
            val localNotesById = allLocalNotes.mapNotNull { note -> note.id?.let { it to note } }.toMap()

            // knownCloudIds belongs to whichever account last completed a download. Carrying it
            // across an account switch would read the new account's (legitimately empty) cloud as
            // "the previous account's notes were deleted" and remove them from this device.
            val isSameAccountAsLastMerge = accountUidBridge.isSameAccountAsLastMerge(uid)
            val previouslyKnownCloudIds =
                if (isSameAccountAsLastMerge) syncStateStore.knownCloudIds() else emptySet()

            // A whole collection vanishing is far more often a failed fetch than a real deletion:
            // a genuine remote delete leaves tombstones, which mergeCloudTombstones has already
            // applied above. Refuse to reconcile rather than delete notes on a bad read.
            val unexplainedMissing = unexplainedMissingCloudIds(previouslyKnownCloudIds)
            if (isSameAccountAsLastMerge &&
                remoteRecords.isEmpty() &&
                unexplainedMissing.isNotEmpty()
            ) {
                throw SuspectEmptyCloudException(unexplainedMissing.size)
            }

            val cloudRevisions = remoteRecords.associate { it.noteId to it.revision }
            val removedIds = previouslyKnownCloudIds - remoteRecords.map { it.noteId }.toSet()
            // Revision bookkeeping derived from the snapshot just fetched belongs to the dataset this
            // download started in. A refused commit means that dataset is gone, so the run stops here
            // rather than carrying on writing anything else for it.
            val revisions = commitLocally(commitToken) {
                syncStateStore.updateKnownServerRevisions(cloudRevisions)
                syncStateStore.clearKnownServerRevisions(removedIds)
                for (id in removedIds) {
                    if (syncStateStore.isDeleted(id)) {
                        syncStateStore.clearBaselineDeleteRevision(id)
                    }
                }
            }
            if (revisions is LocalCommitResult.StaleGeneration) return@runCatching changes

            for (record in remoteRecords) {
                val noteId = record.noteId

                if (syncStateStore.isDeleted(noteId)) {
                    val baseline = syncStateStore.baselineDeleteRevisionById()[noteId]
                    val remoteRevision = record.revision

                    if (baseline != null && baseline == remoteRevision) {
                        // Two account-owned requests, so two grants: the first authorizes the server
                        // delete and is consumed by it, and the tombstone publication takes its own
                        // only once that delete has actually succeeded. Their order is unchanged, and
                        // one grant never covers both invocations.
                        val recordDeleteMutation = "download.deleteNote($noteId)"
                        beforeRemoteMutationStart(recordDeleteMutation)
                        val recordDelete = LocalCommitGate.authorizeRemoteMutationStart(
                            commitToken,
                            recordDeleteMutation,
                        ) ?: return@runCatching changes
                        val result = recordDelete.consumeOnce { transport.deleteNote(remoteIdentity, noteId, baseline) }
                        if (result == CloudNoteTransport.DeleteResult.Success) {
                            val deletedAt = syncStateStore.deletedAtById()[noteId] ?: now()
                            // Issued only for the write that really follows: a non-success delete
                            // returns above without publishing a tombstone, and a request that will
                            // not happen gets no authorization.
                            val recordTombstoneMutation = "download.writeTombstone($noteId)"
                            beforeRemoteMutationStart(recordTombstoneMutation)
                            val recordTombstone = LocalCommitGate.authorizeRemoteMutationStart(
                                commitToken,
                                recordTombstoneMutation,
                            ) ?: return@runCatching changes
                            recordTombstone.consumeOnce { transport.writeTombstone(remoteIdentity, noteId, deletedAt) }
                            // The revision the server has just accepted a delete for is only dropped
                            // in the dataset that asked for the delete: a stale run must not clear the
                            // replacement dataset's bookkeeping for a colliding id.
                            val recorded = commitLocally(commitToken) {
                                syncStateStore.clearKnownServerRevisions(listOf(noteId))
                            }
                            // The server accepted this delete and its tombstone above stands — that work
                            // was already in flight when the boundary landed. But the dataset that asked
                            // for it is gone: stop before another record or the tail starts new work, and
                            // do not count a change this dataset cannot record.
                            if (recorded is LocalCommitResult.StaleGeneration) return@runCatching changes
                            changes++
                        }
                        continue
                    }

                    // Cloud wins (mismatch or missing baseline)
                    when (commitLocally(commitToken) {
                        syncStateStore.clearDeleted(listOf(noteId))
                        syncStateStore.clearBaselineDeleteRevision(noteId)
                    }) {
                        is LocalCommitResult.Applied -> Unit
                        // A refused commit here means the dataset is gone, so the note is not merged and
                        // the run stops instead of moving on to later records and the tail.
                        LocalCommitResult.StaleGeneration -> return@runCatching changes
                    }
                    // Let the remote note be merged/restored locally below
                }

                cloudNoteIds.add(noteId)
                // Resolve the record's labels before the row is built, so a refused label insert stops
                // the run here instead of being folded into the note.
                val recordLabels = mutableMapOf<String, Label>()
                for (name in record.labels) {
                    when (val ensured = ensureLabel(name)) {
                        is LocalCommitResult.Applied -> recordLabels[name] = ensured.value
                        LocalCommitResult.StaleGeneration -> return@runCatching changes
                    }
                }
                val cloudNote = record.toNote { name -> recordLabels.getValue(name) }
                val localNote = localNotesById[noteId]

                val cloudWins = if (localNote == null) {
                    true
                } else {
                    cloudWinsConflict(
                        record.serverUpdatedAt,
                        localNote.serverUpdatedAt,
                        record.clientTimestamp,
                        localNote.timestamp,
                    )
                }

                when {
                    localNote == null -> {
                        // A cloud note may only become a local row in the dataset that authorised
                        // this download. A refused commit means the snapshot belongs to a dataset
                        // that is gone, so the row is deliberately not created.
                        when (commitLocally(commitToken) { innerInsert(cloudNote) }) {
                            is LocalCommitResult.Applied -> changes++
                            // The dataset that authorised this download is gone, so the run stops: every
                            // later record and the whole tail would be new work for an extinct dataset.
                            LocalCommitResult.StaleGeneration -> return@runCatching changes
                        }
                    }
                    // The cloud copy winning does not mean it differs. In steady state both sides
                    // carry the same serverUpdatedAt and the same timestamp, so cloudWinsConflict
                    // answers "cloud" for every note in the library — and innerUpdate is not a
                    // cheap no-op, it rewrites the row and deletes and re-inserts every label
                    // cross-ref and checklist item. Without this check a download rewrites the
                    // whole library through SQLCipher on every sync and reports each note as a
                    // change, so the snackbar always claims the full note count.
                    cloudWins -> {
                        // The payload cannot speak for attachments, so it must not delete the ones
                        // only this device knows about.
                        val merged = cloudNote.keepingLocalOnlyAttachmentsOf(localNote)
                        if (!sameContent(merged, localNote)) {
                            when (commitLocally(commitToken) { innerUpdate(merged) }) {
                                is LocalCommitResult.Applied -> changes++
                                // Same rule as the insert above: a refused update means the run is over.
                                LocalCommitResult.StaleGeneration -> return@runCatching changes
                            }
                        }
                    }
                    else -> {
                        toPushBack += localNote
                        changes++
                    }
                }
            }

            for (localNote in allLocalNotes) {
                val noteId = localNote.id ?: continue
                if (noteId in cloudNoteIds) continue
                if (syncStateStore.isDeleted(noteId)) continue

                if (noteId in previouslyKnownCloudIds) {
                    // Split the phases: the local half is a device-local commit and belongs in the
                    // critical section, the tombstone write is a network call and must not be.
                    // The local half is *destructive* — it removes the row (cascading its label
                    // cross-refs and checklist items) and marks the id deleted — so it is authorized
                    // by the generation this download started in: a stale run must neither delete a
                    // row the replacement dataset owns under a colliding id nor write its deletion
                    // marker into that dataset's sync state.
                    val deletedAt = now()
                    when (commitLocally(commitToken) {
                        noteDao.deleteNote(localNote.toNoteEntity())
                        syncStateStore.markDeleted(noteId, deletedAt)
                    }) {
                        is LocalCommitResult.Applied -> {
                            // The local half is committed, but publishing the deletion is a separate
                            // account-owned request and takes its own authority: a refused grant means
                            // the dataset that decided is gone, so the run stops here rather than
                            // publishing an old generation's deletion into the replacement account.
                            val purgeTombstoneMutation = "download.purgeTombstone($noteId)"
                            beforeRemoteMutationStart(purgeTombstoneMutation)
                            val purgeTombstone = LocalCommitGate.authorizeRemoteMutationStart(
                                commitToken,
                                purgeTombstoneMutation,
                            ) ?: return@runCatching changes
                            purgeTombstone.consumeOnce { transport.writeTombstone(remoteIdentity, noteId, deletedAt) }
                            changes++
                        }
                        // The dataset that decided to purge this note is gone. Abandon the whole
                        // continuation — the tombstone included: publishing an old generation's
                        // deletion would spread it into the replacement dataset's account — and stop
                        // the run rather than moving on to another candidate or the tail.
                        LocalCommitResult.StaleGeneration -> return@runCatching changes
                    }
                    continue
                }

                toPushBack += localNote
                changes++
            }

            when (putNotes(toPushBack, commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> Unit
                // The push-back batch is already on the account's cloud (or there was nothing to push),
                // but the dataset that decided is gone: return before hydration, staged reconciliation,
                // attachment GC, the prune, the sync-meta write and the remaining tail commits, all of
                // which would be new work for it.
                LocalCommitResult.StaleGeneration -> return@runCatching changes
            }

            // Hydration's metadata read is a protected remote call, so it runs under this download's
            // own immutable identity — the same value every other remote step in this run uses, never a
            // fresh read of the live session and never a credential acquired inside the helper.
            val hydration = attachmentSync
                ?.hydrateAllNotes(commitToken, remoteIdentity)
                ?: LocalCommitResult.Applied(0)
            when (hydration) {
                is LocalCommitResult.Applied -> Unit
                // The dataset this hydration belongs to is gone, so the run stops before the staged
                // reconciliation, the attachment GC, the end-of-run bookkeeping, the prune, the
                // sync-meta write and the merged-user marker — all new work for it.
                LocalCommitResult.StaleGeneration -> return@runCatching changes
            }
            // Bytes staged before a restart or an outage upload here, under this download's own
            // token. A *thrown* failure keeps its historical meaning — the bytes stay staged and the
            // next sync retries — and is deliberately not aliased to stale. A refusal is different:
            // it means this download's dataset is gone, so the run stops before the attachment GC,
            // the end-of-run bookkeeping, the prune, the sync-meta write and the merged-user marker,
            // all of which would be new work for a dataset that no longer exists.
            val stagedReconciliation = runCatching {
                attachmentSync?.reconcileStagedAttachments(commitToken)
            }.getOrNull() ?: LocalCommitResult.Applied(0)
            when (stagedReconciliation) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return@runCatching changes
            }
            when (retryPendingAttachmentGc(commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> Unit
                // The dataset that scheduled the retries is gone, so the run stops before the
                // end-of-run bookkeeping, the prune, the sync-meta write and the merged-user marker —
                // all of it new work for a dataset that no longer exists.
                LocalCommitResult.StaleGeneration -> return@runCatching changes
            }

            // End-of-run bookkeeping for the dataset this download started in — the one uncomplicated
            // uid-only commit path migrated this phase. A refusal means that dataset is gone: the
            // run's remote work stands, and the replacement dataset keeps what it has already
            // recorded about its own cloud set.
            when (commitLocally(commitToken) { syncStateStore.setKnownCloudIds(cloudNoteIds) }) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return@runCatching changes
            }
            when (pruneExpiredTombstones(cloudNoteIds, cloudTombstones, commitToken, remoteIdentity)) {
                is LocalCommitResult.Applied -> Unit
                // The dataset that pruned is gone, so the run stops before the two remaining tail
                // writes: the sync-meta document would describe a library this dataset no longer
                // owns, and the merged-user marker would tell the replacement dataset it had merged
                // a cloud it never read.
                LocalCommitResult.StaleGeneration -> return@runCatching changes
            }
            val tailMetaMutation = "download.writeSyncMeta"
            beforeRemoteMutationStart(tailMetaMutation)
            val tailMeta = LocalCommitGate.authorizeRemoteMutationStart(commitToken, tailMetaMutation)
                ?: return@runCatching changes
            tailMeta.consumeOnce { transport.writeSyncMeta(remoteIdentity, eligibleNoteCount(), platform) }
            commitLocally(commitToken) { syncStateStore.setLastMergedUserId(uid) }

            changes
        }
    }

    suspend fun deleteAllCloudData(): Result<Int> {
        return runCatching {
            // Captured before the uid lookup and the remote wipe: the local clear below belongs to
            // this operation's generation, not to whoever holds the account by the time it runs.
            val commitToken = localCommitTokenProvider.capture()
            val uid = uidProvider().getOrThrow()
            // The wipe's identity, captured before its count read *and* before the wipe itself: the
            // destructive call must not borrow the live session's bearer if the session moves between
            // the two.
            val remoteIdentity = remoteIdentityProvider.capture(commitToken) ?: return@runCatching 0
            requireOwnerBinding(uid, remoteIdentity)
            val noteCount = transport.fetchNotes(remoteIdentity).size
            // The wipe is the most destructive remote mutation in the engine, so it is authorized like
            // every other one. A refusal means the account that asked for the wipe is no longer the one
            // signed in — and erasing the account that *is* signed in is not something remote progress
            // can authorize, so the wipe never starts.
            val wipeMutation = "wipe.deleteAllOwnedCloudData"
            beforeRemoteMutationStart(wipeMutation)
            val wipe = LocalCommitGate.authorizeRemoteMutationStart(commitToken, wipeMutation)
                ?: return@runCatching 0
            wipe.consumeOnce { transport.deleteAllOwnedCloudData(remoteIdentity) }
            // A wipe is the most destructive local write in the engine; if another account signed in
            // while the cloud deletion was in flight, clearing here would erase that account instead.
            // The same is true of a new generation of the *same* account, which uid equality cannot
            // see, so the clear is authorized by the token captured at entry. A refusal is the
            // correct asymmetric outcome: the cloud wipe already happened, but remote completion does
            // not authorize an old operation to mutate a new local generation — and nothing after this
            // point depends on the clear having run.
            commitLocally(commitToken) { syncStateStore.clear() }
            noteCount
        }
    }

    /**
     * Reads the whole library and refuses to hand back a snapshot the transport itself says is short.
     *
     * The check has to happen here rather than inside the transport because the decision is policy:
     * "fewer rows than the server counted" only matters to the code that would otherwise treat the
     * gap as a set of deletions. Transports that report no count (`null`) are unaffected.
     */
    private suspend fun fetchCompleteSnapshot(
        identity: OperationRemoteIdentity,
    ): List<CloudNoteRecord> {
        val snapshot = transport.fetchNotesSnapshot(identity)
        val expected = snapshot.authoritativeNoteCount
        if (expected != null && expected != snapshot.records.size) {
            throw IncompleteCloudSnapshotException(
                expectedNoteCount = expected,
                receivedNoteCount = snapshot.records.size,
            )
        }
        return snapshot.records
    }

    /**
     * Of the ids that were in the cloud last time, the ones whose absence now is *unaccounted for*.
     *
     * This is the distinction [SuspectEmptyCloudException] was always meant to draw. Both guards
     * used the whole known-id set, so an account whose notes were all genuinely deleted — the last
     * note deleted on another device, or the trash emptied there — looked exactly like a fetch that
     * had failed open, and the sync was refused. That refusal was not self-healing either:
     * `setKnownCloudIds` only runs at the end of a *successful* download, so the set that tripped
     * the guard was never updated and every later sync, upload included, failed the same way.
     *
     * A tombstone is the explanation. [mergeCloudTombstones] has already run by both call sites, so
     * a remotely-deleted note is locally tombstoned by the time this is asked, and only ids with no
     * tombstone at all count towards "the collection vanished for no reason".
     */
    private fun unexplainedMissingCloudIds(knownCloudIds: Set<Long>): Set<Long> =
        knownCloudIds.filterTo(mutableSetOf()) { !syncStateStore.isDeleted(it) }

    /**
     * `lastMergedUserId == null` is a first sync (guest notes may upload). A non-null value that
     * does not match [uid] means another account's tombstones and known ids are still on disk.
     */
    /**
     * The number of notes this account may store in the cloud, as the metadata document reports it.
     *
     * A named seam rather than three inline `noteDao.getCloudEligibleNoteCount()` calls, because the
     * call sits in the middle of each sync-metadata statement: inlined it pushes those lines past the
     * project's line-length limit, and a local temp per call site costs a line in two of the engine's
     * longest functions.
     */
    private suspend fun eligibleNoteCount(): Int = noteDao.getCloudEligibleNoteCount()

    private fun requireCurrentAccount(uid: String) {
        val last = syncStateStore.lastMergedUserId()
        if (last != null && !accountUidBridge.accountsMatch(last, uid)) {
            throw WrongAccountSyncException(lastMergedUserId = last, currentUserId = uid)
        }
    }

    /**
     * The account the operation read and the account its captured credential belongs to must agree.
     *
     * [OperationRemoteIdentity] is what every protected call now *is* authenticated as, and
     * [RemoteIdentityProvider.capture] proves the owner travelled with the credential by reading the
     * session either side of the token acquisition. The engine's `uid` is read separately, before that
     * — so this is the one remaining place the two could disagree, and it is checked before any
     * network work. Drift here is not a sync failure: it is an operation that can no longer say which
     * account it belongs to, so it refuses rather than authenticate as one and act for the other.
     */
    private fun requireOwnerBinding(uid: String, identity: OperationRemoteIdentity) {
        require(uid == identity.ownerId) {
            "A note operation began for account $uid but captured its remote identity for " +
                "${identity.ownerId} — refusing to run the two against each other."
        }
    }

    /**
     * Refuses to commit once the session has moved on.
     *
     * Called immediately before every device-local mutation that follows a suspension point, and
     * never before a *remote* call: [uid] is the account captured at the top of the operation, and
     * the remote half of that operation is separately bound to the credential captured for the same
     * account — see [requireOwnerBinding] and [remoteIdentityProvider] — so A's data keeps going to
     * A's cloud namespace whatever the live session does. This guard only stops A's results from
     * being written into B's local state.
     *
     * A vanished session counts as changed: if the provider can no longer name an account, there is
     * no account whose state these results could legitimately belong to.
     */
    private suspend fun requireSameSession(uid: String) {
        val current = uidProvider().getOrNull()
            ?: throw AccountChangedDuringSyncException(startedAs = uid, nowSignedInAs = "signed out")
        if (current != uid) {
            throw AccountChangedDuringSyncException(startedAs = uid, nowSignedInAs = current)
        }
    }

    /**
     * Runs a device-local commit under the generation the operation started in, refusing it once
     * that dataset is gone.
     *
     * The validity decision belongs to [LocalCommitGate], not to this engine: the token is validated
     * while the gate is held, so an isolation that lands first turns the whole mutation into
     * [LocalCommitResult.StaleGeneration] instead of a late write into the new account's rows, and an
     * isolation that lands after simply wipes what was written. A uid comparison cannot make that
     * call — after `A → sign out → A` the uid is identical on both sides of the boundary while the
     * dataset is not.
     *
     * Never wrap a network call in this: it is held across local database and preference work only,
     * so isolation waits for a write and never for a round trip.
     */
    private suspend fun <T> commitLocally(
        token: LocalCommitToken,
        block: suspend () -> T,
    ): LocalCommitResult<T> = LocalCommitGate.commit(token, block)

    /**
     * **Migration debt**: [LocalCommitGate] serialized with a uid check, still used by the engine
     * paths this phase did not migrate. New call sites should use the token-aware overload above.
     *
     * Serialization is right — the check below and the mutation have to be one critical section, or
     * a sign-in can land between them (see `AccountSwitchResumeTest`, "a sign-in racing the local
     * write cannot interleave with it"). The *validation* is the weak part: uid equality is not the
     * boundary, because the same account signing back in is a new generation whose uid still matches,
     * so a delayed write from the previous generation passes this check and lands on the new dataset.
     */
    private suspend fun <T> commitLocally(uid: String, block: suspend () -> T): T =
        localCommitGate.withLock {
            requireSameSession(uid)
            block()
        }

    internal fun cloudWinsConflict(
        remoteServerUpdatedAt: Long?,
        localServerUpdatedAt: Long?,
        remoteClientTimestamp: Long?,
        localClientTimestamp: Long,
    ): Boolean {
        if (remoteServerUpdatedAt != null && localServerUpdatedAt != null) {
            if (remoteServerUpdatedAt != localServerUpdatedAt) {
                return remoteServerUpdatedAt > localServerUpdatedAt
            }
            // Same confirmed revision on both sides. Only here does the client clock get a say, and
            // only as a tie-break: Room keeps the old serverUpdatedAt after a local edit, so a newer
            // local `timestamp` against an unchanged server stamp is exactly how a pending local edit
            // announces itself. The tie itself goes to the cloud so an unchanged note is not pushed
            // back up on every sync.
            return remoteClientTimestamp != null && remoteClientTimestamp >= localClientTimestamp
        }

        // Exactly one side has been confirmed by the server: that side wins outright, whatever the
        // client clocks say. A client `timestamp` is spoofable and skew-prone — an imported backup
        // or a device with a wrong clock can carry any value at all — so letting it decide against a
        // revision the server has already stamped is what the serverUpdatedAt field exists to
        // prevent. Reachable on a normal upgrade: MIGRATION_5_6 adds the column with no backfill, so
        // every note predating schema v6 reads null locally until its next upload, while the cloud
        // copy written by any current client has one. Mirrors the web client's
        // `shouldUploadOverRemote`, which has resolved it this way since the field was introduced.
        if (remoteServerUpdatedAt != null) return true
        if (localServerUpdatedAt != null) return false

        // Neither side confirmed: the client clock is all there is.
        return remoteClientTimestamp != null && remoteClientTimestamp > localClientTimestamp
    }

    /**
     * True when the cloud copy carries nothing the local row does not already hold, so applying it
     * would be a write with no effect. The Kotlin counterpart of the web client's `notesEqual`.
     *
     * Compares only what actually syncs. Row ids on labels and checklist items are deliberately
     * excluded: a [CloudNoteRecord] carries label *names* and unkeyed checklist entries, so the ids
     * on the two sides are assigned locally and would never match. Labels are compared as a set —
     * NoteLabelCrossRef stores no ordering — and checklist items are ordered by their own position
     * rather than list order.
     */
    internal fun sameContent(cloud: Note, local: Note): Boolean {
        if (cloud.id != local.id) return false
        if (cloud.timestamp != local.timestamp) return false
        if (cloud.serverUpdatedAt != local.serverUpdatedAt) return false
        if (cloud.title != local.title) return false
        if (cloud.content != local.content) return false
        if (cloud.color != local.color) return false
        if (cloud.position != local.position) return false
        if (cloud.isPinned != local.isPinned) return false
        if (cloud.isArchived != local.isArchived) return false
        if (cloud.isTrashed != local.isTrashed) return false
        if (cloud.reminderTimestamp != local.reminderTimestamp) return false
        if (cloud.labels.map { it.name }.sorted() != local.labels.map { it.name }.sorted()) {
            return false
        }
        if (attachmentsKey(cloud.attachments) != attachmentsKey(local.attachments)) {
            return false
        }
        return cloud.checklist.checklistKey() == local.checklist.checklistKey()
    }

    private fun List<ChecklistItem>.checklistKey(): List<Triple<Int, Boolean, String>> =
        map { Triple(it.position, it.isChecked, it.text) }.sortedBy { it.first }

    private suspend fun putNote(
        note: Note,
        commitToken: LocalCommitToken,
        identity: OperationRemoteIdentity,
    ): LocalCommitResult<Unit> = putNotes(listOf(note), commitToken, identity)

    /**
     * Uploads [notes] in one transport call and records what the server accepted.
     *
     * Both the attachment upload inside [AttachmentSyncService.syncNotesAttachments] and
     * [CloudNoteTransport.putNotes] are suspension points, so nothing captured before them may be
     * written back over the durable row afterwards: a note the user edited while the upload ran
     * carries a newer title, content, timestamp and attachment set, and the row has already taken
     * the `serverUpdatedAt` this call just read back from the server. Replacing the whole row with
     * the pre-upload snapshot silently reverts all of that.
     *
     * So only the attachment column is written, from the row as it is *now*, with the upload's
     * results merged in by stable [Attachment.id]:
     *
     *  - an attachment the upload moved to `r2:` takes its new storage path;
     *  - an attachment the sync deliberately dropped — it was in the payload, and its bytes are
     *    provably gone — is removed;
     *  - an attachment added while the upload ran is preserved;
     *  - an attachment removed while the upload ran is not resurrected.
     */
    private suspend fun putNotes(
        notes: List<Note>,
        commitToken: LocalCommitToken,
        identity: OperationRemoteIdentity,
    ): LocalCommitResult<Unit> {
        if (notes.isEmpty()) {
            // Nothing to upload, but the caller's tail still needs this operation's generation decision:
            // a refused commit here means the dataset is gone, so the tail must not run for it either.
            return commitLocally(commitToken) { }
        }
        // The attachment uploads inside this batch are account-owned remote mutations of their own,
        // and each takes its own authorization from this operation's token — so a refusal here means
        // the dataset that assembled the batch is gone, and neither the notes nor the metadata
        // document may be pushed for it.
        val syncedNotes = when (
            val attachmentSyncResult = attachmentSync?.syncNotesAttachments(notes, commitToken)
                ?: LocalCommitResult.Applied(notes)
        ) {
            is LocalCommitResult.Applied -> attachmentSyncResult.value
            LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
        }
        // The upload itself is an account-owned remote mutation and takes its own authorization: a
        // refusal means the dataset that assembled this batch is gone, so pushing its notes over
        // whatever account holds the cloud by now is not something it may still do.
        val putMutation = "upload.putNotes"
        beforeRemoteMutationStart(putMutation)
        val put = LocalCommitGate.authorizeRemoteMutationStart(commitToken, putMutation)
            ?: return LocalCommitResult.StaleGeneration
        val results = put.consumeOnce { transport.putNotes(identity, syncedNotes) }
        // The upload's results may only be recorded against the dataset that issued the upload, and the
        // token is validated inside the same critical section as the writes: an isolation that landed
        // while the upload was in flight refuses the entire block, instead of stamping A's server
        // revision and attachment paths onto whatever account is signed in by then.
        //
        // The decision is handed back rather than swallowed: a refusal means the upload belongs to an
        // extinct dataset, and the caller's remaining tail — hydration, staged reconciliation, attachment
        // GC, the prune, the sync-meta write — is all new work for that dataset.
        return commitLocally(commitToken) {
            for ((noteId, result) in results) {
                if (result.serverUpdatedAt != null) {
                    noteDao.updateServerTimestamp(noteId, result.serverUpdatedAt)
                }
                syncStateStore.updateKnownServerRevision(noteId, result.revision)
            }
            // [syncNotesAttachments] maps one-to-one, so index i of `notes` is the pre-upload
            // snapshot of `syncedNotes[i]`: the only way to tell "the sync dropped this attachment"
            // (it was in the payload and is now gone) from "the user added this during the upload"
            // (it is in the row but was never in the payload).
            for ((index, uploaded) in syncedNotes.withIndex()) {
                if (uploaded.attachments.isEmpty()) continue
                mergeUploadedAttachmentPaths(
                    snapshot = notes.getOrNull(index) ?: uploaded,
                    uploaded = uploaded,
                )
            }
        }
    }

    /**
     * Writes an upload's attachment results onto the durable row and nothing else.
     *
     * The merge runs against the row as it exists *after* the upload, never against the snapshot
     * the upload was handed, which is what leaves a concurrent edit intact. See [putNotes].
     */
    private suspend fun mergeUploadedAttachmentPaths(snapshot: Note, uploaded: Note) {
        val noteId = uploaded.id ?: return
        val currentJson = noteDao.getNoteEntityById(noteId)?.attachmentsJson ?: return
        val currentAttachments = decodeAttachments(currentJson)
        if (currentAttachments.isEmpty()) return

        val uploadedById = uploaded.attachments.associateBy { it.id }
        val droppedIds = snapshot.attachments.map { it.id }.toSet() - uploadedById.keys

        val merged = currentAttachments
            .filterNot { it.id in droppedIds }
            .map { attachment ->
                val resolved = uploadedById[attachment.id] ?: return@map attachment
                // Only the fields the upload resolves are taken from it. The row keeps the identity
                // it has, and anything added while the upload ran is passed through untouched.
                attachment.copy(
                    storagePath = resolved.storagePath,
                    mimeType = resolved.mimeType,
                    sizeBytes = resolved.sizeBytes,
                )
            }

        val mergedJson = encodeAttachments(merged)
        if (mergedJson == currentJson) {
            // The row already carries the uploaded paths — another sync got there first — so the
            // local source bytes are no longer the only copy and may be released.
            attachmentSync?.confirmCommittedAttachments(snapshot, uploaded)
            return
        }
        val applied = noteDao.replaceAttachmentsJsonIfUnchanged(
            noteId = noteId,
            expectedAttachmentsJson = currentJson,
            attachmentsJson = mergedJson,
        ) > 0
        // When the compare-and-set loses, another writer's attachment set stands. The source bytes
        // are deliberately kept: releasing them is only safe once the row being released against is
        // the row on disk, and the storage paths are re-derived on the next sync either way.
        if (applied) {
            attachmentSync?.confirmCommittedAttachments(snapshot, uploaded)
        }
    }

    /**
     * Returns the cloud tombstones that survived the merge, so callers need not re-fetch them.
     *
     * The result carries this merge's generation decision rather than only its data: `StaleGeneration`
     * means the dataset the merge belongs to has been replaced, and the caller must stop instead of
     * continuing to work for it. Every local continuation below therefore ends the merge — including the
     * loop, so a restored id that has not been acted on yet is never sent to the cloud on behalf of an
     * extinct dataset.
     */
    private suspend fun mergeCloudTombstones(
        commitToken: LocalCommitToken,
        identity: OperationRemoteIdentity,
    ): LocalCommitResult<Map<Long, Long>> {
        val remote = transport.fetchTombstones(identity)
        val restored = syncStateStore.restoredIds()
        val ignoreTombstones = mutableSetOf<Long>()
        for (noteId in restored) {
            val liveNote = transport.fetchNote(identity, noteId)
            if (liveNote != null) {
                // The remote note still exists, so the restore marker has done its job — in the
                // dataset that was current when this fetch ran, and nowhere else. A refusal ends the
                // merge here: the ids left in this loop must not be restored for a dataset that is gone.
                when (commitLocally(commitToken) { syncStateStore.clearRestored(listOf(noteId)) }) {
                    is LocalCommitResult.Applied -> ignoreTombstones.add(noteId)
                    LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
                }
                continue
            }
            val local = noteDao.getNoteById(noteId)?.toNote()
            if (local != null) {
                // A failed remote restore keeps its existing meaning — this id is skipped and the merge
                // carries on — and is deliberately not confused with a stale generation.
                // The restore is an account-owned remote mutation, so it takes its own authorization:
                // a refusal means this dataset is gone, and no later id may be restored in its name.
                val mergeRestoreMutation = "merge.restoreNote($noteId)"
                beforeRemoteMutationStart(mergeRestoreMutation)
                val mergeRestore = LocalCommitGate.authorizeRemoteMutationStart(commitToken, mergeRestoreMutation)
                    ?: return LocalCommitResult.StaleGeneration
                val results = runCatching { mergeRestore.consumeOnce { transport.restoreNote(identity, local) } }
                    .getOrNull()
                if (results != null) {
                    // The local half of the restore and the decision to ignore this note's tombstone are
                    // one generation-scoped outcome. A refused commit means the dataset that asked for
                    // the restore is gone, so neither the stamp, the known revision, the restore marker
                    // nor the suppression may stand in its name, and no later id may be restored either.
                    // The remote restore above belongs to the account's cloud rather than to a local
                    // dataset, so it stands on its own authorization.
                    when (commitLocally(commitToken) {
                        for ((id, result) in results) {
                            if (result.serverUpdatedAt != null) {
                                noteDao.updateServerTimestamp(id, result.serverUpdatedAt)
                            }
                            syncStateStore.updateKnownServerRevision(id, result.revision)
                        }
                        syncStateStore.clearRestored(listOf(noteId))
                    }) {
                        is LocalCommitResult.Applied -> ignoreTombstones.add(noteId)
                        LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
                    }
                }
            }
        }
        val stillRestored = syncStateStore.restoredIds()
        val live = remote.filterKeys { it !in stillRestored && it !in ignoreTombstones }
        // These deletions belong to the dataset this operation started in. Merging them into a
        // replacement dataset would mark *its* notes deleted for ids it never knew about — so the
        // commit's own result is handed back rather than folded into the map.
        return when (commitLocally(commitToken) { syncStateStore.mergeDeleted(live) }) {
            is LocalCommitResult.Applied -> LocalCommitResult.Applied(live)
            LocalCommitResult.StaleGeneration -> LocalCommitResult.StaleGeneration
        }
    }

    /**
     * Finishes attachment blob GC that a previous delete could not complete.
     *
     * [commitToken] is the enclosing download's generation, and it reaches both continuations: the
     * removal itself is refused when that dataset is gone, and so is the marker clear below — a
     * stale retry must not clear the replacement dataset's own pending GC for a colliding id.
     *
     * It returns that decision instead of `Unit`, because the caller's remaining tail — the
     * end-of-run bookkeeping, the prune, the sync-meta write and the merged-user marker — is all new
     * work for the dataset this retry belongs to.
     *
     * R17: the sweep's metadata RPCs are protected, so the caller hands this helper the immutable
     * [com.aus.notelikeus.data.remote.OperationRemoteIdentity] it already holds for the run — the same
     * value belongs to the listing and to every purge, and this helper never reads a credential of its
     * own.
     *
     * `internal` rather than `private` so the pre-sweep entry fence can be tested directly: through
     * [downloadAllNotes] there is no suspension between the last generation-fenced commit and this
     * call, so a token that is already stale here is otherwise unreachable in a test.
     */
    internal suspend fun retryPendingAttachmentGc(
        commitToken: LocalCommitToken,
        metadataIdentity: OperationRemoteIdentity,
    ): LocalCommitResult<Unit> {
        for ((noteId, attachmentIds) in syncStateStore.pendingAttachmentGcEntries()) {
            val fromNote = noteDao.getNoteById(noteId)?.toNote()?.attachments
            val attachments = fromNote ?: attachmentIds.map { id ->
                Attachment(id = id, noteId = noteId, storagePath = "r2:gc/$noteId/$id")
            }
            // Three outcomes, kept apart rather than flattened into "did it throw":
            //   Applied -> the cleanup ran for this dataset, so its marker may be dropped below;
            //   stale   -> the dataset that scheduled this retry is gone: stop immediately, with no
            //              marker clear, no next entry and no sweep. Once this helper knows the
            //              dataset is gone it performs no later account-owned mutation;
            //   thrown  -> historical behaviour, unchanged: the marker stays for the next sync and
            //              the loop moves on. A transport failure is not an account boundary and must
            //              never be reported as one.
            val cleanup = runCatching { deleteNoteAttachments(noteId, attachments, commitToken) }
            if (cleanup.isFailure) continue
            when (cleanup.getOrThrow()) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
            }
            // Only the dataset that recorded the retry may drop it, and that commit's own decision is
            // taken rather than discarded.
            when (commitLocally(commitToken) { syncStateStore.clearPendingAttachmentGc(noteId) }) {
                is LocalCommitResult.Applied -> Unit
                LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
            }
        }
        // Entry fence, kept after 3D.3H.1 gave the sweep a decision behind every one of its
        // suspensions. The sweep would now reach the same *outcome* on its own, so on staleness
        // detection this is defence in depth — but it is still load-bearing for the *call*: a retry
        // that is already stale must not issue a metadata listing for a dataset known to be dead, and
        // that is exactly what PAGC-6 pins. Reporting it as removable would change that behaviour.
        if (commitLocally(commitToken) { } is LocalCommitResult.StaleGeneration) {
            return LocalCommitResult.StaleGeneration
        }
        // One identity for the whole sweep — the listing and every purge — threaded in from the
        // operation that owns this retry, exactly like its token: a capture here would both read the
        // live session a second time for one logical operation and hand the sweep whatever account
        // happened to be live by then.
        val swept = attachmentSync?.sweepPendingDeletedAttachments(commitToken, metadataIdentity) { id ->
            id.toLongOrNull() in syncStateStore.restoredIds()
        } ?: LocalCommitResult.Applied(Unit)
        return when (swept) {
            is LocalCommitResult.Applied -> LocalCommitResult.Applied(Unit)
            LocalCommitResult.StaleGeneration -> LocalCommitResult.StaleGeneration
        }
    }

    /**
     * Fetches the cloud tombstone for one note and, if there is one, merges it into device-local state.
     *
     * Returns the originating generation's decision instead of `Unit`, because **both** outcomes of the
     * read need one. The fetch is a suspension, so a remote read that comes back — with a tombstone or
     * with nothing — does not authorize the caller to carry on:
     *
     *  - a tombstone was found -> the merge's own generation-fenced commit decides;
     *  - **no tombstone** -> there is no local work to fence, but the read still suspended, so the
     *    originating token takes an explicit decision here. Reporting "nothing to do, carry on" for a
     *    dataset that has since been replaced is the defect this phase closes, and it is the outcome
     *    that looks harmless.
     *
     * A caller that ignores this can walk straight into an account-owned mutation on behalf of a
     * dataset that no longer exists — which is what [uploadNote] used to do.
     *
     * `internal` rather than `private` so both read outcomes can be tested directly: the non-null
     * branch has no suspension between its fetch and its commit, so a boundary cannot be driven into
     * it through a caller.
     */
    internal suspend fun refreshCloudTombstone(
        noteId: Long,
        commitToken: LocalCommitToken,
        identity: OperationRemoteIdentity,
    ): LocalCommitResult<Unit> {
        // Local and non-suspending: nothing has suspended yet in this call, so a success return here
        // cannot be hiding a boundary.
        if (noteId in syncStateStore.restoredIds()) return LocalCommitResult.Applied(Unit)
        // One document, not the whole collection: this runs per uploaded note. It suspends, so its
        // outcome alone may not authorize what follows it.
        val deletedAt = transport.fetchTombstone(identity, noteId)
            ?: return commitLocally(commitToken) { }
        // The fetched tombstone is the cloud's, but the deletion it implies is device-local state of
        // the dataset that asked for it: a replaced dataset must not inherit it, and the merge's own
        // decision is handed back rather than discarded.
        return when (commitLocally(commitToken) {
            syncStateStore.mergeDeleted(mapOf(noteId to deletedAt))
        }) {
            is LocalCommitResult.Applied -> LocalCommitResult.Applied(Unit)
            LocalCommitResult.StaleGeneration -> LocalCommitResult.StaleGeneration
        }
    }

    /**
     * Deletes the local rows whose ids this dataset has already tombstoned.
     *
     * Returns the generation decision rather than a bare set. `StaleGeneration` is the fact that this
     * whole operation belongs to an extinct dataset, and its caller must see it: collapsing it into
     * "nothing was purged" would let the run carry on making account-owned writes — local *and* remote —
     * on behalf of a dataset that no longer exists.
     *
     * The commit is taken even when nothing is tombstoned, so a stale run is always detected here
     * instead of slipping out through an "empty work" early exit. The sync state is left untouched
     * either way: tombstone bookkeeping is [mergeCloudTombstones]' business, not this helper's.
     */
    private suspend fun purgeLocalTombstonedNotes(
        localNotes: List<Note>,
        commitToken: LocalCommitToken,
    ): LocalCommitResult<Set<Long>> =
        commitLocally(commitToken) {
            // One commit for the whole snapshot rather than one per row: the candidates are a single
            // snapshot result read in one pass, and a partial purge would be worse than none — some
            // rows gone, the caller told nothing about which.
            localNotes.mapNotNull { note ->
                val id = note.id ?: return@mapNotNull null
                if (!syncStateStore.isDeleted(id)) return@mapNotNull null
                noteDao.deleteNote(note.toNoteEntity())
                id
            }.toSet()
        }

    /**
     * Drops tombstones that have outlived [NoteSyncStateStore.TOMBSTONE_TTL_MS], locally and in the
     * cloud, and reports this run's generation decision instead of swallowing it.
     *
     * The local half is a *destructive* removal from the dataset's own tombstone bookkeeping, and the
     * caller's remaining tail — the sync-meta write and the merged-user marker — is new work for that
     * dataset. So the local removal runs inside the originating generation's commit gate, and a
     * refusal is handed back rather than collapsed into "nothing was pruned": that collapse is what
     * lets a replaced dataset's run carry on writing. Same winner model as every other local commit
     * in this engine.
     *
     * The two remote `deleteTombstones` calls carry the enclosing operation's captured [identity] as
     * well as its generation: the local prune's authority and the credential the remote deletes
     * authenticate with are the same operation's, so neither can end up belonging to the replacement
     * dataset. Their own remote-*start* grants are unchanged.
     *
     * `internal` rather than `private` so the fence can be tested directly. Reaching this helper only
     * through `downloadAllNotes` is not enough: that run hits an earlier generation-fenced commit
     * first, so a stale token stops it before the prune — which makes the defect merely unreachable
     * rather than absent, and a composition-only test would report it as fixed.
     *
     * @param cloud tombstones already fetched by [mergeCloudTombstones] earlier in this sync. Any
     *   tombstone written since is dated now and cannot be expired, so re-reading the collection here
     *   only bought a second round trip.
     * @param commitToken the calling download's generation. The local removal below runs after the
     *   remote deletes, so nothing here may take effect in a dataset that replaced this one.
     * @param identity the calling download's one remote identity — the token and owner its own
     *   `deleteTombstones` requests must use, rather than whatever session is live by then.
     */
    internal suspend fun pruneExpiredTombstones(
        liveNoteIds: Set<Long>,
        cloud: Map<Long, Long>,
        commitToken: LocalCommitToken,
        identity: OperationRemoteIdentity,
    ): LocalCommitResult<Unit> {
        // One commit for the local expiry removal: it is device-local state of the dataset this
        // download started in, so a stale run must not delete the replacement dataset's tombstone
        // entries under colliding ids. The pure filter stays outside — only the mutation is fenced.
        val pruned = when (val expired = commitLocally(commitToken) {
            syncStateStore.pruneExpired(NoteSyncStateStore.TOMBSTONE_TTL_MS)
        }) {
            is LocalCommitResult.Applied -> expired.value.filter { it !in liveNoteIds }
            LocalCommitResult.StaleGeneration -> return LocalCommitResult.StaleGeneration
        }
        if (pruned.isNotEmpty()) {
            // One grant for this batch's actual invocation, taken only because the batch is
            // non-empty: an empty batch would mean authorizing a request that never happens. A
            // refusal means the dataset that pruned is gone, so the run stops before the second
            // batch and before the postremote clear.
            val localBatchMutation = "prune.deleteTombstones(local)"
            beforeRemoteMutationStart(localBatchMutation)
            val localBatch = LocalCommitGate.authorizeRemoteMutationStart(commitToken, localBatchMutation)
                ?: return LocalCommitResult.StaleGeneration
            localBatch.consumeOnce { transport.deleteTombstones(identity, pruned.toList()) }
        }
        val now = now()
        val expiredRemote = cloud.mapNotNull { (noteId, deletedAt) ->
            if (noteId in liveNoteIds) return@mapNotNull null
            if (now - deletedAt >= NoteSyncStateStore.TOMBSTONE_TTL_MS) noteId else null
        }
        if (expiredRemote.isNotEmpty()) {
            // A different batch invocation needs a different grant: the first one was consumed by
            // the request above and cannot be reused for this one.
            val remoteBatchMutation = "prune.deleteTombstones(remote)"
            beforeRemoteMutationStart(remoteBatchMutation)
            val remoteBatch = LocalCommitGate.authorizeRemoteMutationStart(commitToken, remoteBatchMutation)
                ?: return LocalCommitResult.StaleGeneration
            remoteBatch.consumeOnce { transport.deleteTombstones(identity, expiredRemote) }
        }
        // Postremote bookkeeping, so its own decision is taken and returned rather than discarded: a
        // refusal here means the dataset is gone and the caller must stop before its tail.
        return when (commitLocally(commitToken) { syncStateStore.clearDeleted(expiredRemote) }) {
            is LocalCommitResult.Applied -> LocalCommitResult.Applied(Unit)
            LocalCommitResult.StaleGeneration -> LocalCommitResult.StaleGeneration
        }
    }

    /**
     * Keeps the attachment references only this device can account for.
     *
     * [CloudNoteRecord] carries no attachment data at all — attachments travel as their own
     * server-side metadata rows, reconciled afterwards by
     * [com.aus.notelikeus.data.attachments.AttachmentSyncService.hydrateAllNotes] — so
     * [CloudNoteRecord.toNote] produces a note with none. Writing that over the local row drops
     * every `pending:` upload the server has never seen and every `file:` reference to a local
     * source file, and since
     * [com.aus.notelikeus.data.attachments.AttachmentSyncService.reconcileStagedAttachments]
     * releases staged bytes no note references any more, the only copy of those bytes is deleted
     * with the reference.
     *
     * An `r2:` attachment is deliberately *not* carried over: the server does speak for those, so
     * a download that finds no metadata row for one must let the reference go, and `hydrateAllNotes`
     * restores the ones the server still has.
     */
    private fun Note.keepingLocalOnlyAttachmentsOf(local: Note): Note {
        val localOnly = local.attachments.filter {
            isPendingAttachment(it.storagePath) || isFileAttachment(it.storagePath)
        }
        if (localOnly.isEmpty()) return this
        val known = attachments.map { it.id }.toSet()
        return copy(attachments = attachments + localOnly.filterNot { it.id in known })
    }

    private suspend fun innerInsert(note: Note) {
        runInTransaction {
            val insertedId = noteDao.insertNote(note.toNoteEntity())
            note.labels.forEach { label ->
                label.id?.let { labelId ->
                    noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(insertedId, labelId))
                }
            }
            note.checklist.forEach { item ->
                noteDao.insertChecklistItem(item.toChecklistItemEntity(insertedId))
            }
        }
    }

    private suspend fun innerUpdate(note: Note) {
        val noteId = note.id ?: return
        runInTransaction {
            noteDao.updateNote(note.toNoteEntity())

            noteDao.deleteNoteLabelCrossRefs(noteId)
            note.labels.forEach { label ->
                label.id?.let { labelId ->
                    noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(noteId, labelId))
                }
            }

            noteDao.deleteChecklistItems(noteId)
            note.checklist.forEach { item ->
                noteDao.insertChecklistItem(item.toChecklistItemEntity(noteId))
            }
        }
    }

    private companion object {
        const val TAG = "NoteSyncEngine"
    }

}

internal suspend fun CloudNoteRecord.toNote(
    resolveLabel: suspend (String) -> Label
): Note = Note(
    id = noteId,
    title = title,
    content = content,
    timestamp = timestamp,
    color = color,
    isPinned = isPinned,
    isArchived = isArchived,
    isTrashed = isTrashed,
    position = position,
    reminderTimestamp = reminderTimestamp,
    serverUpdatedAt = serverUpdatedAt,
    labels = labels.map { name -> resolveLabel(name) },
    attachments = emptyList(),
    checklist = checklistItems.mapIndexed { index, item ->
        ChecklistItem(
            text = item.text,
            isChecked = item.isChecked,
            position = item.position
        )
    }
)
