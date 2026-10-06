package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.PendingDeletedAttachment
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Which credential an attachment-metadata RPC actually goes out with — R17, closing F-7.
 *
 * R16 fixed *when* a metadata mutation may start (a one-shot generation grant). It left *whose*
 * credential the request carries alone: the metadata implementation resolved the bearer from the live
 * session while the request was being built, so an operation that began as account A and suspended
 * could send its listing — or its purge — after B signed in, as B, with A's ids in the body. The grant
 * cannot see that: it answers "may this mutation start now?", never "as whom?".
 *
 * Like F-1's lanes, the defect lives *inside* the real client, so a fake `SupabaseRpcClient` cannot
 * show it. These lanes run a real local HTTP server against the real `DesktopSupabaseRpcClient` and
 * assert on the **`Authorization` header that actually arrived**, plus `providerCalls` — how often the
 * client read the live session at all. Metadata reads and metadata mutations are both covered: a
 * listing that drifts exposes B's rows to an A continuation just as a purge that drifts mutates them.
 */
class AttachmentMetadataRemoteIdentityFenceTest {

    private lateinit var server: HttpServer
    private val arrived = mutableListOf<Pair<String, String>>()
    /** How often the *client* read the live session while building a request. */
    private var providerCalls = 0
    /** How often the engine's identity capture read the live credential, once per operation. */
    private var captureReads = 0

    /** The mutable live session the *client* reads when nobody hands it an identity. */
    private var liveOwner: String? = OWNER_A
    private var liveToken: String? = TOKEN_A

    /** Set by a lane to replace the live session's credential once the capture has read it. */
    private var replaceLiveTokenAfterCapture: Boolean = false

    /** Server-side park, so a lane can move the generation while a request is on the wire. */
    private var parkFunction: String? = null
    private val requestArrived = CompletableDeferred<Unit>()
    private val requestRelease = CompletableDeferred<Unit>()

    private lateinit var tempDir: java.io.File
    private lateinit var staging: AttachmentStagingStore
    private var noteDao = FakeNoteDao()

    @BeforeTest
    fun startServer() {
        tempDir = Files.createTempDirectory("notelikeus-metadata-identity-test").toFile()
        staging = MemoryStagingStore()
        noteDao = FakeNoteDao()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest/v1/rpc/") { exchange ->
            val function = exchange.requestURI.path.substringAfterLast('/')
            val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            exchange.requestBody.readBytes()
            arrived += function to header
            if (function == parkFunction) {
                requestArrived.complete(Unit)
                runBlocking { requestRelease.await() }
            }
            // Kept as small separate predicates: one three-term condition is exactly what the
            // complexity rule flags, and the intent reads better split out anyway.
            val expiredPurge = function == PURGE && expiredForA && header == "Bearer $TOKEN_A"
            val body = when {
                expiredPurge -> ""
                function == LIST_PENDING -> """
                    [{"attachment_id":"$ATT_A","note_id":"$NOTE_ID","object_key":"owners/$OWNER_A/x"}]
                """.trimIndent()
                function == LIST_USER -> """
                    [{"attachment_id":"$ATT_A","note_id":"$NOTE_ID","object_key":"owners/$OWNER_A/x",
                      "mime_type":"image/png","size_bytes":3,"attachment_type":"image","created_at":1}]
                """.trimIndent()
                else -> "{}"
            }
            val knownBearer = header == "Bearer $TOKEN_A" || header == "Bearer $TOKEN_B"
            val served = if (expiredPurge) false else knownBearer
            val inFlight = if (served) 200 else 401
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(inFlight, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        // A small pool: one parked request must not block the requests a lane sends after it.
        server.executor = Executors.newFixedThreadPool(2)
        server.start()
    }

    @AfterTest
    fun stopServer() {
        requestRelease.complete(Unit)
        server.stop(0)
        tempDir.deleteRecursively()
    }

    private var expiredForA = false

    // ---- AMRID-1 / AMRID-5 / AMRID-6: a real mutation, and identity continuity across the sweep ----

    @Test
    fun `AMRID-1 a sweep under A purges with A's bearer after B signs in`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(OWNER_A)
        val identity = identityForA()
        val service = service()

        // The operation has captured its identity; now the live session becomes B's, which is what the
        // pre-fix send path would have used for both RPCs below.
        moveLiveSessionToB()

        val result = service.sweepPendingDeletedAttachments(token, identity, skipNoteId = { false })

        assertEquals(LocalCommitResult.Applied(Unit), result, "a valid sweep was refused")
        assertEquals(
            listOf(LIST_PENDING to "Bearer $TOKEN_A", PURGE to "Bearer $TOKEN_A"),
            arrived,
            "a metadata RPC went out bearing the live session's credentials instead of the operation's",
        )
        assertEquals(0, providerCalls, "the identity-bound path read the live session at send time")
    }

