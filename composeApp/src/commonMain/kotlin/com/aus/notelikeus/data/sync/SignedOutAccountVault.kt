package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.UnsyncedWork
import com.aus.notelikeus.util.DateUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keeps a signed-out account's unsynced work on this device, out of reach of any other account, and
 * gives it back when that account signs in here again (NL-03).
 *
 * Signing out still wipes the device's library, so the next person at it sees nothing of the previous
 * account. What changes is that the library is sealed first when it holds anything the cloud has not
 * confirmed (an edit, a deletion, an image); before this, sign-out deleted those for good. This mirrors the
 * web client, which keeps each account's IndexedDB namespace sealed and owner-scoped
 * (`clearLocalUserDataForAccountSwitch` in `web/src/lib/bootstrap.ts`).
 *
 * ## What is sealed, and why all of it
 *
 * The whole library and the whole sync state, not just the unsynced notes. The engine's decisions are
 * made against its baselines, known revisions and tombstones together; giving back an edit without
 * the revision it was based on would turn a plain upload into a conflict. Restoring both puts the
 * device exactly where it was at sign-out, which is a state the engine already handles: a device that
 * was offline for a while.
 *
 * ## Life of a sealed library
 *
 * ```
 * seal(owner)          written, not armed: the live rows are still this dataset, and newer
 *   |
 * arm()                inside the isolation, after the wipe: the live rows are gone, so this copy is now
 *   |                  the only one and may be restored
 * restore(owner)       only by the same account, only when armed; the row is deleted in the same
 *                      transaction that writes the notes back
 * ```
 *
 * An unarmed library is never restored. It exists only when a sign-out sealed and then did not cross
 * the boundary (the session refused to end), and then the device is still on that dataset.
 *
 * Nothing here decides *whether* a sign-out happens; [LocalAccountIsolator] calls it.
 */
