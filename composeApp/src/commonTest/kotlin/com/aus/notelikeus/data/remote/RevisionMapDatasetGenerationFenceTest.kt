package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.RecordingSyncCoordinator
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * F-9/R20: cloud revision knowledge is dataset-scoped, not uid-scoped.
 *
 * The transport remembers the server revision it last saw per note, and sends it as `p_base_revision`
 * on the next write, which is what the server uses to accept or refuse the write. That knowledge
 * describes *one* local dataset's cloud state. When the local dataset is replaced — including a
 * same-UID isolation or wipe-and-sign-in — the replacement library reuses note ids, so a remembered
 * revision for "note 42" is indistinguishable from one for the replacement's note 42. Consuming it
 * either makes the server apply a write against a lineage the new dataset never saw, or makes it answer
 * `conflict` for a note that has no conflict.
 *
 * These lanes drive the real `SupabaseNoteTransport` and the real `LocalAccountIsolator`, and assert the
 * `p_base_revision` that would actually reach `apply_note_change`.
 */
class RevisionMapDatasetGenerationFenceTest {

    private val uid = UID

    private fun transport(rpc: SupabaseRpcClient, store: RevisionStateStore = RevisionStateStore()) =
        SupabaseNoteTransport(rpc, store)

    /** An identity as the production capture produces it: bound to the generation active right now. */
    private suspend fun identityFor(store: RevisionStateStore): OperationRemoteIdentity {
        val provider = RemoteIdentityProvider(
            accessTokenProvider = { TOKEN },
            sessionOwnerId = { uid },
            revisionEpochProvider = { store.currentEpoch() },
        )
        return provider.capture(LocalCommitGate.capture(uid))!!
    }

    /** The isolator exactly as the platform graph wires it — the revision hook included. */
    private fun isolator(transport: DatasetScopedCloudRevisionState) = LocalAccountIsolator(
        FakeNoteRepository(),
        FakeNoteSyncStateStore(),
        RecordingSyncCoordinator(),
        clearDatasetScopedInMemoryState = { transport.clearDatasetScopedState() },
    )

    /** REV-3/§11: inside one generation the learned revision is still used, unchanged. */
    @Test
    fun `REV-3 the current generation still sends the revision it learned`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        rpc.on("apply_note_change", applied(revision = 101L))

        transport.fetchNotes(uid)
        transport.putNotes(uid, listOf(note(42L)))

