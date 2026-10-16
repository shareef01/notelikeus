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
import kotlin.test.assertFalse
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
        // No CAPTCHA configured: the local stack has [auth.captcha] off.
        assertFalse("gotrue_meta_security" in body())
    }

    @Test
    fun requestEmailOtpSendsTheTurnstileTokenWhereGoTrueReadsIt() = runTest {
        api().requestEmailOtp("a@example.com", captchaToken = "tok\"en")

        val security = body()["gotrue_meta_security"]!!.jsonObject
        assertEquals("tok\"en", security["captcha_token"]!!.jsonPrimitive.content)
        assertEquals(true, body()["create_user"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun theSessionManagerAsksForAFreshTokenForEveryCodeRequest() = runTest {
        var issued = 0
        val manager = SupabaseSessionManager(
            authApi = api(),
            sessionStore = SupabaseSessionStore(),
            captcha = { "token-${++issued}" },
        )

        assertTrue(manager.requestEmailCode(" a@example.com ").isSuccess)
        assertTrue(manager.requestEmailCode("a@example.com").isSuccess)

        assertEquals(
            listOf("token-1", "token-2"),
            calls.indices.map {
                body(it)["gotrue_meta_security"]!!.jsonObject["captcha_token"]!!.jsonPrimitive.content
            },
        )
        assertEquals("a@example.com", body(0)["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCancelledSecurityCheckSendsNothingAndSaysWhy() = runTest {
        val manager = SupabaseSessionManager(
            authApi = api(),
            sessionStore = SupabaseSessionStore(),
            captcha = { throw CaptchaUnavailableException("Check cancelled.") },
        )

        val failure = manager.requestEmailCode("a@example.com").exceptionOrNull()

        assertTrue(calls.isEmpty())
        assertEquals("Check cancelled.", failure?.message)
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
        assertTrue(
            "security check" in describeEmailCodeFailure(
                failure(400, """{"code":"captcha_failed","msg":"captcha protection: request disallowed"}"""),
            ),
        )
    }
}
