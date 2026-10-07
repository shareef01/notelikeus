package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.DesktopSupabaseRpcClient
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.RemoteIdentityProvider
import com.aus.notelikeus.data.remote.SupabaseAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The engine on the **real** note transport path — R13A, closing F-1 end to end.
 *
 * [com.aus.notelikeus.data.remote.SupabaseNoteTransportIdentityTest] proves the transport's
 * identity-bound members send the captured bearer when they are *called* with an identity. It cannot
 * say anything about whether the engine ever calls them: the engine is what decides, and before this
 * phase it decided to call the `uid`-keyed members, which resolve the bearer from whatever session is
 * live when the request is finally built.
 *
 * So these tests drive a real [NoteSyncEngine] through [SupabaseNoteTransport] and a real
 * [DesktopSupabaseRpcClient] against a local HTTP server, and assert on the `Authorization` header that
 * actually arrived. Nothing is stubbed below the engine: the only seam is the session itself.
 *
 * ## What "the session moves to B" means here
 *
 * `SupabaseAccessTokenProvider` hands back a **bare token with no owner binding**, and the vulnerable
 * path re-read that token at send time. So the transition these tests model is the *credential*: the
 * operation originates under A and the first read (its own capture) returns A's token, while every read
 * after that returns B's. That is exactly the shape F-1 exploited — A's arguments on the wire under B's
 * bearer — and it is modelled without moving the `uid`, because moving it is not what made the old path
 * wrong. The lanes that do need an owner change drive it explicitly (`RIDE2E-4`, `RIDE2E-7`,
 * `RIDE2E-8`, `RIDE2E-9`).
 *
 * ## Where a `FakeCloudNoteTransport` cannot be used
 *
 * The adapter the rest of the suite uses forwards `fetchNotes(identity)` to
 * `fetchNotes(identity.ownerId)`, which makes a double convenient — and would make a leaked
 * live-session call *indistinguishable* from a correctly bound one. So the lanes whose claim is "the
 * engine used the identity contract" use [RecordingIdentityTransport], which implements
 * [IdentityBoundNoteTransport] directly and has no `uid`-keyed member to reach at all.
 */
class NoteSyncEngineRemoteIdentityF1Test {

    private lateinit var server: HttpServer

    /** The `Authorization` header of every RPC that actually reached the server, in order. */
    private val authorizations = mutableListOf<String>()

    /** The RPC names that reached the server, in order. */
    private val functions = mutableListOf<String>()

    /** Every read of the app's one token provider, i.e. every live-session credential read. */
    private var providerCalls = 0

    /**
     * Credential reads that landed *after* the operation's first request had already reached the
     * server — the send-time lookups the vulnerable path performs, and the ones the fix removes.
     */
    private var providerCallsDuringSend = 0

    private var firstRequestSeen = false

    private var liveToken: String? = TOKEN_A
    private var sessionOwner: String? = OWNER_A

    /** When set, the credential moves to B's on every read after the operation's own capture. */
    private var credentialMovesAfterFirstRead = false

    /** Parks the credential read so a test can land a boundary inside it. */
    private var credentialGate: ParkGate? = null

    /** When set, every request is answered with this status instead of a success body. */
    private var rejectWith: Int? = null

    /** The whole-library payload `fetch_full_snapshot` answers with, and its matching count. */
    private var snapshotNotes: String = "[]"
    private var snapshotNoteCount: Int = 0

    /** The account the engine's `uidProvider` names. Never moved — the operation starts as A. */
    private val originOwner = OWNER_A