        assertEquals(
            listOf(100L),
            rpc.baseRevisions(),
            "the current generation stopped sending the revision it learned, which disables conflict detection",
        )
    }

    /** REV-1/§4: a same-UID replacement cannot consume the previous generation's revision. */
    @Test
    fun `REV-1 a same-UID replacement cannot send the old revision`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        rpc.on("apply_note_change", applied(revision = 500L))

        transport.fetchNotes(uid)                 // generation N learns 42 -> 100
        isolator(transport).isolate()             // the dataset is replaced, same uid
        transport.putNotes(uid, listOf(note(42L)))  // generation N+1 writes its own note 42

        assertNull(
            rpc.baseRevisions().singleOrNull(),
            "generation N+1 sent a base revision learned by the replaced dataset",
        )
    }

    /** REV-5/§13: the collision is note-id reuse, and it is unconditional. */
    @Test
    fun `REV-5 the reused note id is the collision, and clearing does not depend on the uid`() = runTest {
        val rpc = RecordingRpc()
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 7L))
        rpc.on("apply_note_change", applied(revision = 8L))

        transport.fetchNotes(uid)
        // Same uid, same note id, replacement dataset: only the boundary is different.
        transport.clearDatasetScopedState()
        transport.putNotes(uid, listOf(note(42L)))

        assertNull(rpc.baseRevisions().singleOrNull(), "note-id reuse leaked the old revision")
    }

    /** REV-4/§12: the replacement generation relearns and uses *its* authoritative revision. */
    @Test
    fun `REV-4 the replacement generation relearns and uses the new revision`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        rpc.on("apply_note_change", applied(revision = 101L))

        transport.fetchNotes(uid)
        isolator(transport).isolate()

        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 900L))
        transport.fetchNotes(uid)
        transport.putNotes(uid, listOf(note(42L)))

        assertEquals(
            listOf(900L),
            rpc.baseRevisions(),
            "the replacement generation did not learn its own revision, or sent the replaced one",
        )
    }

    /** REV-2/§5: a different account was already isolated by the uid key; record that it stays safe. */
    @Test
    fun `REV-2 a different account never sees another account's revision`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        rpc.on("apply_note_change", applied(revision = 101L))

        transport.fetchNotes(uid)
        transport.putNotes(OTHER_UID, listOf(note(42L)))

        assertNull(rpc.baseRevisions().singleOrNull(), "one account's revision reached another account's write")
    }

    /** REV-7/§8/§9: the clear happens at the boundary, so a wipe that then fails cannot un-clear it. */
    @Test
    fun `REV-7 a failed isolation leaves the old revision unavailable`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        rpc.on("apply_note_change", applied(revision = 101L))
        transport.fetchNotes(uid)

        // The clear happens and *then* the wipe fails, which is the shape of a real mid-isolation failure:
        // the point of the lane is that the boundary's invalidation does not depend on what follows it.
        val failing = LocalAccountIsolator(
            FakeNoteRepository(),
            FakeNoteSyncStateStore(),
            RecordingSyncCoordinator(),
            clearDatasetScopedInMemoryState = {
                transport.clearDatasetScopedState()
                error("wipe failed")
            },
        )
        val outcome = runCatching { failing.isolate() }

        assertTrue(outcome.isFailure, "the isolation was supposed to fail for this lane")
        transport.putNotes(uid, listOf(note(42L)))
        assertNull(
            rpc.baseRevisions().singleOrNull(),
            "a quarantined dataset could still parameterize a write with the previous generation's revision",
        )
    }

    /** REV-6/§16: work that began in the old generation cannot refill the map after the boundary. */
    @Test
    fun `REV-6 an in-flight snapshot from the old generation cannot repopulate the map`() =
        runTest(timeout = TIMEOUT) {
            val rpc = BlockingSnapshotRpc(snapshotWith(42L, revision = 100L))
            val transport = transport(rpc)
            val snapshot = async(Dispatchers.IO) { transport.fetchNotes(uid) }
            rpc.awaitStarted()

            // The dataset is replaced while the old generation's read is still in flight.
            transport.clearDatasetScopedState()
            rpc.release()
            snapshot.await()

            rpc.on("apply_note_change", applied(revision = 101L))
            transport.putNotes(uid, listOf(note(42L)))

            assertNull(
                rpc.baseRevisions().singleOrNull(),
                "the replaced generation's read refilled the map after the boundary",
            )
        }

    /** REV-8/§10: nothing is persisted, so a restart starts with no dataset-scoped revision state. */
    @Test
    fun `REV-8 a restart starts with empty revision state`() = runTest(timeout = TIMEOUT) {
        val first = RecordingRpc()
        val learned = transport(first)
        first.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        learned.fetchNotes(uid)

        val rpc = RecordingRpc()
        rpc.on("apply_note_change", applied(revision = 101L))
        transport(rpc).putNotes(uid, listOf(note(42L)))

        assertNull(rpc.baseRevisions().singleOrNull(), "revision state survived a fresh process")
    }

    /** REV-9: the boundary is the dataset isolation, not the sign-in — same uid, no isolation, kept. */
    @Test
    fun `REV-9 signing back in without replacing the dataset keeps its revisions`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        rpc.on("apply_note_change", applied(revision = 101L))
        val stateStore = FakeNoteSyncStateStore().apply { setLastMergedUserId(uid) }

        transport.fetchNotes(uid)
        val sameDataset = LocalAccountIsolator(
            FakeNoteRepository(),
            stateStore,
            RecordingSyncCoordinator(),
            clearDatasetScopedInMemoryState = { transport.clearDatasetScopedState() },
        )
        sameDataset.isolateIfAccountChanged(uid)     // same account, same library: no boundary
        transport.putNotes(uid, listOf(note(42L)))

        assertEquals(
            listOf(100L),
            rpc.baseRevisions(),
            "a sign-in that keeps the library must keep its optimistic-concurrency state",
        )
    }


    /** REV-10/§2: an operation whose generation check passed cannot mutate after the reset. */
    @Test
    fun `REV-10 a write decided before the reset cannot land after it`() = runTest(timeout = TIMEOUT) {
        val store = RevisionStateStore()
        store.write(store.currentEpoch(), uid, 42L, 100L)          // generation N learns 42 -> 100
        val checkedEpoch = store.currentEpoch()                     // an operation's generation check

        store.resetDataset()                                        // the boundary crosses

        store.write(checkedEpoch, uid, 42L, 100L)                   // its pending write arrives late
        assertNull(
            store.read(store.currentEpoch(), uid, 42L),
            "a write that passed its check before the reset landed in the replacement dataset",
        )
        assertEquals(1L, store.currentEpoch(), "the reset did not publish exactly one new epoch")
    }

    /** REV-11/§8: a read decided before the reset cannot consume the replacement's state. */
    @Test
    fun `REV-11 a read decided before the reset cannot see after it`() = runTest(timeout = TIMEOUT) {
        val store = RevisionStateStore()
        store.write(store.currentEpoch(), uid, 42L, 100L)
        val oldEpoch = store.currentEpoch()

        store.resetDataset()
        store.write(store.currentEpoch(), uid, 42L, 200L)           // N+1 relearns its own revision

        assertNull(store.read(oldEpoch, uid, 42L), "a replaced dataset's read saw the replacement's revision")
        assertEquals(200L, store.read(store.currentEpoch(), uid, 42L), "the replacement kept its own revision")
    }

    /** REV-13/§10: reset publishes the new epoch and the empty state as one transition. */
    @Test
    fun `REV-13 reset publishes a new epoch and empty state together`() = runTest(timeout = TIMEOUT) {
        val store = RevisionStateStore()
        store.write(store.currentEpoch(), uid, 42L, 100L)

        val published = store.resetDataset()

        assertEquals(published, store.currentEpoch(), "the reset returned an epoch that is not the active one")
        assertNull(store.read(published, uid, 42L), "the new epoch still carried the replaced dataset's revision")
        assertNull(store.read(published - 1, uid, 42L), "the replaced epoch was still readable")
    }

    /** REV-12/§9: a suspended operation cannot adopt the replacement's generation on resume. */
    @Test
    fun `REV-12 a suspended delete cannot forget the replacement's revision`() = runTest(timeout = TIMEOUT) {
        val rpc = ParkingRpc("apply_note_delete", deleted())
        val transport = transport(rpc)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        transport.fetchNotes(uid)                                   // generation N learns 42 -> 100

        val deleting = async(Dispatchers.IO) { transport.deleteNotes(uid, listOf(42L)) }
        rpc.awaitParked()                                           // inside the delete RPC, generation N
        transport.clearDatasetScopedState()                         // the boundary crosses mid-flight
        rpc.release()
        deleting.await()

        // The replacement generation learns its own revision, which the late delete must not touch.
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 200L))
        transport.fetchNotes(uid)
        rpc.on("apply_note_change", applied(revision = 201L))
        transport.putNotes(uid, listOf(note(42L)))

        assertEquals(
            listOf(200L),
            rpc.baseRevisions(),
            "the resumed operation re-captured the new epoch, or forgot what the replacement had learned",
        )
    }


    /**
     * REV-14/§2: an operation issued under N, whose *first* revision-state use comes after the boundary,
     * cannot consume the replacement dataset's revision.
     *
     * This is the grant-first ordering F-10 permits: the identity (and so the operation's dataset binding)
     * is captured under the originating token, the isolation completes, and only then does the physical
     * RPC begin. The binding — not the current epoch — decides which revisions this operation may see.
     */
    @Test
    fun `REV-14 a grant-first operation cannot consume the replacement revision`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val store = RevisionStateStore()
        val transport = transport(rpc, store)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        transport.fetchNotes(uid)
        val issued = identityFor(store)                       // issued under N, before the boundary

        store.resetDataset()                                  // the boundary completes
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 200L))
        transport.fetchNotes(uid)                             // the replacement learns its own revision
        rpc.on("apply_note_change", applied(revision = 201L))

        transport.putNotes(issued, listOf(note(42L)))         // the issued operation's physical RPC

        assertNull(
            rpc.baseRevisions().singleOrNull(),
            "the issued operation borrowed the replacement dataset's revision",
        )
    }

    /** REV-15/§9: nor may it remove or overwrite the replacement's revision. */
    @Test
    fun `REV-15 a grant-first delete cannot touch replacement revision state`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val store = RevisionStateStore()
        val transport = transport(rpc, store)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 100L))
        transport.fetchNotes(uid)
        val issued = identityFor(store)

        store.resetDataset()
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 200L))
        transport.fetchNotes(uid)
        rpc.on("apply_note_delete", deleted())

        transport.deleteNotes(issued, listOf(42L))

        assertEquals(
            200L,
            store.read(store.currentEpoch(), uid, 42L),
            "the issued delete removed or overwrote the replacement dataset's revision",
        )
    }

    /** REV-16/§17: the current generation is unaffected — bound and uid-only paths both still work. */
    @Test
    fun `REV-16 a current-generation operation consumes the current revision normally`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val store = RevisionStateStore()
        val transport = transport(rpc, store)
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 200L))
        rpc.on("apply_note_change", applied(revision = 201L))
        transport.fetchNotes(uid)

        transport.putNotes(identityFor(store), listOf(note(42L)))     // bound to the active generation

        assertEquals(
            listOf(200L),
            rpc.baseRevisions(),
            "binding the generation broke ordinary optimistic concurrency",
        )
    }

    /** REV-17/§9: one capture per identity, reused across calls that straddle a boundary. */
    @Test
    fun `REV-17 the revision epoch is captured exactly once per operation`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val store = RevisionStateStore()
        val transport = transport(rpc, store)
        var captures = 0
        val provider = RemoteIdentityProvider(
            accessTokenProvider = { TOKEN },
            sessionOwnerId = { uid },
            revisionEpochProvider = {
                captures++
                store.currentEpoch()
            },
        )
        val issued = provider.capture(LocalCommitGate.capture(uid))!!

        store.resetDataset()
        transport.putNotes(issued, listOf(note(42L)))
        transport.putNotes(issued, listOf(note(42L)))

        assertEquals(1, captures, "the operation re-read the active generation instead of using its binding")
        // Both calls reached the RPC — asserted on the calls, not on the revisions, because "no base
        // revision" is the correct answer here and an absent value is exactly what must not be lost.
        assertEquals(2, rpc.calls.count { it.first == "apply_note_change" }, "the calls did not reach the RPC")
        assertTrue(
            rpc.baseRevisions().isEmpty(),
            "a call on the issued operation borrowed a revision: ${rpc.baseRevisions()}",
        )
    }

    /** REV-18/§11: a stale issued read cannot populate the replacement's revision state. */
    @Test
    fun `REV-18 a stale issued snapshot cannot populate replacement state`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val store = RevisionStateStore()
        val transport = transport(rpc, store)
        val issued = identityFor(store)
        store.resetDataset()

        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 300L))
        transport.fetchNotes(issued)                          // the issued read completes late

        assertNull(
            store.read(store.currentEpoch(), uid, 42L),
            "an issued operation from the replaced dataset published its revisions into the replacement",
        )
    }


    /**
     * REV-19/§11: what the late issued operation actually puts on the wire, and why that is safe.
     *
     * The backend proof (`notelikeus_note_occ_null_base_revision.test.sql`, OCC-NULL-1/2/4) shows that a
     * null base revision is *create-only*: against an existing note both `apply_note_change` and
     * `apply_note_delete` answer `conflict` and touch nothing. This lane pins the payload that reaches
     * them — an explicit null, never the replacement dataset's revision — and that the delete path, which
     * has no way to express "delete without a base", sends nothing at all.
     */
    @Test
    fun `REV-19 a late issued operation never sends the replacement revision`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val store = RevisionStateStore()
        val transport = transport(rpc, store)
        val issued = identityFor(store)                  // the operation issued under the old dataset
        store.resetDataset()                             // the boundary crosses behind it
        rpc.on("fetch_full_snapshot", snapshotWith(42L, revision = 200L))
        rpc.on("apply_note_change", applied(revision = 201L))
        rpc.on("apply_note_delete", deleted())
        transport.fetchNotes(identityFor(store))         // the replacement knows 42 -> 200

        transport.putNotes(issued, listOf(note(42L)))
        transport.deleteNotes(issued, listOf(42L))

        val writes = rpc.calls.filter { it.first == "apply_note_change" }
        assertEquals(1, writes.size, "the late write did not reach the RPC")
        assertTrue(
            writes.single().second["p_base_revision"] is JsonNull,
            "the late operation did not send an explicit null base: ${writes.single().second}",
        )
        assertTrue(
            rpc.baseRevisions().none { it == 200L },
            "the late operation borrowed the replacement dataset's revision",
        )
        assertTrue(
            rpc.calls.none { it.first == "apply_note_delete" },
            "the late delete was sent unbased; the create-only semantics only cover writes it can express",
        )
    }

    private fun note(id: Long) = Note(
        id = id,
        title = "n$id",
        content = "",
        timestamp = 1L,
        color = 0,
    )

    private fun deleted() = buildJsonObject {
        put("status", JsonPrimitive("applied"))
    }

    private fun applied(revision: Long) = buildJsonObject {
        put("status", JsonPrimitive("applied"))
        put("revision", JsonPrimitive(revision))
        put("server_updated_at", JsonPrimitive(1L))
    }

    private fun snapshotWith(noteId: Long, revision: Long) = buildJsonObject {
        put(
            "notes",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("note_id", JsonPrimitive(noteId.toString()))
                        put("local_id", JsonPrimitive(noteId))
                        put("revision", JsonPrimitive(revision))
                        put("title", JsonPrimitive("n$noteId"))
                        put("content", JsonPrimitive(""))
                        put("client_timestamp", JsonPrimitive(1L))
                        put("color", JsonPrimitive(0))
                        put("position", JsonPrimitive(0))
                    },
                )
            },
        )
        put("tombstones", buildJsonArray { })
        put("note_count", JsonPrimitive(1))
    }

    /** Records the `p_base_revision` of every note write. */
    private open class RecordingRpc : SupabaseRpcClient {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        private val responses = mutableMapOf<String, JsonObject>()

        fun on(functionName: String, response: JsonObject) {
            responses[functionName] = response
        }

        fun baseRevisions(): List<Long> = calls
            .filter { it.first == "apply_note_change" }
            .mapNotNull { it.second["p_base_revision"]?.jsonPrimitive?.longOrNull }

        fun record(functionName: String, body: JsonObject) {
            calls += functionName to body
        }

        fun answer(functionName: String): JsonObject = responses[functionName] ?: buildJsonObject { }

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject {
            record(functionName, body)
            return answer(functionName)
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            callRpc(functionName, body)

        // The identity-bound overloads: an issued operation reaches the RPC through these, and the
        // recorder answers them from the same script so a lane can assert what the operation sent.
        override suspend fun callRpc(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonObject = callRpc(functionName, body)

        override suspend fun callRpcElement(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonElement = callRpc(functionName, body)
    }

    /** An RPC that parks on one function until released, so the boundary can be crossed mid-flight. */
    private class ParkingRpc(
        private val parkedFunction: String,
        private val parkedResponse: JsonObject,
    ) : SupabaseRpcClient {
        private val started = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        private val delegate = RecordingRpc()

        suspend fun awaitParked() = started.await()

        fun release() {
            released.complete(Unit)
        }

        fun on(functionName: String, response: JsonObject) = delegate.on(functionName, response)

        fun baseRevisions(): List<Long> = delegate.baseRevisions()

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject {
            delegate.record(functionName, body)
            if (functionName == parkedFunction) {
                started.complete(Unit)
                released.await()
                return parkedResponse
            }
            return delegate.answer(functionName)
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            callRpc(functionName, body)
    }

    /** A snapshot read that parks until released, so the boundary can be crossed mid-flight. */
    private class BlockingSnapshotRpc(private val snapshot: JsonObject) : SupabaseRpcClient {
        private val started = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        private val delegate = RecordingRpc()

        suspend fun awaitStarted() = started.await()

        fun release() {
            released.complete(Unit)
        }

        fun on(functionName: String, response: JsonObject) = delegate.on(functionName, response)

        fun baseRevisions(): List<Long> = delegate.baseRevisions()

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject {
            if (functionName == "fetch_full_snapshot") {
                started.complete(Unit)
                released.await()
                return snapshot
            }
            return delegate.callRpc(functionName, body)
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            callRpc(functionName, body)
    }

    private companion object {
        const val UID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_UID = "22222222-2222-4222-8222-222222222222"
        const val TOKEN = "token-a"
        val TIMEOUT = 60.seconds
    }
}
