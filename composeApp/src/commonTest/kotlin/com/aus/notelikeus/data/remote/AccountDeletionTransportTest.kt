package com.aus.notelikeus.data.remote

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDeletionTransportTest {
    private val sent = mutableListOf<Pair<String, String>>()

    private fun transport(
        token: String? = "token",
        send: suspend (String, String) -> Unit = { url, accessToken -> sent += url to accessToken },
    ) = WorkerAccountDeletionTransport(
        workerBaseUrl = "https://worker.example/",
        accessTokenProvider = object : SupabaseAccessTokenProvider {
            override suspend fun accessToken(): String? = token
        },
        send = send,
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
    fun `a refusal reaches the caller, so nothing local is erased`() = runTest {
        assertFailsWith<SupabaseTransportException> {
            transport(send = { _, _ -> throw SupabaseTransportException("attachments", 503, "") })
                .deleteAccount()
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
}
