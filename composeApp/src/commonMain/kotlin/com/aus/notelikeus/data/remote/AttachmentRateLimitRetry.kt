package com.aus.notelikeus.data.remote

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long to wait before each retry of a request the attachments Worker refused with 429. The Worker
 * counts requests per user over a 60-second window, so the waits add up to one window.
 */
internal val ATTACHMENT_RATE_LIMIT_WAITS: List<Duration> = listOf(15.seconds, 45.seconds)

/**
 * Runs [request], and when the Worker answers 429 (too many requests), waits and runs it again, once
 * per entry of [waits]. Any other failure, and a 429 after the last wait, is thrown as before.
 *
 * Without this a new device hydrating a few hundred images failed every load past the limit, and those
 * images stayed missing until a later sync happened to retry them.
 */
internal suspend fun <T> retryOnAttachmentRateLimit(
    waits: List<Duration> = ATTACHMENT_RATE_LIMIT_WAITS,
    sleep: suspend (Duration) -> Unit = { delay(it) },
    request: suspend () -> T,
): T {
    for (wait in waits) {
        try {
            return request()
        } catch (e: SupabaseTransportException) {
            if (e.statusCode != HTTP_TOO_MANY_REQUESTS) throw e
        }
        sleep(wait)
    }
    return request()
}

private const val HTTP_TOO_MANY_REQUESTS = 429