class SignedOutAccountVault(
    private val noteRepository: NoteRepository,
    private val syncStateStore: NoteSyncStateStore,
    private val store: SealedAccountStore,
    /** What the cloud has not confirmed; `NoteSyncEngine.unsyncedWork`. A function to avoid a cycle. */
    private val unsyncedWork: suspend () -> UnsyncedWork,
) {
    /**
     * Seals the device's library for [ownerUid] if it holds unsynced work, and drops any older copy
     * if it holds none.
     *
     * A count that cannot be read is not a count of zero (AUDIT-006): the library is sealed anyway.
     * Throws if the seal cannot be written, so a caller can refuse to go on and wipe.
     *
     * @return true when a library is now sealed for [ownerUid]
     */
    suspend fun seal(ownerUid: String): Boolean {
        val work = runCatching { unsyncedWork() }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        if (work != null && work.isEmpty) {
            // Everything is in the cloud, so the next sign-in downloads it. An older sealed copy would
            // now be stale, and restoring it would put old versions back over newer ones.
            store.remove(ownerUid)
            return false
        }
        val library = SealedLibrary(
            notes = noteRepository.getAllNotesForBackup(),
            labels = noteRepository.getAllLabelsSnapshot(),
            syncState = SyncStateSnapshot.of(syncStateStore),
        )
        store.put(
            SealedAccount(
                ownerUid = ownerUid,
                payload = json.encodeToString(SealedLibrary.serializer(), library),
                sealedAt = DateUtils.currentTimeMillis(),
                armed = false,
            ),
        )
        return true
    }

    /** Called by the isolation once the library is wiped: every sealed copy is now the only one. */
    suspend fun armAll() = store.armAll()

    /** Forgets [ownerUid]'s sealed library: the user deleted the account, or asked for its data gone. */
    suspend fun discard(ownerUid: String) = store.remove(ownerUid)

    /** True when [ownerUid] has a library waiting on this device. */
    suspend fun hasRestorable(ownerUid: String): Boolean = restorable(ownerUid) != null

    /**
     * Gives [ownerUid] back the library it sealed here, and queues its upload.
     *
     * The notes keep their ids and timestamps, so the restored sync state describes them exactly.
     * Labels are matched by name, since a label created here while signed out may have taken a
     * sealed label's id. Anything the device gained while signed out stays; it is guest work, which a
     * sign-in adopts in any case.
     *
     * @return how many notes were restored, or 0 when there was nothing to restore or the dataset
     *   moved on while this ran (the library then stays sealed for the next attempt)
     */
    suspend fun restore(ownerUid: String): Int {
        val sealed = restorable(ownerUid) ?: return 0
        val library = json.decodeFromString(SealedLibrary.serializer(), sealed.payload)
        val token = LocalCommitGate.capture(ownerUid)
        val restored = LocalCommitGate.commit(token) {
            library.syncState.mergeInto(syncStateStore, ownerUid)
            noteRepository.withWriteTransaction {
                val labelsByName = noteRepository.getAllLabelsSnapshot()
                    .associateByTo(mutableMapOf()) { it.name.trim().lowercase() }
                suspend fun resolve(label: Label): Label {
                    val key = label.name.trim().lowercase()
                    labelsByName[key]?.let { return it }
                    val name = label.name.trim()
                    val created = Label(id = noteRepository.insertLabel(Label(name = name)), name = name)
                    labelsByName[key] = created
                    return created
                }
                library.labels.forEach { resolve(it) }
                val ids = library.notes.mapNotNull { note ->
                    if (note.id == null) return@mapNotNull null
                    noteRepository.insertNoteWithoutSync(note.copy(labels = note.labels.map { resolve(it) }))
                }
                // In the same transaction as the notes: a crash leaves either both or neither, never
                // the notes back *and* a copy that a later sign-in would write over newer edits.
                store.remove(sealed.ownerUid)
                ids
            }
        }
        val ids = when (restored) {
            is LocalCommitResult.Applied -> restored.value
            LocalCommitResult.StaleGeneration -> return 0
        }
        // Reminders, widget, and an upload for each note. A note the cloud already has at this version
        // loses the tie to the cloud and is not sent again, so this re-sends only what is unsynced.
        noteRepository.finalizeImportedNotes(ids, token)
        return ids.size
    }

    private suspend fun restorable(ownerUid: String): SealedAccount? =
        store.all().firstOrNull { it.ownerUid == ownerUid && it.armed }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class SealedLibrary(
    val version: Int = 1,
    val notes: List<Note>,
    val labels: List<Label>,
    val syncState: SyncStateSnapshot,
)

/** Everything [NoteSyncStateStore] holds, read through its public interface. */
@Serializable
internal data class SyncStateSnapshot(
    val deletedAt: Map<Long, Long> = emptyMap(),
    val baselineDeleteRevisions: Map<Long, Long> = emptyMap(),
    val deletedDigests: Map<Long, String> = emptyMap(),
    val restoredIds: Set<Long> = emptySet(),
    val pendingAttachmentGc: Map<Long, Set<String>> = emptyMap(),
    val recommitNeededIds: Set<Long> = emptySet(),
    val lastReconciledAt: Long = 0,
    val knownCloudIds: Set<Long> = emptySet(),
    val knownServerRevisions: Map<Long, Long> = emptyMap(),
    val syncedTimestamps: Map<Long, Long> = emptyMap(),
    val wipeGeneration: Long = 0,
    val uploadsBlockedAfterCloudWipe: Boolean = false,
) {
    /**
     * Adds this snapshot to [store], which after a sign-out holds only what the device did as a guest.
     * Merged rather than replaced so a guest tombstone survives; re-running it is harmless, which a
     * restore interrupted before its database transaction relies on.
     */
    fun mergeInto(store: NoteSyncStateStore, ownerUid: String) {
        store.mergeDeleted(deletedAt)
        baselineDeleteRevisions.forEach { (id, revision) -> store.setBaselineDeleteRevision(id, revision) }
        deletedDigests.forEach { (id, digest) -> store.setDeletedDigest(id, digest) }
        restoredIds.forEach(store::markRestored)
        pendingAttachmentGc.forEach { (id, attachments) -> store.markPendingAttachmentGc(id, attachments) }
        recommitNeededIds.forEach(store::markRecommitNeeded)
        if (lastReconciledAt > store.lastReconciledAt()) store.markReconciled(lastReconciledAt)
        store.setKnownCloudIds(store.knownCloudIds() + knownCloudIds)
        store.updateKnownServerRevisions(knownServerRevisions)
        store.updateSyncedTimestamps(syncedTimestamps)
        if (wipeGeneration > store.wipeGeneration()) store.setWipeGeneration(wipeGeneration)
        if (uploadsBlockedAfterCloudWipe) store.blockUploadsAfterCloudWipe()
        store.setLastMergedUserId(ownerUid)
    }

    companion object {
        fun of(store: NoteSyncStateStore) = SyncStateSnapshot(
            deletedAt = store.deletedAtById(),
            baselineDeleteRevisions = store.baselineDeleteRevisionById(),
            deletedDigests = store.deletedDigestById(),
            restoredIds = store.restoredIds(),
            pendingAttachmentGc = store.pendingAttachmentGcEntries(),
            recommitNeededIds = store.recommitNeededIds(),
            lastReconciledAt = store.lastReconciledAt(),
            knownCloudIds = store.knownCloudIds(),
            knownServerRevisions = store.knownServerRevisionById(),
            syncedTimestamps = store.syncedTimestampById(),
            wipeGeneration = store.wipeGeneration(),
            uploadsBlockedAfterCloudWipe = store.uploadsBlockedAfterCloudWipe(),
        )
    }
}
