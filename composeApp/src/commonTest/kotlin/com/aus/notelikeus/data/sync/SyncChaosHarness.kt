package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.Note

/**
 * A deterministic sandbox for the sync engine, with failure injectable at every durable boundary.
 *
 * The point is to make concurrency and crash bugs *reproducible*. The engine's hard cases are all
 * about what survives an interruption between two writes that are not one transaction — a local
 * delete and its cloud tombstone, a cloud commit and the cursor that records it — and none of
 * those are reachable from an ordinary test that lets every call succeed.
 *
 * Three things model that:
 *
 * - [FaultInjectingTransport] fails a named transport call the Nth time it is made, so a scenario
 *   can say "the tombstone write fails" without stubbing the whole transport.
 * - [restartProcess] rebuilds the engine over the *same* DAO and state store. Anything the engine
 *   was holding in memory is gone; anything it wrote is not. That is what process death looks
 *   like from the engine's side.
 * - [signOut] and [signInAs] drive the real [LocalAccountIsolator] rules, so an account switch in
 *   a scenario isolates exactly the way the app does.
 *
 * Assertions live in [SyncInvariants] and are written about user-visible state — which notes exist,
 * which are pending, which are deleted — never about how the engine got there.
 */
class SyncChaosHarness(
    initialUid: String = "user-a",
) {
    val noteDao = FakeNoteDao()
    val labelDao = FakeLabelDao()
    val stateStore = FakeNoteSyncStateStore()

    /**
     * One cloud per account, because that is what row-level security produces: account B's
     * session can neither see nor be affected by account A's rows. A single shared map would
     * let A's leftover tombstones reach B and make cross-account tests pass for the wrong
     * reason — the isolation would look correct while actually being the fake's doing.
     */
    private val cloudsByAccount = mutableMapOf<String, FakeCloudNoteTransport>()

    /** The signed-in account's cloud. */
    val cloud: FakeCloudNoteTransport
        get() = cloudFor(uid ?: SIGNED_OUT_ACCOUNT)

    fun cloudFor(account: String): FakeCloudNoteTransport =
        cloudsByAccount.getOrPut(account) { FakeCloudNoteTransport() }

    val transport = FaultInjectingTransport { account -> cloudFor(account) }

    var uid: String? = initialUid
        private set

    /** Advances independently of the state store's clock so ordering is explicit in a scenario. */
    var clock: Long = 1_000_000L

    var engine: NoteSyncEngine = buildEngine()
        private set

    private fun buildEngine() = NoteSyncEngine(
        transport = transport,
        noteDao = noteDao,
        labelDao = labelDao,
        syncStateStore = stateStore,
        uidProvider = {
            uid?.let { Result.success(it) } ?: Result.failure(IllegalStateException("signed out"))
        },
        platform = "test",
        now = { clock },
    )

    /**
     * Process death and relaunch.
     *
     * Only the engine is rebuilt. The DAO and the state store are the durable side of the device,
     * so a scenario that restarts here is asking exactly the right question: was enough written
     * down before the process went away?
     */
    fun restartProcess() {
        engine = buildEngine()
        transport.clearFaults()
    }

    fun tick(millis: Long = 1_000L): Long {
        clock += millis
        stateStore.currentTime = clock
        return clock
    }

    // ---- local operations (what the user does) ----

    fun createLocalNote(title: String, id: Long? = null): Long {
        val noteId = id ?: noteDao.nextId++
        noteDao.notes[noteId] = Note(
            id = noteId,
            title = title,
            content = "",
            timestamp = clock,
            color = 0,
        ).toNoteEntity()
        return noteId
    }

    fun editLocalNote(noteId: Long, title: String) {
        val existing = noteDao.notes[noteId] ?: return
        // Mirrors what the repository does on a local edit: bump the client clock and leave
        // serverUpdatedAt alone, which is how a pending edit announces itself to the conflict rule.
        noteDao.notes[noteId] = existing.copy(title = title, timestamp = clock)
    }

    fun localNote(noteId: Long): Note? = noteDao.notes[noteId]?.let { entity ->
        com.aus.notelikeus.data.local.model.NoteWithLabels(entity, emptyList(), emptyList()).toNote()
    }

    fun localNoteIds(): Set<Long> = noteDao.notes.keys.toSet()

    fun cloudNoteIds(): Set<Long> = cloud.notes.keys.toSet()

    // ---- remote operations (what another device does) ----

    /** Another device writes [noteId] with a server revision strictly newer than anything local. */
    fun remoteEdit(noteId: Long, title: String, serverUpdatedAt: Long) {
        cloud.notes[noteId] = CloudNoteRecord(
            noteId = noteId,
            serverUpdatedAt = serverUpdatedAt,
            clientTimestamp = clock,
            title = title,
            content = "",
            timestamp = clock,
            color = 0,
            isPinned = false,
            isArchived = false,
            isTrashed = false,
            position = 0,
            reminderTimestamp = null,
            labels = emptyList(),
            checklistItems = emptyList(),
        )
        cloud.tombstones.remove(noteId)
    }

    /** Another device deletes [noteId], leaving the tombstone a real delete leaves. */
    fun remoteDelete(noteId: Long, deletedAt: Long = clock) {
        cloud.notes.remove(noteId)
        cloud.tombstones[noteId] = deletedAt
    }

    /** The cloud read fails open: an empty answer where notes exist. Models an expired token. */
    fun simulateEmptyCloudRead() {
        cloud.notes.clear()
        cloud.tombstones.clear()
    }

    /**
     * The cloud read comes back *short*: some rows are missing, but the server still reports the
     * true count. The failure mode the empty-cloud guards cannot see, because the answer is not
     * empty — it just quietly lost notes on the way.
     */
    fun simulateTruncatedCloudRead(missingNotes: Int) {
        cloud.truncateSnapshotBy = missingNotes
    }

    fun stopTruncatingCloudReads() {
        cloud.truncateSnapshotBy = 0
    }

    // ---- account lifecycle ----

    /**
     * Sign-out always isolates the device — the next person here must not see these notes.
     * Mirrors [LocalAccountIsolator.isolate] without dragging in the repository and coordinator.
     */
    fun signOut() {
        uid = null
        stateStore.clear()
        noteDao.notes.clear()
        noteDao.checklistItems.clear()
        noteDao.crossRefs.clear()
        restartProcess()
    }

    /**
     * Switches the signed-in account **without** isolating, modelling the window a crash between
     * sign-out and sign-in leaves open. Nothing in the app does this on purpose; the engine's own
     * account guard is what has to catch it.
     */
    fun uidOverrideForTest(newUid: String) {
        uid = newUid
    }

    /**
     * Signs in as [newUid], isolating first if this device last merged a different account.
     * Mirrors [LocalAccountIsolator.isolateIfAccountChanged].
     */
    fun signInAs(newUid: String) {
        val last = stateStore.lastMergedUserId()
        if (last != null && last != newUid) {
            stateStore.clear()
            noteDao.notes.clear()
            noteDao.checklistItems.clear()
            noteDao.crossRefs.clear()
        }
        uid = newUid
        restartProcess()
    }
}

