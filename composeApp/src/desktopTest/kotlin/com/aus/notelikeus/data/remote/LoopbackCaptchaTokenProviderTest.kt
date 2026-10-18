package com.aus.notelikeus.data.remote

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Desktop's one-shot loopback listener for the Turnstile token. Real sockets: the "browser" here
 * is an HTTP client that does what `web/captcha.html` does after the check passes.
 */
class LoopbackCaptchaTokenProviderTest {

    private val http = HttpClient.newHttpClient()
    private val page = "https://notes.example/captcha.html"

    private fun query(uri: URI): Map<String, String> =
        uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun get(url: String): Int =
        http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding())
            .statusCode()

    @Test
    fun theTokenComesBackFromThePageThisAppOpened() = runBlocking<Unit> {
        val statuses = mutableListOf<Int>()
        var opened: URI? = null
        var port = 0
        val token = LoopbackCaptchaTokenProvider(page, openBrowser = { uri ->
            opened = uri
            val params = query(uri)
            port = params.getValue("port").toInt()
            // The browser's own request for an icon does not use up the listener.
            statuses += get("http://127.0.0.1:$port/favicon.ico")
            statuses += get("http://127.0.0.1:$port/?state=${params.getValue("state")}&token=tok%2Ben")
            statuses += get("http://127.0.0.1:$port/?state=${params.getValue("state")}&token=second")
        }).obtainToken()

        assertEquals("tok+en", token)
        assertEquals(listOf(404, 200, 409), statuses)
        assertTrue(opened.toString().startsWith("$page?port="), opened.toString())
        assertTrue(port in 1024..65535, "port $port")
        // Stopped once the token is in.
        assertFailsWith<IOException> { get("http://127.0.0.1:$port/") }
    }

    @Test
    fun aStrayRequestDoesNotSpoilTheCheck() = runBlocking<Unit> {
        val statuses = mutableListOf<Int>()
        val token = LoopbackCaptchaTokenProvider(page, openBrowser = { uri ->
            val params = query(uri)
            val port = params.getValue("port")
            statuses += get("http://127.0.0.1:$port/")
            statuses += get("http://127.0.0.1:$port/?state=guessed-state-0000&token=forged")
            statuses += get("http://127.0.0.1:$port/?state=${params.getValue("state")}&token=real")
        }).obtainToken()

        assertEquals("real", token)
        assertEquals(listOf(400, 400, 200), statuses)
    }

    @Test
    fun repeatedRequestsWithAnotherStateFailTheCheck() = runBlocking<Unit> {
        val statuses = mutableListOf<Int>()
        val failure = assertFailsWith<CaptchaUnavailableException> {
            LoopbackCaptchaTokenProvider(page, openBrowser = { uri ->
                val params = query(uri)
                val port = params.getValue("port")
                repeat(5) { statuses += get("http://127.0.0.1:$port/?state=guessed-state-000$it&token=t") }
                // Too late: the check has already failed.
                statuses += get("http://127.0.0.1:$port/?state=${params.getValue("state")}&token=real")
            }).obtainToken()
        }
        assertEquals(listOf(400, 400, 400, 400, 400, 409), statuses)
        assertTrue("security check" in failure.message.orEmpty())
    }

    @Test
    fun itGivesUpWhenTheBrowserNeverReturns() = runBlocking<Unit> {
        val failure = assertFailsWith<CaptchaUnavailableException> {
            LoopbackCaptchaTokenProvider(page, openBrowser = {}, timeout = 200.milliseconds).obtainToken()
        }
        assertTrue("timed out" in failure.message.orEmpty())
    }

    @Test
    fun aBrowserThatCannotOpenSaysSo() = runBlocking<Unit> {
        val failure = assertFailsWith<CaptchaUnavailableException> {
            LoopbackCaptchaTokenProvider(page, openBrowser = { throw UnsupportedOperationException() })
                .obtainToken()
        }
        assertTrue("browser" in failure.message.orEmpty())
    }

    @Test
    fun noPageUrlMeansNoCaptchaAndNoBrowser() = runBlocking<Unit> {
        assertNull(LoopbackCaptchaTokenProvider("", openBrowser = { fail("opened $it") }).obtainToken())
    }
}