    @BeforeTest
    fun startServer() {
        authorizations.clear()
        functions.clear()
        providerCalls = 0
        providerCallsDuringSend = 0
        firstRequestSeen = false
        liveToken = TOKEN_A
        sessionOwner = OWNER_A
        credentialMovesAfterFirstRead = false
        credentialGate = null
        rejectWith = null
        snapshotNotes = "[]"
        snapshotNoteCount = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest/v1/rpc/") { exchange ->
            val function = exchange.requestURI.path.substringAfterLast('/')
            authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            functions += function
            firstRequestSeen = true
            exchange.requestBody.readBytes()
            val rejected = rejectWith
            val body = if (rejected != null) {
                """{"message":"JWT expired"}"""
            } else {
                when (function) {
                    "fetch_full_snapshot" ->
                        """{"notes":$snapshotNotes,"note_count":$snapshotNoteCount,"tombstones":[]}"""
                    "restore_note" -> """{"status":"applied","revision":7,"server_updated_at":2000}"""
                    "delete_all_user_cloud_data" -> """{"status":"ok"}"""
                    else -> """{"status":"applied","revision":1,"server_updated_at":1000}"""
                }
            }.toByteArray()
            exchange.sendResponseHeaders(rejected ?: 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    // ---- the two pre-fix reds ----

    /**
     * Red A — a **mutation** that began as A must send A's bearer.
     *
     * `uploadNote` issues three round trips (a tombstone read, a note read, then the upload), so the
     * transition is visible on the wire without any artificial seam: the first request carries A's
     * token because the session had not moved yet, and every request after it carries B's.
     *
     * Recorded red before the fix:
     * `expected [Bearer token-a, Bearer token-a, Bearer token-a] but was [Bearer token-a, Bearer token-b, Bearer token-b]`
     */
    @Test
    fun `RIDE2E-1 (engine) a mutation that began as A sends A's captured bearer`() =
        runTest(timeout = TIMEOUT) {
            credentialMovesAfterFirstRead = true
            val noteDao = FakeNoteDao().apply { seedNote(NOTE_ID) }
            val engine = engine(noteDao = noteDao)

            val result = engine.uploadNote(NOTE_ID)

            assertEquals(
                THREE_REQUESTS_AS_A,
                authorizations,
                "the engine's mutation did not authenticate as the account it started as",
            )
            assertEquals(
                1,
                providerCalls,
                "the engine read the live credential more than once for one logical operation",
            )
            assertEquals(
                0,
                providerCallsDuringSend,
                "the engine's note mutation consulted the live session at send time",
            )
            assertTrue(
                result.isSuccess,
                "a transport failure replaced the identity outcome: ${result.exceptionOrNull()}",
            )
        }

    /**
     * Red B — a **read** that began as A must send A's bearer.
     *
     * `downloadAllNotes` opens with the cloud-tombstone merge and then the full snapshot, so two
     * requests go out for one logical operation and both must be A's.
     *
     * Recorded red before the fix:
     * `expected [Bearer token-a, Bearer token-a] but was [Bearer token-a, Bearer token-b]`
     */
    @Test
    fun `RIDE2E-2 (engine) a read that began as A sends A's captured bearer`() =
        runTest(timeout = TIMEOUT) {
            credentialMovesAfterFirstRead = true
            val engine = engine()

            engine.downloadAllNotes()

            assertEquals(
                TWO_REQUESTS_AS_A,
                authorizations,
                "the engine's read did not authenticate as the account it started as",
            )
            assertEquals(
                1,
                providerCalls,
                "the engine read the live credential more than once for one logical operation",
            )
            assertEquals(
                0,
                providerCallsDuringSend,
                "the engine's note read consulted the live session at send time",
            )
        }

    // ---- RIDE2E-3 / RIDE2E-4: capture refuses, and the operation abandons ----

    /**
     * A same-uid dataset replacement landing between the operation's own token capture and its
     * identity capture.
     *
     * The uid is identical on both sides — `A → sign out → A` — so a uid comparison would accept the
     * stale capture. Only the generation can see it, and the engine must abandon the whole operation
     * rather than run it under the dataset that replaced the originating one. The credential read
     * itself is deliberately *before* that verdict, because holding the gate across it would make
     * isolation wait on an auth round trip; the refusal is what makes the read harmless.
     */
    @Test
    fun `RIDE2E-3 (engine) a same-uid generation replacement refuses the identity capture`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingIdentityTransport()
            val engine = engine(
                noteDao = FakeNoteDao().apply { seedNote(NOTE_ID) },
                transport = transport,
                // The engine reads the account before it captures the identity, so isolating here lands
                // the replacement in exactly that window with the uid untouched.
                uidProvider = {
                    LocalCommitGate.isolate { }
                    Result.success(originOwner)
                },
            )

            val result = engine.uploadNote(NOTE_ID)

            assertEquals(originOwner, sessionOwner, "precondition: the uid never changed at all")
            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
            assertEquals(
                emptyList(),
                transport.calls,
                "a capture from a replaced dataset still reached the transport",
            )
        }

    /**
     * The session moving to another account *while the credential is being read*.
     *
     * `SupabaseAccessTokenProvider` returns a bare token, so the owner is proved by a before/after read
     * around the acquisition. A refresh that lands after a sign-in yields a mismatched pair, and the
     * engine must abandon the operation rather than pair A's arguments with B's bearer.
     */
    @Test
    fun `RIDE2E-4 (engine) an A to B switch during identity capture refuses`() =
        runTest(timeout = TIMEOUT) {
            val gate = ParkGate().also { credentialGate = it }
            val transport = RecordingIdentityTransport()
            val engine = engine(
                noteDao = FakeNoteDao().apply { seedNote(NOTE_ID) },
                transport = transport,
            )

            val upload = async { engine.uploadNote(NOTE_ID) }
            gate.awaitParked()
            // The session moves to B inside the credential read itself.
            sessionOwner = OWNER_B
            liveToken = TOKEN_B
            gate.release()
            val result = upload.await()

            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
            assertEquals(
                emptyList(),
                transport.calls,
                "the engine ran an operation whose owner changed while its credential was read",
            )
        }

    // ---- RIDE2E-5 / RIDE2E-6: one identity per logical operation, no drift ----

    /**
     * One logical operation captures exactly one identity, and every call it makes carries that one.
     *
     * `uploadAllNotes` is the widest flow in the engine: a tombstone merge, a snapshot read, an upload
     * and a metadata write. All of it is one operation, so all of it is one identity — a second capture
     * anywhere below would be the laundering this closes.
     */
    @Test
    fun `RIDE2E-5 (engine) one identity is captured per logical operation`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingIdentityTransport().apply { seedCloudNote(SECOND_ID) }
            val noteDao = FakeNoteDao().apply {
                seedNote(NOTE_ID)
                seedNote(SECOND_ID)
            }
            val engine = engine(noteDao = noteDao, transport = transport)

            val result = engine.uploadAllNotes()

            assertTrue(result.isSuccess, "a valid upload failed: ${result.exceptionOrNull()}")
            assertTrue(
                transport.calls.size >= 3,
                "precondition: the flow made several calls, got ${transport.calls}",
            )
            assertEquals(1, providerCalls, "the operation read the live credential more than once")
            assertEquals(
                1,
                transport.calls.map { it.second }.distinct().size,
                "the operation used more than one identity across its calls: ${transport.calls}",
            )
            assertEquals(
                OperationRemoteIdentity(OWNER_A, TOKEN_A),
                transport.calls.first().second,
                "the captured identity is not the originating account's",
            )
        }

