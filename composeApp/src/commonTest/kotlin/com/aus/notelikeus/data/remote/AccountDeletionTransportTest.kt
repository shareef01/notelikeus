package com.aus.notelikeus.data.remote

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDeletionTransportTest {
    @Test
    fun `token acquisition cannot redirect an explicit deletion to a different owner`() = runTest {
        var owner = "A"
        val transport = WorkerAccountDeletionTransport(
            "https://worker.example", object : SupabaseAccessTokenProvider {
                override suspend fun accessToken(): String { owner = "B"; return "B-token" }
            },
            send = { _, _ -> error("must not send") },
            sessionOwnerId = { owner },
        )
        assertFailsWith<IllegalStateException> { transport.deleteAccount("A") }
    }

    @Test
    fun `ambiguous deletion checks the captured bearer after a switch`() = runTest {
        var owner = "A"
        var checked: String? = null
        val transport = WorkerAccountDeletionTransport(
            "https://worker.example", object : SupabaseAccessTokenProvider {
                override suspend fun accessToken() = "A-token"
            },
            send = { _, _ -> owner = "B"; throw NetworkFailure("lost reply") },
            sessionOwnerId = { owner },
            checkAccountLiveWithToken = { ownerId, token ->
                assertEquals("A", ownerId)
                checked = token
                throw deletedAnswer
            },
        )
        transport.deleteAccount("A")
        assertEquals("A-token", checked)
    }
    /** A request that never got an answer, as a dropped connection or a timeout leaves it. */
    private class NetworkFailure(message: String) : Exception(message)

    private val sent = mutableListOf<Pair<String, String>>()

    private var checks = 0

    private fun transport(
        token: String? = "token",
        send: suspend (String, String) -> Unit = { url, accessToken -> sent += url to accessToken },
        checkAccountLive: (suspend () -> Unit)? = null,
    ) = WorkerAccountDeletionTransport(
        workerBaseUrl = "https://worker.example/",
        accessTokenProvider = object : SupabaseAccessTokenProvider {
            override suspend fun accessToken(): String? = token
        },
        send = send,
        checkAccountLive = checkAccountLive?.let { check -> { checks++; check() } },
    )

    private val deletedAnswer = SupabaseTransportException(
        "get_sync_generation",
        400,
        """{"code":"NL003","message":"account_deleted: this Notelikeus account was deleted."}""",
    )

    @Test
    fun `the request goes to the account route with the session token`() = runTest {
        transport().deleteAccount()

        assertEquals(listOf("https://worker.example/v1/account" to "token"), sent)
    }

    @Test
    fun `no session sends nothing`() = runTest {
        assertFailsWith<SupabaseTransportException> { transport(token = null).deleteAccount() }

        assertEquals(emptyList(), sent)
    }

    @Test
    fun `a refusal reaches the caller unchecked, so nothing local is erased`() = runTest {
        assertFailsWith<SupabaseTransportException> {
            transport(
                send = { _, _ -> throw SupabaseTransportException("attachments", 403, "Forbidden") },
                checkAccountLive = { throw deletedAnswer },
            ).deleteAccount()
        }
        assertEquals(0, checks, "a 4xx is a definite refusal and needs no check")
    }

    // AUD-010: a failure that may have come after the database accepted the request is settled by asking.
    @Test
    fun `an unclear failure on an account that turns out deleted is a deletion`() = runTest {
        transport(
            send = { _, _ -> throw NetworkFailure("connection reset") },
            checkAccountLive = { throw deletedAnswer },
        ).deleteAccount()

        assertEquals(1, checks)
    }

    @Test
    fun `an unclear failure on an account still live reports the original failure`() = runTest {
        val failure = assertFailsWith<SupabaseTransportException> {
            transport(
                send = { _, _ -> throw SupabaseTransportException("attachments", 502, "upstream") },
                checkAccountLive = { },
            ).deleteAccount()
        }
        assertEquals(502, failure.statusCode)
    }

    @Test
    fun `an outcome even the check cannot settle is reported as unknown`() = runTest {
        assertFailsWith<AccountDeletionOutcomeUnknownException> {
            transport(
                send = { _, _ -> throw NetworkFailure("timeout") },
                checkAccountLive = { throw NetworkFailure("Unable to resolve host") },
            ).deleteAccount()
        }
        assertFailsWith<AccountDeletionOutcomeUnknownException> {
            transport(send = { _, _ -> throw SupabaseTransportException("attachments", 503, "") }).deleteAccount()
        }
    }

    @Test
    fun `a build with no Worker refuses`() = runTest {
        assertFailsWith<IllegalStateException> { UnavailableAccountDeletionTransport.deleteAccount() }
    }

    @Test
    fun `the database's answer is recognised, directly or wrapped`() {
        val refusal = SupabaseTransportException(
            "fetch_full_snapshot",
            400,
            """{"code":"NL003","message":"account_deleted: this Notelikeus account was deleted."}""",
        )

        assertTrue(refusal.isAccountDeletedRefusal())
        assertTrue(IllegalStateException("sync failed", refusal).isAccountDeletedRefusal())
    }

    /** The local copy is erased on this answer, so nothing ambiguous may pass for it. */
    @Test
    fun `nothing ambiguous reads as a deleted account`() {
        val ambiguous = listOf(
            RuntimeException("Unable to resolve host"),
            SupabaseTransportException("auth", 400, """{"error_code":"refresh_token_not_found"}"""),
            SupabaseTransportException("auth", 401, "JWT expired"),
            SupabaseTransportException("apply_note_change", 400, """{"code":"NL002"}"""),
            SupabaseTransportException("fetch_full_snapshot", 503, "upstream"),
        )

        ambiguous.forEach { assertFalse(it.isAccountDeletedRefusal(), "$it") }
    }

    /**
     * AUD-009: the text NL003 or account_deleted: anywhere else in a body, such as a note title quoted
     * back in an error's details, is not the database's answer.
     */
    @Test
    fun `the answer is read from the error's code and message prefix only`() {
        val quoted = listOf(
            """{"code":"23502","message":"null value violates not-null constraint","details":"Failing row contains (1, NL003 account_deleted: my note)"}""",
            """{"code":"22023","message":"note title mentions account_deleted: and NL003"}""",
            "plain text NL003 account_deleted: from a proxy page",
        ).map { SupabaseTransportException("apply_note_change", 400, it) }

        quoted.forEach { assertFalse(it.isAccountDeletedRefusal(), it.body) }
        assertTrue(
            SupabaseTransportException("pull_changes", 400, """{"message":"account_deleted: gone"}""")
                .isAccountDeletedRefusal(),
        )
    }
}