    @Test
    fun `AMRID-5 one identity, listing and purge together`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(OWNER_A)
        val identity = identityForA()
        moveLiveSessionToB()

        service().sweepPendingDeletedAttachments(token, identity)

        assertEquals(
            2,
            arrived.size,
            "the sweep did not perform exactly one listing and one purge: $arrived",
        )
        assertEquals(
            setOf("Bearer $TOKEN_A"),
            arrived.map { it.second }.toSet(),
            "the sweep used more than one credential for one logical operation: $arrived",
        )
    }

    // ---- AMRID-2 / AMRID-11: a metadata READ, through the service and through the engine ----

    @Test
    fun `AMRID-2 hydration's listing carries the operation's bearer, not the live one`() =
        runTest(timeout = TIMEOUT) {
            seedLocalNote()
            val token = LocalCommitGate.capture(OWNER_A)
            val identity = identityForA()
            moveLiveSessionToB()

            val result = service().hydrateAllNotes(token, identity)

            assertEquals(LocalCommitResult.Applied(1), result, "a valid hydration reported the wrong count")
            assertEquals(
                listOf(LIST_USER to "Bearer $TOKEN_A"),
                arrived,
                "the metadata read went out bearing the live session's credentials",
            )
            assertEquals(0, providerCalls, "the read consulted the live session at send time")
        }

    @Test
    fun `AMRID-11 the engine's hydration read uses the identity it captured`() = runTest(timeout = TIMEOUT) {
        seedLocalNote()
        replaceLiveTokenAfterCapture = true
        val engine = engine()

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess, "the download surfaced as a failure: ${result.exceptionOrNull()}")
        assertTrue(arrived.isNotEmpty(), "the download performed no metadata RPC at all")
        assertEquals(
            setOf("Bearer $TOKEN_A"),
            arrived.map { it.second }.toSet(),
            "the engine's metadata traffic used the live session's credentials: $arrived",
        )
        assertEquals(1, captureReads, "the download captured the live credential more than once")
        assertEquals(
            0,
            providerCalls,
            "the engine's metadata traffic consulted the live session at send time",
        )
    }

    // ---- AMRID-3 / AMRID-4: capture refusal, both orderings ----

    @Test
    fun `AMRID-3 a same-uid generation replacement refuses the capture`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(OWNER_A)
        val provider = RemoteIdentityProvider(
            accessTokenProvider = { TOKEN_A },
            sessionOwnerId = { liveOwner },
        )

        LocalCommitGate.isolate { }

        assertNull(
            provider.capture(token),
            "a same-uid replacement handed out an identity for the replaced dataset",
        )
    }

    @Test
    fun `AMRID-4 an account change during credential acquisition refuses the capture`() =
        runTest(timeout = TIMEOUT) {
            val token = LocalCommitGate.capture(OWNER_A)
            var reads = 0
            val provider = RemoteIdentityProvider(
                accessTokenProvider = { TOKEN_A },
                sessionOwnerId = {
                    reads++
                    if (reads == 1) OWNER_A else OWNER_B
                },
            )

            assertNull(
                provider.capture(token),
                "a credential acquired across an account change produced an identity anyway",
            )
        }

    @Test
    fun `AMRID-4b a refused capture performs no metadata RPC through the engine`() =
        runTest(timeout = TIMEOUT) {
            seedLocalNote()
            val engine = engine(
                identityProvider = RemoteIdentityProvider(
                    accessTokenProvider = { null },
                    sessionOwnerId = { OWNER_A },
                ),
            )

            val result = engine.downloadAllNotes()

            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
            assertTrue(
                arrived.none { it.first == LIST_USER || it.first == LIST_PENDING },
                "a refused identity still produced metadata traffic: $arrived",
            )
            assertEquals(0, providerCalls, "a refused capture fell back to the live session")
        }

    // ---- AMRID-7: an expired A credential never becomes B ----

    @Test
    fun `AMRID-7 a 401 for A's expired credential never retries as B`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(OWNER_A)
        val identity = identityForA()
        expiredForA = true
        moveLiveSessionToB()

        // Historical policy, preserved: the purge failure is swallowed and the sweep carries on.
        val result = service().sweepPendingDeletedAttachments(token, identity)

        assertEquals(LocalCommitResult.Applied(Unit), result, "a purge failure changed the sweep's result")
        assertEquals(
            listOf(LIST_PENDING to "Bearer $TOKEN_A", PURGE to "Bearer $TOKEN_A"),
            arrived,
            "the failed purge was retried, or retried with the live session's credentials",
        )
        assertEquals(0, providerCalls, "a failed identity-bound call fell back to the live session")
    }

    // ---- AMRID-8 / AMRID-9: grant-first across isolation, and no I/O in the gate ----

    @Test
    fun `AMRID-8 a purge authorized before isolation still carries A's identity`() =
        runTest(timeout = TIMEOUT) {
            val token = LocalCommitGate.capture(OWNER_A)
            val identity = identityForA()
            parkFunction = PURGE
            val service = service()

            val sweep = async { service.sweepPendingDeletedAttachments(token, identity) }
            requestArrived.await()             // the purge was authorized and is on the wire
            assertFalse(
                LocalCommitGate.mutex.isLocked,
                "a metadata RPC is in flight with the gate held",
            )
            LocalCommitGate.isolate { }         // the boundary lands behind the issued purge
            moveLiveSessionToB()
            requestRelease.complete(Unit)
            val result = sweep.await()

            assertEquals(
                LocalCommitResult.StaleGeneration,
                result,
                "a purge that completed across the boundary was reported as success",
            )
            assertEquals(
                listOf(LIST_PENDING to "Bearer $TOKEN_A", PURGE to "Bearer $TOKEN_A"),
                arrived,
                "the already-issued purge did not carry its operation's identity",
            )
            assertEquals(0, providerCalls, "the issued purge borrowed the live session's credential")
        }

    @Test
    fun `AMRID-9 no credential IO happens under the gate while a read is in flight`() =
        runTest(timeout = TIMEOUT) {
            val token = LocalCommitGate.capture(OWNER_A)
            val identity = identityForA()
            parkFunction = LIST_USER
            val service = service()

            val hydration = async { service.hydrateAllNotes(token, identity) }
            requestArrived.await()
            assertFalse(LocalCommitGate.mutex.isLocked, "the metadata read is in flight with the gate held")
            LocalCommitGate.isolate { }
            requestRelease.complete(Unit)

            assertEquals(
                LocalCommitResult.StaleGeneration,
                hydration.await(),
                "a listing that completed across the boundary was reported as success",
            )
        }

    // ---- AMRID-10 / AMRID-12: the structural closure ----

    @Test
    fun `AMRID-10 the metadata implementation has no live-session send path`() {
        val source = sourceOf("AttachmentRemoteMetadata.kt") + sourceOf("SupabaseAttachmentMetadata.kt")

        assertFalse(
            Regex("""rpcClient\.callRpc\(\s*functionName""").containsMatchIn(source),
            "a metadata RPC is still sent through the live-session overload",
        )
        assertFalse(
            Regex("""rpcClient\.callRpcElement\(\s*functionName""").containsMatchIn(source),
            "a metadata read is still sent through the live-session overload",
        )
        assertTrue(
            source.contains("identity: OperationRemoteIdentity"),
            "the metadata contract no longer requires an identity",
        )
        assertTrue(
            source.contains("interface AttachmentRemoteMetadata"),
            "the identity-bound metadata contract is missing",
        )
    }

    @Test
    fun `AMRID-12 every protected metadata call site passes an identity`() {
        val service = sourceOf("AttachmentSyncService.kt")

        assertTrue(
            Regex("""listUserAttachments\(\s*metadataIdentity\s*\)""").containsMatchIn(service),
            "hydration's listing no longer passes the operation's identity",
        )
        assertTrue(
            Regex("""listPendingDeletedAttachments\(\s*metadataIdentity\s*\)""").containsMatchIn(service),
            "the sweep's listing no longer passes the operation's identity",
        )
        assertTrue(
            Regex("""purgeDeleted\(\s*metadataIdentity\s*,""").containsMatchIn(service),
            "the sweep's purge no longer passes the operation's identity",
        )
        assertFalse(
            Regex("""listUserAttachments\(\s*\)|listPendingDeletedAttachments\(\s*\)|purgeDeleted\(\s*row\.attachmentId""")
                .containsMatchIn(service),
            "a metadata call site invokes the RPC without an identity",
        )
    }

    // ---- fixtures ----

    private fun identityForA() = OperationRemoteIdentity(ownerId = OWNER_A, accessToken = TOKEN_A)

    private fun moveLiveSessionToB() {
        liveOwner = OWNER_B
        liveToken = TOKEN_B
    }

    private fun client(live: () -> String?) = DesktopSupabaseRpcClient(
        supabaseUrl = "http://127.0.0.1:${server.address.port}",
        anonKey = "anon",
        accessTokenProvider = {
            providerCalls++
            live()
        },
    )

    private fun service() = AttachmentSyncService(
        // Not the Noop transport: `enabled` is false with it, and then no metadata call is made at all.
        blobTransport = RecordingBlobTransport(),
        metadata = SupabaseAttachmentMetadata(client { liveToken }),
        localStorage = NoopLocalStorage(),
        noteDao = noteDao,
        staging = staging,
        ownerIdProvider = { liveOwner },
        attachmentsEnabled = { true },
    )

    /** The engine's download, with the real metadata implementation against the local server. */
    private fun engine(
        identityProvider: RemoteIdentityProvider = RemoteIdentityProvider(
            accessTokenProvider = {
                // Read first, replace second: the capture keeps A's credential — the owner reads either
                // side of it still agree, so the capture is coherent — and the live session's bearer
                // becomes B's immediately afterwards. That is the drift: anything still resolving the
                // credential at send time goes out as B.
                captureReads++
                liveToken.also { if (replaceLiveTokenAfterCapture) liveToken = TOKEN_B }
            },
            sessionOwnerId = { liveOwner },
        ),
    ) = NoteSyncEngine(
        transport = FakeCloudNoteTransport(),
        remoteIdentityProvider = identityProvider,
        noteDao = noteDao,
        labelDao = FakeLabelDao(),
        syncStateStore = FakeNoteSyncStateStore().apply { setLastMergedUserId(OWNER_A) },
        uidProvider = { Result.success(checkNotNull(liveOwner) { "precondition: a session is live" }) },
        platform = "desktop",
        now = { NOW },
        attachmentSync = service(),
        // Production wires this to the session (see the platform DI), so the engine's tokens carry an
        // account. The constructor default is a null-UID token, which R18.1 refuses for the sweep.
        localCommitTokenProvider = LocalCommitTokenProvider {
            LocalCommitGate.captureCoherent { liveOwner }
        },
    )

    private fun seedLocalNote() {
        noteDao.notes[NOTE_ID] = Note(
            id = NOTE_ID,
            title = "note",
            content = "body",
            timestamp = 1_000L,
            color = 0,
            attachments = listOf(
                Attachment(
                    id = ATT_LOCAL,
                    noteId = NOTE_ID,
                    storagePath = "r2:owners/$OWNER_A/notes/$NOTE_ID/$ATT_LOCAL",
                    type = "image",
                    mimeType = "image/png",
                    sizeBytes = 3,
                ),
            ),
        ).toNoteEntity()
    }

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            java.io.File("src/commonMain/kotlin/com/aus/notelikeus/data/remote/$name"),
            java.io.File(
                "src/commonMain/kotlin/com/aus/notelikeus/data/attachments/$name",
            ),
            java.io.File("composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/remote/$name"),
            java.io.File("composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/attachments/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("could not read $name from ${candidates.joinToString { it.absolutePath }}")
    }

    /** Records nothing and touches nothing: these lanes are about the metadata wire, not blobs. */
    private class RecordingBlobTransport : AttachmentBlobTransport {
        override suspend fun captureContext(): AttachmentRemoteContext =
            AttachmentRemoteContext(ownerId = OWNER_A, accessToken = TOKEN_A)

        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ): AttachmentBlobUploadResult = AttachmentBlobUploadResult(
            objectKey = "owners/${context.ownerId}/notes/$noteId/$attachmentId",
            sizeBytes = bytes.size.toLong(),
            mimeType = mimeType,
        )

        override suspend fun download(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ): ByteArray = ByteArray(0)

        override suspend fun delete(context: AttachmentRemoteContext, noteId: String, attachmentId: String) {}
    }

    private class NoopLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null

        override fun readBytes(storagePath: String): ByteArray? = null

        override fun deleteIfLocal(storagePath: String) {}
    }

    /** Enough staging for the fixtures: nothing here stages or uploads bytes. */
    private class MemoryStagingStore : AttachmentStagingStore {
        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment? = null

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? = null

        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? = null

        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean = false

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) {}

        override suspend fun release(attachmentId: String, ownerId: String) {}

        override suspend fun list(ownerId: String): List<StagedAttachment> = emptyList()
    }

    private companion object {
        const val OWNER_A = "user-a"
        const val OWNER_B = "user-b"
        const val TOKEN_A = "token-a"
        const val TOKEN_B = "token-b"

        const val NOTE_ID = 42L
        const val ATT_A = "att-a"
        const val ATT_LOCAL = "att-local"

        const val LIST_USER = "list_user_attachments"
        const val LIST_PENDING = "list_pending_deleted_attachments"
        const val PURGE = "purge_deleted_note_attachment"

        /** Unused by the lanes that only assert on arrival order; kept for readable fixtures. */
        val PENDING_ROW = PendingDeletedAttachment(attachmentId = ATT_A, noteId = NOTE_ID.toString(), objectKey = "")

        const val NOW = 2_000_000_000_000L
        val TIMEOUT = 60.seconds
    }
}