    /**
     * A multi-round-trip operation whose credential moves after its first request still sends one bearer
     * throughout.
     *
     * This is the shape F-1 needed four requests to expose before the fix: only the first carried A's
     * token, and each later one re-resolved the live session. Now the whole flow is one identity.
     */
    @Test
    fun `RIDE2E-6 (engine) a multi-RPC flow does not drift identity`() =
        runTest(timeout = TIMEOUT) {
            credentialMovesAfterFirstRead = true
            val noteDao = FakeNoteDao().apply {
                seedNote(NOTE_ID)
                seedNote(SECOND_ID)
            }
            val engine = engine(noteDao = noteDao)

            val result = engine.uploadAllNotes()

            assertTrue(result.isSuccess, "a valid upload failed: ${result.exceptionOrNull()}")
            assertEquals(
                4,
                authorizations.size,
                "precondition: the flow made several round trips, made ${authorizations.size}",
            )
            assertEquals(
                List(authorizations.size) { "Bearer $TOKEN_A" },
                authorizations,
                "one of the flow's ${authorizations.size} requests drifted to another account's bearer",
            )
            assertEquals(1, providerCalls, "the flow re-read the live credential mid-flight")
            assertEquals(0, providerCallsDuringSend, "a later request in the flow re-resolved the live session")
        }

