package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.R2AttachmentBlobTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The legacy `/v1` protocol after R19 — what it still does, and what it cannot be made to do.
 *
 * R19.2 gave new clients the deferred protocol: uploads land provisional and become visible only when
 * the note commit names them. Legacy clients keep the old route, which commits at upload:
 *
 * * R2LEGACY-1 (backend, `notelikeus_attachment_commitment.test.sql`) pins the residual: a `/v1` upload
 *   is visible before any commit, and a replacement note 42 commit leaves it visible. That is accepted
 *   compatibility debt, not a property the promotion rule may "fix" — the 14-argument note commit names
 *   no attachment id, so promoting provisional rows on a note commit would adopt exactly these uploads
 *   into replacement notes, which is the collision R19 exists to prevent.
 * * R2LEGACY-2 — a replacement commit refusing the old upload — is therefore **not implemented**: an
 *   upload and a later note commit share only the owner uid and the note id (§4), and a timestamp is not
 *   a correlation (§5). No test claims otherwise.
 * * R2LEGACY-5 — retiring `/v1` behind a cutoff — is **not implemented** either: no request in the
 *   protocol carries an app/protocol version or capability (the client sends the bearer and the content
 *   type, nothing else), so the server has no way to tell a rollout-ready fleet from one that would break.
 *
 * What this file does pin is the part legacy clients must keep: reads and deletes are protocol-independent
 * and stay on `/v1`, so historical committed attachments remain reachable by a current client.
 */
class AttachmentLegacyProtocolIsolationTest {

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Pair<String, String>>()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += exchange.requestMethod to path
            // A GET answers bytes; a PUT answers the metadata document the upload path parses.
            val body = when (exchange.requestMethod) {
                "GET" -> byteArrayOf(1, 2, 3)
                "PUT" -> """{"objectKey":"owners/$OWNER/notes/42/att-a","mimeType":"image/png","sizeBytes":3}"""
                    .toByteArray()
                else -> ByteArray(0)
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
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

    /** R2LEGACY-3/§14: historical attachments stay readable and deletable. */
    @Test
    fun `R2LEGACY-3 reads and deletes keep the legacy route`() = runTest(timeout = TIMEOUT) {
        val transport = transport()

        transport.download(context(), "42", "att-a")
        transport.delete(context(), "42", "att-a")

        assertEquals(
            listOf("GET" to "/v1/attachments/42/att-a", "DELETE" to "/v1/attachments/42/att-a"),
            requests,
            "the read/delete route changed; historical committed attachments would become unreachable",
        )
    }

    /** R2LEGACY-4: the deferred protocol is unaffected by the compatibility surface. */
    @Test
    fun `R2LEGACY-4 uploads use the deferred route while reads use the legacy one`() = runTest(timeout = TIMEOUT) {
        val transport = transport()

        transport.upload(context(), "42", "att-a", byteArrayOf(1, 2, 3), "image/png")
        transport.download(context(), "42", "att-a")

        assertEquals(
            listOf("PUT" to "/v2/attachments/42/att-a", "GET" to "/v1/attachments/42/att-a"),
            requests,
            "the client's protocol split drifted: writes must be deferred, reads may stay legacy",
        )
        assertTrue(
            requests.none { it.first == "PUT" && it.second.startsWith("/v1/") },
            "a write reached the legacy route, which commits attachment rows at upload",
        )
    }

    private companion object {
        const val OWNER = "11111111-1111-1111-1111-111111111111"
        const val TOKEN = "token-a"
        val TIMEOUT = 60.seconds
    }
}
