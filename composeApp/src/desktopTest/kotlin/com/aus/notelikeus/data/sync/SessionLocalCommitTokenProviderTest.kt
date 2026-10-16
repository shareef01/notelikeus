package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.remote.CloudSessionAccount
import com.aus.notelikeus.data.remote.CloudSessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The production provider's job is *composition*, not policy: it must read the uid **inside**
 * `captureCoherent`, so the uid and the dataset generation come from one incarnation. Passing a uid
 * to `capture(uid)` instead would be two independent reads and could pair the previous account's uid
 * with the new dataset's generation.
 *
 * The retry behaviour itself is proven by [LocalCommitGateCoherentCaptureTest]; this only proves the
 * wiring from the live session through to the token.
 */
class SessionLocalCommitTokenProviderTest {

    @Test
    fun `capture composes the live session uid with the current generation`() {
        val session = FakeCloudSessionManager(userId = "uid-A")
        val provider = SessionLocalCommitTokenProvider(session)

        val token = provider.capture()

        assertEquals("uid-A", token.initiatingUid)
        assertEquals(
            LocalCommitGate.currentGeneration(),
            token.generation,
            "the token should carry the current dataset generation",
        )
        assertTrue(
            session.accountQueries > 0,
            "the provider never asked the session manager for the current account",
        )
    }

    @Test
    fun `capture reports a signed-out session as a null uid`() {
        val provider = SessionLocalCommitTokenProvider(FakeCloudSessionManager(userId = null))

        assertNull(provider.capture().initiatingUid)
    }

    /** Hand-written rather than mocked: the point is to assert this exact call path. */
    private class FakeCloudSessionManager(private val userId: String?) : CloudSessionManager {
        var accountQueries = 0
            private set

        override fun getCurrentAccount(): CloudSessionAccount {
            accountQueries++
            return CloudSessionAccount(
                userId = userId,
                email = null,
                isSignedIn = false,
                isAnonymous = userId == null,
            )
        }

        override suspend fun requestEmailCode(email: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("not used in this test"))

        override suspend fun signInWithEmailCode(email: String, code: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("not used in this test"))

        override suspend fun signInWithEmailPassword(
            email: String,
            password: String,
            createAccount: Boolean,
        ): Result<Unit> = Result.failure(UnsupportedOperationException("not used in this test"))

        override suspend fun signOut(): Result<Unit> = Result.success(Unit)

        override suspend fun ensureSignedIn(): Result<String> =
            Result.failure(UnsupportedOperationException("not used in this test"))

        override fun diagnose(error: Throwable): String = error.message.orEmpty()
    }
}
