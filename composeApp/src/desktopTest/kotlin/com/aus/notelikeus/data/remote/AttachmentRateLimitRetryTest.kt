package com.aus.notelikeus.data.remote

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A request the attachments Worker throttles (429) is retried after a wait instead of failing. */
class AttachmentRateLimitRetryTest {

    private fun throttled() = SupabaseTransportException("attachments", 429, "rate limited")

    @Test
    fun `a throttled request is retried after each wait and then succeeds`() = runTest {
        val waited = mutableListOf<Duration>()
        var calls = 0
        val result = retryOnAttachmentRateLimit(sleep = { waited += it }) {
            calls++
            if (calls < 3) throw throttled()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(15.seconds, 45.seconds), waited)
    }

    @Test
    fun `a request still throttled after the last wait fails with the 429`() = runTest {
        var calls = 0
        val error = assertFailsWith<SupabaseTransportException> {
            retryOnAttachmentRateLimit(sleep = {}) {
                calls++
                throw throttled()
            }
        }
        assertEquals(429, error.statusCode)
        assertEquals(3, calls)
    }

    @Test
    fun `any other failure is thrown at once without waiting`() = runTest {
        val waited = mutableListOf<Duration>()
        var calls = 0
        val error = assertFailsWith<SupabaseTransportException> {
            retryOnAttachmentRateLimit(sleep = { waited += it }) {
                calls++
                throw SupabaseTransportException("attachments", 403, "note not committed")
            }
        }
        assertEquals(403, error.statusCode)
        assertEquals(1, calls)
        assertEquals(emptyList(), waited)
    }
}
