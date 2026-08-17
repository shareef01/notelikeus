package com.aus.notelikeus.platform

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the session bookkeeping that does not depend on DPAPI or the network: JWT claim
 * decoding (uid/email/expiry), the still-valid fast path of validIdToken(), the no-session
 * path, and clear().
 *
 * Deliberately NOT covered here: the DPAPI persistence round-trip and the legacy-blob
 * migration (need Windows Crypt32, while desktop CI runs on Linux), and the refresh flow
 * (the token endpoint URL is hardcoded, so it cannot be pointed at a test double without a
 * proxy). Those paths stay covered by manual Windows testing.
 */
class DesktopTokenStoreTest {

    private lateinit var dataDir: File
    private lateinit var store: DesktopTokenStore

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("notelikeus-session-test").toFile()
        store = DesktopTokenStore(dataDir, firebaseApiKey = "test-key")
    }

    @AfterTest
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    /** Minimal unsigned JWT: base64url(header).base64url(payload).signature */
    private fun jwt(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun `fresh store has no session`() {
        assertFalse(store.hasSession())
        assertNull(store.uid())
        assertNull(store.email())
        assertNull(store.idToken())
    }

    @Test
    fun `save extracts uid email and expiry from the token claims`() {
        val exp = (System.currentTimeMillis() / 1000) + 3600
        val token = jwt("""{"sub":"user-1","email":"a@example.com","exp":$exp}""")
        store.save(token, refreshToken = "refresh-1")

        assertTrue(store.hasSession())
        assertEquals("user-1", store.uid())
        assertEquals("a@example.com", store.email())
        assertEquals(token, store.idToken())
    }

    @Test
    fun `validIdToken returns the cached token while it is fresh`() {
        val exp = (System.currentTimeMillis() / 1000) + 3600
        val token = jwt("""{"sub":"user-1","email":null,"exp":$exp}""")
        store.save(token, refreshToken = "refresh-1")

        // Must not hit the network: the token is good for an hour, well past the 5-minute skew.
        assertEquals(token, kotlinx.coroutines.runBlocking { store.validIdToken() })
    }

    @Test
    fun `validIdToken is null without a session`() {
        assertNull(kotlinx.coroutines.runBlocking { store.validIdToken() })
    }

    @Test
    fun `user_id claim is honoured when sub is absent`() {
        val exp = (System.currentTimeMillis() / 1000) + 3600
        val token = jwt("""{"user_id":"fallback-uid","exp":$exp}""")
        store.save(token, refreshToken = null)
        assertEquals("fallback-uid", store.uid())
    }

    @Test
    fun `clear drops the whole session`() {
        val exp = (System.currentTimeMillis() / 1000) + 3600
        store.save(jwt("""{"sub":"user-1","exp":$exp}"""), refreshToken = "refresh-1")
        assertTrue(store.hasSession())

        store.clear()
        assertFalse(store.hasSession())
        assertNull(store.uid())
        assertNull(store.idToken())
    }
}
