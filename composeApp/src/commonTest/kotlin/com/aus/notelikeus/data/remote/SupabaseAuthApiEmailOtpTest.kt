package com.aus.notelikeus.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The request shapes GoTrue expects for email one-time-code sign-in. */
class SupabaseAuthApiEmailOtpTest {

    private val calls = mutableListOf<Pair<String, String>>()

    private fun api(response: String = "{}") = SupabaseAuthApi(
        supabaseUrl = "https://example.supabase.co",
        anonKey = "anon",
        post = { path, body ->
            calls += path to body
            response
        },
    )

    private fun body(index: Int = 0): JsonObject = Json.parseToJsonElement(calls[index].second).jsonObject

    @Test
    fun requestEmailOtpPostsToOtpAndCreatesTheUserOnFirstUse() = runTest {
        api().requestEmailOtp("a\"b@example.com")

        assertEquals("/auth/v1/otp", calls.single().first)
        assertEquals("a\"b@example.com", body()["email"]!!.jsonPrimitive.content)
        assertEquals(true, body()["create_user"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun verifyEmailOtpPostsTheEmailTypeAndParsesTheSession() = runTest {
        val session = api(
            """{"access_token":"at","refresh_token":"rt","expires_in":3600,
               "user":{"id":"11111111-1111-4111-8111-111111111111","email":"a@example.com"}}""",
        ).verifyEmailOtp("a@example.com", "123456")

        assertEquals("/auth/v1/verify", calls.single().first)
        assertEquals("email", body()["type"]!!.jsonPrimitive.content)
        assertEquals("a@example.com", body()["email"]!!.jsonPrimitive.content)
        assertEquals("123456", body()["token"]!!.jsonPrimitive.content)
        assertEquals("at", session.accessToken)
        assertEquals("rt", session.refreshToken)
        assertEquals("11111111-1111-4111-8111-111111111111", session.userId)
        assertEquals("a@example.com", session.email)
    }

    @Test
    fun anUnconfiguredBackendFailsBeforeAnyRequest() = runTest {
        val unconfigured = SupabaseAuthApi("", "", post = { path, body -> calls += path to body; "{}" })

        assertFailsWith<IllegalStateException> { unconfigured.requestEmailOtp("a@example.com") }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun gotrueErrorsBecomeMessagesAUserCanActOn() {
        fun failure(status: Int, body: String) = SupabaseTransportException("auth", status, body)

        assertTrue(
            "wrong or has expired" in describeEmailCodeFailure(
                failure(403, """{"code":"otp_expired","msg":"Token has expired or is invalid"}"""),
            ),
        )
        assertTrue(
            "Too many" in describeEmailCodeFailure(
                failure(429, """{"code":"over_email_send_rate_limit"}"""),
            ),
        )
        assertTrue(
            "valid email" in describeEmailCodeFailure(
                failure(400, """{"code":"email_address_invalid"}"""),
            ),
        )
        assertTrue("connection" in describeEmailCodeFailure(java.io.IOException("reset")))
    }
}