    // ---- RIDE2E-7 / RIDE2E-8 / RIDE2E-9: the destructive and failing paths ----

    /**
     * RIDE2E-7 — the cloud wipe cannot borrow B's bearer.
     *
     * The wipe is the most destructive remote mutation in the engine. Its grant is a *generation*
     * authority, so a session that moves before the request is built must change nothing about which
     * credentials the request carries: the grant wins, and the wipe still goes out as A.
     */
    @Test
    fun `RIDE2E-7 (engine) a cloud wipe cannot borrow the live session's bearer`() =
        runTest(timeout = TIMEOUT) {
            val engine = engine(
                // The session moves at the last moment before the wipe: after its identity was
                // captured and after its grant was issued, and before the request is built.
                beforeRemoteMutationStart = { mutation ->
                    if (mutation.startsWith("wipe.")) {
                        sessionOwner = OWNER_B
                        liveToken = TOKEN_B
                    }
                },
            )

            val result = engine.deleteAllCloudData()

            assertTrue(result.isSuccess, "a valid wipe failed: ${result.exceptionOrNull()}")
            assertEquals(
                listOf("Bearer $TOKEN_A", "Bearer $TOKEN_A"),
                authorizations,
                "the wipe did not authenticate as the account that issued it",
            )
            assertEquals(listOf("fetch_full_snapshot", "delete_all_user_cloud_data"), functions)
            assertEquals(
                1,
                providerCalls,
                "the wipe looked the credential up again after the session moved",
            )
            assertEquals(0, providerCallsDuringSend, "the wipe resolved the live session at send time")
        }

    /**
     * RIDE2E-8 — a restore cannot borrow B's bearer.
     *
     * Representative restore: the operation originates as A, its identity and its grant are taken under
     * A, and the session moves to B before the already-issued call is built.
     */
    @Test
    fun `RIDE2E-8 (engine) a restore cannot borrow the live session's bearer`() =
        runTest(timeout = TIMEOUT) {
            val noteDao = FakeNoteDao().apply { seedNote(NOTE_ID, trashed = true) }
            val engine = engine(
                noteDao = noteDao,
                beforeRemoteMutationStart = { mutation ->
                    if (mutation.startsWith("restore.")) {
                        sessionOwner = OWNER_B
                        liveToken = TOKEN_B
                    }
                },
            )

            val result = engine.restoreNote(NOTE_ID)

            assertTrue(result.isSuccess, "a valid restore failed: ${result.exceptionOrNull()}")
            assertEquals(
                listOf("Bearer $TOKEN_A"),
                authorizations,
                "the restore did not authenticate as the account that issued it",
            )
            assertEquals(listOf("restore_note"), functions)
            assertEquals(1, providerCalls, "the restore looked the credential up again after the session moved")
            assertEquals(0, providerCallsDuringSend, "the restore resolved the live session at send time")
        }

