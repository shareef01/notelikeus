package com.aus.notelikeus.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop's Turnstile token (D30). There is no WebView on desktop, so the system browser opens the
 * web app's challenge page with `?port=<n>&state=<s>`, and the page sends the token to
 * `http://127.0.0.1:<n>/?state=<s>&token=<t>`.
 *
 * The listener binds to 127.0.0.1 only, on a port the OS picks. The first request to `/` with a
 * matching state yields the token and uses the listener up. A request with a wrong or missing state
 * gets a 400 and the listener keeps waiting, so a stray visit to the port does not spoil the check,
 * but after [MAX_BAD_ATTEMPTS] of them the check fails. It gives up after [timeout]. A page that
 * is not the one this app opened cannot know the state, so it cannot hand the app a token of its
 * choosing.
 */
class LoopbackCaptchaTokenProvider(
    private val pageUrl: String,
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val timeout: Duration = 5.minutes,
) : CaptchaTokenProvider {

    override suspend fun obtainToken(): String? {
        if (pageUrl.isBlank()) return null
        val state = newCaptchaState()
        val result = CompletableDeferred<String>()
        val answered = AtomicBoolean(false)
        val badAttempts = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        server.createContext("/") { exchange ->
            exchange.use { handle(it, state, answered, badAttempts, result) }
        }
        server.start()
        try {
            val port = server.address.port
            val challenge = URI(captchaChallengeUrl(pageUrl, "port=$port&state=$state"))
            withContext(Dispatchers.IO) {
                runCatching { openBrowser(challenge) }.getOrElse {
                    throw CaptchaUnavailableException(
                        "Couldn't open your browser for the security check. Try again.",
                        it,
                    )
                }
            }
            return withTimeoutOrNull(timeout) { result.await() }
                ?: throw CaptchaUnavailableException(
                    "The security check in your browser timed out. Try again.",
                )
        } finally {
            server.stop(0)
        }
    }

    private fun handle(
        exchange: HttpExchange,
        state: String,
        answered: AtomicBoolean,
        badAttempts: AtomicInteger,
        result: CompletableDeferred<String>,
    ) {
        // The browser may also ask for /favicon.ico; only the page's redirect to / counts.
        if (exchange.requestURI.path != "/" || exchange.requestMethod != "GET") {
            respond(exchange, 404, "Not found.")
            return
        }
        if (answered.get()) {
            respond(exchange, 409, USED_MESSAGE)
            return
        }
        val token = captchaTokenFromReturnQuery(exchange.requestURI.rawQuery, state)
        if (token == null) {
            respond(
                exchange,
                400,
                "This security check is not the one Notelikeus started. Return to the app and try again.",
            )
            if (badAttempts.incrementAndGet() >= MAX_BAD_ATTEMPTS && answered.compareAndSet(false, true)) {
                result.completeExceptionally(
                    CaptchaUnavailableException("The security check didn't go through. Try again."),
                )
            }
        } else if (answered.compareAndSet(false, true)) {
            respond(exchange, 200, "Done. You can close this tab and return to Notelikeus.")
            result.complete(token)
        } else {
            respond(exchange, 409, USED_MESSAGE)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val body = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Notelikeus</title></head>
            <body style="font-family:system-ui,sans-serif;text-align:center;padding:3rem">
            <p>$message</p></body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.apply {
            add("Content-Type", "text/html; charset=utf-8")
            add("Cache-Control", "no-store")
            add("Referrer-Policy", "no-referrer")
            add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private companion object {
        /** Requests with a wrong or missing state the listener tolerates before failing the check. */
        const val MAX_BAD_ATTEMPTS = 5
        const val USED_MESSAGE = "This security check has already been used. Return to Notelikeus."
    }
}
