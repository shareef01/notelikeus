package com.aus.notelikeus.platform

import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume

/**
 * Google sign-in for desktop via OAuth 2.0 loopback (RFC 8252).
 *
 * Flow: open system browser → user authorises → redirect to localhost →
 * exchange code for tokens → exchange Google token for Firebase session.
 *
 * **Prerequisite:** a Desktop OAuth 2.0 client must be created in the
 * Google Cloud Console for project `notelikeus` with
 * `http://127.0.0.1` (any port) added as an authorised redirect URI.
 * Without this the authorisation endpoint will reject the request.
 *
 * @param oauthClientId The OAuth 2.0 client ID (desktop type).
 * @param firebaseApiKey Firebase web API key for the `notelikeus` project.
 */
class DesktopGoogleSignInHelper(
    private val oauthClientId: String,
    private val oauthClientSecret: String,
    private val firebaseApiKey: String,
    private val tokenStore: DesktopTokenStore
) : GoogleSignInHelper {

    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)

    override suspend fun requestIdToken(): Result<String> = withContext(Dispatchers.IO) {
        // Checked before the browser opens. Without it, the user picks an account, grants
        // consent, and only then gets Google's "client_secret is missing" 400 — a failure that
        // reads like the app is broken rather than unconfigured.
        if (oauthClientSecret.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Desktop sign-in isn't configured on this build. Add " +
                        "notelikeus.oauthClientSecret to local.properties, or set " +
                        "NOTELIKEUS_OAUTH_CLIENT_SECRET, then restart."
                )
            )
        }
        try {
            val codeVerifier = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)
            val state = generateState()

            // Step 1: open browser and capture the auth code via loopback
            val (authCode, redirectUri) = captureAuthCode(codeChallenge, state)
                ?: return@withContext Result.failure(
                    IllegalStateException("Sign-in was cancelled or timed out")
                )

            // Step 2: exchange auth code for Google tokens (must use exact same redirect_uri)
            val googleTokens = exchangeCodeForTokens(authCode, codeVerifier, redirectUri)
            val googleIdToken = googleTokens["id_token"]?.jsonPrimitive?.content
                ?: return@withContext Result.failure(
                    IllegalStateException("Google did not return an ID token")
                )

            // Step 3: exchange the Google ID token for a Firebase session
            val firebaseResponse = exchangeGoogleTokenForFirebase(googleIdToken)
            val firebaseIdToken = firebaseResponse["idToken"]?.jsonPrimitive?.content
                ?: return@withContext Result.failure(
                    IllegalStateException("Firebase did not return an ID token")
                )

            // The refresh token is the durable half of the session — without it the sign-in
            // silently stops working an hour from now. Persist both together.
            tokenStore.save(
                idToken = firebaseIdToken,
                refreshToken = firebaseResponse["refreshToken"]?.jsonPrimitive?.content
            )
            Result.success(firebaseIdToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- PKCE (RFC 7636) ----

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(96)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * CSRF token for the loopback redirect (RFC 6749 §10.12).
     *
     * The loopback server answers any process on this machine, so without a `state` to match,
     * a local attacker could race the browser and hand us *their* authorization code — binding
     * the user's app to the attacker's account. PKCE does not cover this: it prevents an
     * intercepted code from being redeemed, not an injected one from being accepted.
     */
    private fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ---- Loopback server to capture the OAuth redirect ----

    private suspend fun captureAuthCode(
        codeChallenge: String,
        expectedState: String
    ): Pair<String, String>? {
        return withTimeoutOrNull(120_000L) {
            suspendCancellableCoroutine { cont ->
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val port = server.address.port
                val redirectUri = "http://127.0.0.1:$port"
                var codeHandled = false

                server.createContext("/") { exchange ->
                    val params = parseQuery(exchange.requestURI.query)

                    if (!codeHandled) {
                        codeHandled = true
                        val code = params["code"]
                        val error = params["error"]
                        val stateMatches = params["state"] == expectedState

                        val message = when {
                            !stateMatches -> "Sign-in failed: the response did not match this request."
                            code != null -> "Signed in — you can close this window."
                            // `error` comes from the browser's query string; escape it so a
                            // same-machine attacker cannot inject HTML/script into this page.
                            else -> "Sign-in failed: ${escapeHtml(error ?: "no authorisation code received")}"
                        }
                        val responseBytes =
                            "<html><body><h3>$message</h3></body></html>".toByteArray()
                        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                        exchange.responseBody.use { it.write(responseBytes) }
                        exchange.close()

                        if (cont.isActive) {
                            server.stop(0)
                            cont.resume(
                                if (code != null && stateMatches) Pair(code, redirectUri) else null
                            )
                        }
                    } else {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.close()
                    }
                }
                server.start()

                // Open the browser
                val authUrl = buildOAuthUrl(redirectUri, codeChallenge, expectedState)
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(authUrl))
                    } else {
                        cont.resume(null)
                    }
                } catch (_: Exception) {
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * Percent-decodes the callback query.
     *
     * Both halves matter: a parameter without `=` used to blow up the handler mid-request, and
     * the authorization code arrives percent-encoded (`4%2F0Ae…`), so handing the raw value to
     * [URLEncoder] at the token endpoint double-encoded it.
     */
    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val separator = pair.indexOf('=')
            if (separator < 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, separator), "UTF-8")
            val value = URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            key to value
        }.toMap()
    }

    /**
     * Escapes a string for safe interpolation into the loopback result page's HTML.
     *
     * The `error` query parameter arrives from the browser and is attacker-influenced, so it
     * must not be able to inject markup or script into the page we serve back.
     */
    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun buildOAuthUrl(
        redirectUri: String,
        codeChallenge: String,
        state: String
    ): String {
        val params = mapOf(
            "client_id" to oauthClientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent"
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "https://accounts.google.com/o/oauth2/v2/auth?$query"
    }

    // ---- Token exchange (Google) ----

    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String, redirectUri: String): JsonObject {
        val body = mapOf(
            "code" to code,
            "client_id" to oauthClientId,
            "client_secret" to oauthClientSecret,
            "code_verifier" to codeVerifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to redirectUri // the token endpoint doesn't validate port
        ).entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("Token exchange failed: ${response.statusCode()} ${response.body()}")
        }
        return json.decodeFromString<JsonObject>(response.body())
    }

    // ---- Token exchange (Firebase) ----

    private suspend fun exchangeGoogleTokenForFirebase(googleIdToken: String): JsonObject {
        val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
            "postBody" to JsonPrimitive("id_token=$googleIdToken&providerId=google.com"),
            "requestUri" to JsonPrimitive("http://127.0.0.1"),
            "returnIdpCredential" to JsonPrimitive("true"),
            "returnSecureToken" to JsonPrimitive("true")
        )))

        val request = HttpRequest.newBuilder()
            .uri(URI("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$firebaseApiKey"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("Firebase token exchange failed: ${response.statusCode()} ${response.body()}")
        }
        return json.decodeFromString<JsonObject>(response.body())
    }
}