    /**
     * RIDE2E-9 — an expired or revoked A credential fails closed.
     *
     * The server rejects A's bearer while the live session holds a perfectly good B token. The operation
     * must fail: retrying as B, or re-reading the live session after the rejection, would turn an expired
     * credential into a cross-account write. The engine does neither.
     */
    @Test
    fun `RIDE2E-9 (engine) an expired A credential does not fall back to the live session`() =
        runTest(timeout = TIMEOUT) {
            rejectWith = 401
            val noteDao = FakeNoteDao().apply { seedNote(NOTE_ID) }
            val stateStore = FakeNoteSyncStateStore().apply {
                setLastMergedUserId(originOwner)
                updateKnownServerRevision(NOTE_ID, BASELINE_REVISION)
            }
            val engine = engine(
                noteDao = noteDao,
                stateStore = stateStore,
                // The session moves to B — with a credential the server *would* accept — after the
                // operation's identity was captured and before its already-granted call is built.
                beforeRemoteMutationStart = { mutation ->
                    if (mutation.startsWith("delete.")) {
                        sessionOwner = OWNER_B
                        liveToken = TOKEN_B
                    }
                },
            )

            val result = engine.deleteNote(NOTE_ID)

            assertTrue(result.isFailure, "an expired credential was reported as success")
            assertEquals(
                listOf("Bearer $TOKEN_A"),
                authorizations,
                "the operation retried with the live session's bearer after A's credential was rejected",
            )
            assertEquals(
                listOf("apply_note_delete"),
                functions,
                "the rejected operation issued something other than its own single request",
            )
            assertEquals(
                1,
                providerCalls,
                "the rejected operation re-read the live credential instead of failing closed",
            )
            assertEquals(0, providerCallsDuringSend, "a retry re-resolved the live session")
        }

    // ---- RIDE2E-12: every protected call is the identity-bound member ----

    /**
     * The sync-metadata document is an account-owned remote write like any other, so it goes out on the
     * identity contract too — not on the `uid`-keyed member whose bearer comes from the session.
     *
     * [RecordingIdentityTransport] has no `uid`-keyed members at all, so this cannot pass by accident:
     * the engine either calls `writeSyncMeta(identity, …)` or it does not compile.
     */
    @Test
    fun `RIDE2E-12 (engine) writeSyncMeta is routed through the identity contract`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingIdentityTransport()
            val engine = engine(transport = transport)

            engine.uploadAllNotes()

