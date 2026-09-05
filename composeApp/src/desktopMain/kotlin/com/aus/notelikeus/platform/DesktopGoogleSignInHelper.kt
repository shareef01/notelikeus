package com.aus.notelikeus.platform

import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.aus.notelikeus.util.AppLog
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * exchange code for tokens → exchange Google token for a Supabase session.
 *
 * **Prerequisite:** a Desktop OAuth 2.0 client must be created in the
 * Google Cloud Console for project `notelikeus` with
 * `http://127.0.0.1` (any port) added as an authorised redirect URI.
 * Without this the authorisation endpoint will reject the request.
 *
 * @param oauthClientId The OAuth 2.0 client ID (desktop type).
 */
class DesktopGoogleSignInHelper(
    private val oauthClientId: String,
    private val oauthClientSecret: String,
    private val supabaseAuthApi: com.aus.notelikeus.data.remote.SupabaseAuthApi,
    private val supabaseSessionStore: com.aus.notelikeus.data.remote.SupabaseSessionStore,
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
            val nonce = generateNonce()

            // Step 1: open browser and capture the auth code via loopback
            val (authCode, redirectUri) = captureAuthCode(codeChallenge, state, hashedNonce(nonce))
                ?: return@withContext Result.failure(
                    IllegalStateException("Sign-in was cancelled or timed out")
                )

            // Step 2: exchange auth code for Google tokens (must use exact same redirect_uri)
            val googleTokens = exchangeCodeForTokens(authCode, codeVerifier, redirectUri)
            val googleIdToken = googleTokens["id_token"]?.jsonPrimitive?.content
                ?: return@withContext Result.failure(
                    IllegalStateException("Google did not return an ID token")
                )

            // Step 3: exchange the Google ID token for a Supabase session
            val session = supabaseAuthApi.signInWithGoogleIdToken(googleIdToken, nonce)
            supabaseSessionStore.save(session)
            Result.success(session.accessToken)
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
    /**
     * OIDC nonce (OpenID Connect Core §3.1.2.1). Google echoes it into the ID token's `nonce`
     * claim, and Supabase verifies it on `grant_type=id_token`.
     *
     * Sending one is why the project does not have to disable the nonce check, which would
     * otherwise let a captured ID token be replayed until it expired.
     */
    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The value Google is given, which is the **hash** of the nonce — not the nonce itself.
     *
     * GoTrue hashes whatever nonce it is handed and compares the digest against the token's
     * `nonce` claim, so the claim has to hold the digest and Supabase has to receive the plain
     * value. Sending the same plain value to both is what produced
     * `{"error_description":"Bad ID token"}`: Google echoed the plain nonce, GoTrue compared its
     * hash against it, and they never matched.
     *
     * Hex, lower case — the encoding GoTrue's comparison expects, not base64url like the PKCE
     * challenge above.
     */
    private fun hashedNonce(nonce: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ---- Loopback server to capture the OAuth redirect ----

    private suspend fun captureAuthCode(
        codeChallenge: String,
        expectedState: String,
        hashedNonce: String
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

                // The 120s withTimeoutOrNull above cancels this coroutine when the user closes the
                // browser or never finishes consent. Without this the handler's `cont.isActive`
                // guard is false, server.stop() never runs, and the loopback server keeps its port
                // bound and its threads alive for the rest of the process — still willing to accept
                // an authorization code nothing is waiting for.
                cont.invokeOnCancellation { runCatching { server.stop(0) } }

                // Open the browser
                val authUrl = buildOAuthUrl(redirectUri, codeChallenge, expectedState, hashedNonce)
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(authUrl))
                    } else {
                        // A null resume is indistinguishable from the user cancelling consent, so
                        // the reason the browser never opened only exists in the log.
                        AppLog.warn(TAG, "No AWT desktop browser support; cannot start sign-in")
                        server.stop(0)
                        cont.resume(null)
                    }
                } catch (error: Exception) {
                    AppLog.warn(TAG, "Failed to open the browser for sign-in", error)
                    server.stop(0)
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
        state: String,
        hashedNonce: String
    ): String {
        val params = mapOf(
            "client_id" to oauthClientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "nonce" to hashedNonce,
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

    private companion object {
        const val TAG = "GoogleSignIn"
    }
}