/**
 * Wraps a [CloudNoteTransport] and fails a named call on demand.
 *
 * Faults are consumed as they fire, so `failOnce("writeTombstone")` models one interrupted request
 * followed by a working network — the interleaving a crash or a dropped connection actually
 * produces, rather than a transport that is broken forever.
 */
class FaultInjectingTransport(
    /** Resolved per call from the uid the engine passes, so each account gets its own rows. */
    private val delegateFor: (String) -> CloudNoteTransport,
) : CloudNoteTransport {

    private val faults = mutableMapOf<String, MutableList<Throwable>>()
    val calls = mutableListOf<String>()

    fun failOnce(operation: String, error: Throwable = RuntimeException("injected $operation failure")) {
        faults.getOrPut(operation) { mutableListOf() }.add(error)
    }

    fun clearFaults() {
        faults.clear()
    }

    private suspend fun <T> gateSuspend(operation: String, block: suspend () -> T): T {
        calls.add(operation)
        faults[operation]?.removeFirstOrNull()?.let { throw it }
        return block()
    }

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> =
        gateSuspend("fetchNotes") { delegateFor(uid).fetchNotes(uid) }

    // Forwarded rather than left to the interface default, which would call *this* wrapper's
    // fetchNotes and so report no authoritative count — the delegate's completeness proof would
    // vanish at the wrapper and no scenario could exercise it.
    override suspend fun fetchNotesSnapshot(uid: String): CloudNoteSnapshot =
        gateSuspend("fetchNotes") { delegateFor(uid).fetchNotesSnapshot(uid) }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? =
        gateSuspend("fetchNote") { delegateFor(uid).fetchNote(uid, noteId) }

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?> =
        gateSuspend("putNotes") { delegateFor(uid).putNotes(uid, notes) }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) =
        gateSuspend("deleteNotes") { delegateFor(uid).deleteNotes(uid, noteIds) }

    override suspend fun restoreNote(uid: String, note: Note): Map<Long, Long?> =
        gateSuspend("restoreNote") { delegateFor(uid).restoreNote(uid, note) }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> =
        gateSuspend("fetchTombstones") { delegateFor(uid).fetchTombstones(uid) }

    override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) =
        gateSuspend("writeTombstone") { delegateFor(uid).writeTombstone(uid, noteId, deletedAt) }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) =
        gateSuspend("deleteTombstones") { delegateFor(uid).deleteTombstones(uid, noteIds) }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) =
        gateSuspend("writeSyncMeta") { delegateFor(uid).writeSyncMeta(uid, noteCount, platform) }

    override suspend fun deleteSyncMeta(uid: String) =
        gateSuspend("deleteSyncMeta") { delegateFor(uid).deleteSyncMeta(uid) }
}

private const val SIGNED_OUT_ACCOUNT = "__signed_out__"