            val metaWrites = transport.calls.filter { it.first == "writeSyncMeta" }
            assertEquals(
                1,
                metaWrites.size,
                "the sync-metadata write did not happen exactly once: ${transport.calls}",
            )
            assertEquals(
                OperationRemoteIdentity(OWNER_A, TOKEN_A),
                metaWrites.single().second,
                "the sync-metadata write did not carry the operation's identity",
            )
        }

    // ---- RIDE2E-13: no credential I/O under the gate ----

    /**
     * The credential read may refresh, i.e. it may suspend on an auth round trip. It must therefore run
     * *outside* `LocalCommitGate`, or account isolation would wait on the network. Only the final
     * generation validation is taken under the gate, and only briefly.
     */
    @Test
    fun `RIDE2E-13 (engine) credential I-O does not run under LocalCommitGate`() =
        runTest(timeout = TIMEOUT) {
            val gate = ParkGate().also { credentialGate = it }
            val transport = RecordingIdentityTransport()
            val engine = engine(
                noteDao = FakeNoteDao().apply { seedNote(NOTE_ID) },
                transport = transport,
            )

            val upload = async { engine.uploadNote(NOTE_ID) }
            gate.awaitParked()

            assertFalse(
                LocalCommitGate.mutex.isLocked,
                "the engine performed credential I/O while holding LocalCommitGate",
            )
            gate.release()
            assertTrue(
                upload.await().isSuccess,
                "the operation did not complete after its credential read was released",
            )
            assertTrue(
                transport.calls.isNotEmpty(),
                "precondition: the released operation actually did its remote work",
            )
            // The gate is immediately usable: nothing was left held by the capture.
            var isolated = false
            LocalCommitGate.isolate { isolated = true }
            assertTrue(isolated, "the gate could not be taken after the capture completed")
        }

    // ---- fixtures ----

    /**
     * The production shape below the engine: the real transport over the real RPC client, speaking to
     * the local server, with the engine's identity captured from that same session.
     *
     * One [SupabaseAccessTokenProvider] instance is shared by the RPC client and the identity provider,
     * exactly as production shares one — so `providerCalls` counts *every* read of the app's session,
     * whether it comes from the engine's capture or from a send-time lookup on the vulnerable path.
     */
    private fun engine(
        noteDao: FakeNoteDao = FakeNoteDao(),
        transport: IdentityBoundNoteTransport? = null,
        stateStore: FakeNoteSyncStateStore = FakeNoteSyncStateStore().apply {
            setLastMergedUserId(originOwner)
        },
        uidProvider: suspend () -> Result<String> = { Result.success(originOwner) },
        beforeRemoteMutationStart: suspend (String) -> Unit = {},
    ): NoteSyncEngine {
        val session = liveSession()
        return NoteSyncEngine(
            transport = transport ?: SupabaseNoteTransport(
                DesktopSupabaseRpcClient(
                    supabaseUrl = "http://127.0.0.1:${server.address.port}",
                    anonKey = "anon",
                    accessTokenProvider = session,
                ),
            ),
            remoteIdentityProvider = RemoteIdentityProvider(
                accessTokenProvider = session,
                sessionOwnerId = { sessionOwner },
            ),
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = uidProvider,
            platform = "desktop",
            localCommitTokenProvider = LocalCommitTokenProvider {
                LocalCommitGate.captureCoherent { sessionOwner }
            },
            beforeRemoteMutationStart = beforeRemoteMutationStart,
        )
    }

    /**
     * The app's one live session. A test can park the read so a boundary can be landed inside it, or ask
     * for the credential to move to B's on every read after the operation's own capture.
     */
    private fun liveSession() = SupabaseAccessTokenProvider {
        providerCalls++
        if (firstRequestSeen) providerCallsDuringSend++
        credentialGate?.park()
        if (credentialMovesAfterFirstRead && providerCalls > 1) TOKEN_B else liveToken
    }

    private fun FakeNoteDao.seedNote(id: Long, trashed: Boolean = false) {
        notes[id] = Note(
            id = id,
            title = "A's note $id",
            content = "A's body",
            timestamp = 1_000L,
            color = 0,
            isTrashed = trashed,
        ).toNoteEntity()
    }

    /** Parks a suspension point so a test can land a boundary inside it, with no sleeps. */
    private class ParkGate {
        private val parked = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun awaitParked() = parked.await()

        fun release() {
            released.complete(Unit)
        }

        suspend fun park() {
            parked.complete(Unit)
            released.await()
        }
    }

    /**
     * An identity-bound transport that **cannot** reach a `uid`-keyed member.
     *
     * The test adapter the rest of the suite uses forwards `fetchNotes(identity)` to
     * `fetchNotes(identity.ownerId)`, and it accepts every `uid` call the engine could make. Here there
     * is no such member: the engine either calls the identity contract or it does not compile, which is
     * what makes these lanes a proof rather than a convention. Every call is recorded with the identity
     * it carried, in order, so drift and recapture are both visible.
     */
    private class RecordingIdentityTransport(
        private val delegate: FakeCloudNoteTransport = FakeCloudNoteTransport(),
    ) : IdentityBoundNoteTransport {

        val calls = mutableListOf<Pair<String, OperationRemoteIdentity>>()

        override suspend fun fetchNotes(identity: OperationRemoteIdentity): List<CloudNoteRecord> {
            calls += "fetchNotes" to identity
            return delegate.fetchNotes(identity.ownerId)
        }

        override suspend fun fetchNotesSnapshot(identity: OperationRemoteIdentity): CloudNoteSnapshot {
            calls += "fetchNotesSnapshot" to identity
            return delegate.fetchNotesSnapshot(identity.ownerId)
        }

        override suspend fun fetchNote(identity: OperationRemoteIdentity, noteId: Long): CloudNoteRecord? {
            calls += "fetchNote($noteId)" to identity
            return delegate.fetchNote(identity.ownerId, noteId)
        }

        override suspend fun fetchTombstones(identity: OperationRemoteIdentity): Map<Long, Long> {
            calls += "fetchTombstones" to identity
            return delegate.fetchTombstones(identity.ownerId)
        }

        override suspend fun fetchTombstone(identity: OperationRemoteIdentity, noteId: Long): Long? {
            calls += "fetchTombstone($noteId)" to identity
            return delegate.fetchTombstone(identity.ownerId, noteId)
        }

        override suspend fun putNotes(
            identity: OperationRemoteIdentity,
            notes: List<Note>,
        ): Map<Long, CloudNoteTransport.PutResult> {
            calls += "putNotes" to identity
            return delegate.putNotes(identity.ownerId, notes)
        }

        override suspend fun deleteNote(
            identity: OperationRemoteIdentity,
            noteId: Long,
            baseRevision: Long,
        ): CloudNoteTransport.DeleteResult {
            calls += "deleteNote($noteId)" to identity
            return delegate.deleteNote(identity.ownerId, noteId, baseRevision)
        }

        override suspend fun deleteNotes(identity: OperationRemoteIdentity, noteIds: List<Long>) {
            calls += "deleteNotes" to identity
            delegate.deleteNotes(identity.ownerId, noteIds)
        }

        override suspend fun restoreNote(
            identity: OperationRemoteIdentity,
            note: Note,
        ): Map<Long, CloudNoteTransport.PutResult> {
            calls += "restoreNote" to identity
            return delegate.restoreNote(identity.ownerId, note)
        }

        override suspend fun writeTombstone(identity: OperationRemoteIdentity, noteId: Long, deletedAt: Long) {
            calls += "writeTombstone($noteId)" to identity
            delegate.writeTombstone(identity.ownerId, noteId, deletedAt)
        }

        override suspend fun deleteTombstones(identity: OperationRemoteIdentity, noteIds: List<Long>) {
            calls += "deleteTombstones" to identity
            delegate.deleteTombstones(identity.ownerId, noteIds)
        }

        override suspend fun writeSyncMeta(identity: OperationRemoteIdentity, noteCount: Int, platform: String) {
            calls += "writeSyncMeta" to identity
            delegate.writeSyncMeta(identity.ownerId, noteCount, platform)
        }

        override suspend fun deleteAllOwnedCloudData(identity: OperationRemoteIdentity) {
            calls += "deleteAllOwnedCloudData" to identity
            delegate.deleteAllOwnedCloudData(identity.ownerId)
        }

        /** Seeds a cloud row so a flow has something to read and reconcile against. */
        fun seedCloudNote(noteId: Long) {
            delegate.notes[noteId] = CloudNoteRecord(
                noteId = noteId,
                serverUpdatedAt = 1L,
                clientTimestamp = 1L,
                title = "cloud",
                content = "cloud",
                timestamp = 1L,
                color = 0,
                isPinned = false,
                isArchived = false,
                isTrashed = false,
                position = 0,
                reminderTimestamp = null,
                labels = emptyList(),
                checklistItems = emptyList(),
                revision = 1L,
            )
        }
    }

    private companion object {
        val THREE_REQUESTS_AS_A = List(3) { "Bearer $TOKEN_A" }
        val TWO_REQUESTS_AS_A = List(2) { "Bearer $TOKEN_A" }

        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
        const val TOKEN_A = "token-a"
        const val TOKEN_B = "token-b"
        const val NOTE_ID = 42L
        const val SECOND_ID = 43L
        const val BASELINE_REVISION = 3L

        val TIMEOUT = 60.seconds
    }
}
