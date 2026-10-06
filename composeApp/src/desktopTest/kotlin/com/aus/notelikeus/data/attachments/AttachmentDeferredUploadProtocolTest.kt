package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.R2AttachmentBlobTransport
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The deferred upload protocol, as the client actually speaks it — R19.2.
 *
 * A new-client upload must reach the versioned route and must **not** fall back to the legacy one: the
 * legacy route commits the row at upload, which is the orphan a stale note continuation leaves behind.
 * These lanes drive the real `R2AttachmentBlobTransport` against a local HTTP server and assert the
 * request that arrived, plus the note RPC's attachment-id argument.
 */
class AttachmentDeferredUploadProtocolTest {

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Triple<String, String, String>>()
    private var status = 200

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val body = exchange.requestBody.readBytes()
            requests += Triple(exchange.requestMethod, path, auth)
            val payload = """{"objectKey":"owners/$OWNER/notes/42/att-a","mimeType":"image/png","sizeBytes":3}"""
                .toByteArray()
            val bytes = if (status == 200) payload else ByteArray(0)
            exchange.sendResponseHeaders(status, if (status == 200) bytes.size.toLong() else -1)
            if (status == 200) {
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.close()
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun transport() = R2AttachmentBlobTransport(
        workerBaseUrl = "http://127.0.0.1:${server.address.port}",
        accessTokenProvider = { TOKEN },
        ownerIdProvider = { OWNER },
    )

    private fun context() = AttachmentRemoteContext(ownerId = OWNER, accessToken = TOKEN)

    /** TEST-R19-2-A: a new-client upload uses the deferred route. */
    @Test
    fun `R19-2-A an upload uses the deferred route, not the legacy one`() = runTest(timeout = TIMEOUT) {
        val result = transport().upload(context(), "42", "att-a", byteArrayOf(1, 2, 3), "image/png")

        assertEquals(
            listOf("PUT /v2/attachments/42/att-a with Bearer $TOKEN"),
            requests.map { "${it.first} ${it.second} with ${it.third}" },
            "the upload did not use the deferred route",
        )
        assertTrue(result.objectKey.startsWith("owners/$OWNER/notes/42/"), "the object key drifted")
    }

    /** TEST-R19-2-B: an unsupported deferred route fails, and never retries on the legacy one. */
    @Test
    fun `R19-2-B an unsupported deferred route fails without a legacy fallback`() = runTest(timeout = TIMEOUT) {
        status = 404

        val failure = runCatching {
            transport().upload(context(), "42", "att-a", byteArrayOf(1, 2, 3), "image/png")
        }

        assertTrue(failure.isFailure, "an unsupported protocol was accepted: ${failure.getOrNull()}")
        assertEquals(
            listOf("PUT to /v2/attachments/42/att-a"),
            requests.map { "PUT to ${it.second}" },
            "the upload fell back to another route",
        )
        assertTrue(
            requests.none { it.second.startsWith("/v1/") },
            "the client retried the legacy route, which would commit the row and recreate the orphan",
        )
    }

    /** TEST-R19-2-C: the note RPC names exactly the remote attachments the note version references. */
    @Test
    fun `R19-2-C the note commit names exactly the remote attachment ids`() = runTest(timeout = TIMEOUT) {
        val rpc = RecordingRpc()
        val note = Note(
            id = 42L,
            title = "note",
            content = "body",
            timestamp = 1_000L,
            color = 0,
            attachments = listOf(
                attachment("att-remote", "$ATTACHMENT_R2_PREFIX owners/$OWNER/notes/42/att-remote"),
                attachment("att-pending", pendingStoragePath("att-pending")),
                attachment("att-file", fileStoragePath("/tmp/att-file.jpg")),
            ),
        )

        SupabaseNoteTransport(rpc).putNotes(OperationRemoteIdentity(OWNER, TOKEN), listOf(note))

        val args = rpc.lastBody ?: fail("the note RPC never ran")
        assertEquals(
            """["att-remote"]""",
            args["p_attachment_ids"].toString(),
            "the note commit named attachments that have no remote row, or missed the one that does",
        )
    }

    private fun attachment(id: String, storagePath: String) = Attachment(
        id = id,
        noteId = 42L,
        storagePath = storagePath,
        type = "image",
        mimeType = "image/png",
        sizeBytes = 3,
    )

    private class RecordingRpc : SupabaseRpcClient {
        var lastBody: JsonObject? = null

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
            JsonObject(emptyMap())

        override suspend fun callRpc(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonObject {
            lastBody = body
            return JsonObject(
                mapOf(
                    "status" to kotlinx.serialization.json.JsonPrimitive("applied"),
                    "revision" to kotlinx.serialization.json.JsonPrimitive(1),
                    "server_updated_at" to kotlinx.serialization.json.JsonPrimitive(1L),
                ),
            )
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            JsonArray(emptyList())
    }

    private companion object {
        const val OWNER = "11111111-1111-1111-1111-111111111111"
        const val TOKEN = "token-a"
        val TIMEOUT = 60.seconds
    }
}
