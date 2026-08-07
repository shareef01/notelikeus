package com.aus.notelikeus.platform

import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.withTimeoutOrNull
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
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    // WARNING: client_secret in source — use env vars for production
    private val oauthClientSecret: String,
    private val firebaseApiKey: String
) : GoogleSignInHelper {

    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)

    override suspend fun requestIdToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val codeVerifier = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)

            // Step 1: open browser and capture the auth code via loopback
            val (authCode, redirectUri) = captureAuthCode(codeChallenge)
                ?: return@withContext Result.failure(
                    IllegalStateException("Sign-in was cancelled or timed out")
                )
            println("[Notelikeus] Got auth code, exchanging for tokens...")

            // Step 2: exchange auth code for Google tokens (must use exact same redirect_uri)
            val googleTokens = exchangeCodeForTokens(authCode, codeVerifier, redirectUri)
            println("[Notelikeus] Google token response: ${googleTokens.keys}")
            val googleIdToken = googleTokens["id_token"]?.jsonPrimitive?.content
                ?: return@withContext Result.failure(
                    IllegalStateException("No id_token in Google token response. Keys: ${googleTokens.keys}")
                )

            // Step 3: exchange Google ID token for Firebase custom token
            println("[Notelikeus] Exchanging Google token for Firebase session...")
            val firebaseResponse = exchangeGoogleTokenForFirebase(googleIdToken)
            println("[Notelikeus] Firebase response: ${firebaseResponse.keys}")
            val firebaseIdToken = firebaseResponse["idToken"]?.jsonPrimitive?.content
                ?: return@withContext Result.failure(
                    IllegalStateException("No idToken in Firebase response. Keys: ${firebaseResponse.keys}")
                )

            println("[Notelikeus] Sign-in successful!")
            Result.success(firebaseIdToken)
        } catch (e: Exception) {
            println("[Notelikeus] SIGN-IN ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
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

    // ---- Loopback server to capture the OAuth redirect ----

    private suspend fun captureAuthCode(codeChallenge: String): Pair<String, String>? {
        return withTimeoutOrNull(120_000L) {
            suspendCancellableCoroutine { cont ->
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val port = server.address.port
                val redirectUri = "http://127.0.0.1:$port"
                var codeHandled = false

                server.createContext("/") { exchange ->
                    val query = exchange.requestURI.query ?: ""
                    val params = query.split("&").associate {
                        val (k, v) = it.split("=", limit = 2)
                        k to v
                    }

                    if (!codeHandled) {
                        codeHandled = true
                        val code = params["code"]
                        val error = params["error"]

                        val responseText = if (code != null) {
                            "<html><body><h3>Signed in — you can close this window.</h3></body></html>"
                        } else {
                            "<html><body><h3>Sign-in failed: ${error ?: "no authorisation code received"}</h3></body></html>"
                        }
                        val responseBytes = responseText.toByteArray()
                        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                        exchange.responseBody.use { it.write(responseBytes) }
                        exchange.close()

                        if (code != null && cont.isActive) {
                            server.stop(0)
                            cont.resume(Pair(code, redirectUri))
                        } else if (cont.isActive) {
                            server.stop(0)
                            cont.resume(null)
                        }
                    } else {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.close()
                    }
                }
                server.start()

                // Open the browser
                val authUrl = buildOAuthUrl(redirectUri, codeChallenge)
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(authUrl))
                    } else {
                        cont.resume(null)
                    }
                } catch (e: Exception) {
                    println("[Notelikeus] Failed to open browser: ${e.message}")
                    cont.resume(null)
                }
            }
        }
    }

    private fun buildOAuthUrl(redirectUri: String, codeChallenge: String): String {
        val params = mapOf(
            "client_id" to oauthClientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
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
