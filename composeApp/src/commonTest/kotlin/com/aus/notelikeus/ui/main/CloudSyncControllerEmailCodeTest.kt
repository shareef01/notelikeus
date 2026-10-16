package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.repository.SyncManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two-step email sign-in: request a code, then sign in with it. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerEmailCodeTest {

    private class FakeSyncManager : SyncManager {
        override val syncStatus = MutableStateFlow(CloudSyncStatus.Unknown)
        override val cloudAccount = MutableStateFlow(CloudAccount())
        override val pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)

        var requestResult: Result<Unit> = Result.success(Unit)
        var verifyResult: Result<Unit> = Result.success(Unit)
        val requested = mutableListOf<String>()
        val verified = mutableListOf<Pair<String, String>>()
        var downloads = 0

        override suspend fun requestEmailCode(email: String): Result<Unit> {
            requested += email
            return requestResult
        }

        override suspend fun signInWithEmailCode(email: String, code: String): Result<Unit> {
            verified += email to code
            return verifyResult
        }

        override suspend fun signInWithEmail(email: String, password: String, create: Boolean) = Result.success(Unit)
        override suspend fun signOut(deleteCloudData: Boolean) = Result.success(Unit)
        override suspend fun syncNotes() = Unit
        override suspend fun downloadNotes() {
            downloads++
        }
        override fun clearPendingEvent() = Unit
    }

    private val sync = FakeSyncManager()
    private val state = MutableStateFlow(MainState())

    private fun TestScope.controller() = CloudSyncController(state, backgroundScope, sync).also {
        it.observe()
        runCurrent()
    }

    @Test
    fun `a sent code moves the gate to the code step for the trimmed address`() = runTest {
        val controller = controller()

        controller.requestEmailCode("  a@example.com ")
        runCurrent()

        assertEquals(listOf("a@example.com"), sync.requested)
        assertEquals("a@example.com", state.value.emailCodeSentTo)
        assertFalse(state.value.isSigningIn)
        assertNull(state.value.pendingCloudSyncEvent)
    }

    @Test
    fun `a code that could not be sent stays on the email step and reports why`() = runTest {
        val controller = controller()
        sync.requestResult = Result.failure(IllegalStateException("Too many attempts."))

        controller.requestEmailCode("a@example.com")
        runCurrent()

        assertNull(state.value.emailCodeSentTo)
        assertFalse(state.value.isSigningIn)
        assertEquals(CloudSyncEvent.Failure("Too many attempts."), state.value.pendingCloudSyncEvent)
    }

    @Test
    fun `a correct code signs in, pulls notes and leaves the code step`() = runTest {
        val controller = controller()
        state.value = state.value.copy(isSignInRequested = true)
        controller.requestEmailCode("a@example.com")
        runCurrent()

        controller.signInWithEmailCode("a@example.com", " 123456 ")
        runCurrent()

        assertEquals(listOf("a@example.com" to "123456"), sync.verified)
        assertEquals(1, sync.downloads, "a fresh sign-in pulls the account's notes straight away")
        assertNull(state.value.emailCodeSentTo)
        assertFalse(state.value.isSignInRequested)
        assertFalse(state.value.isSigningIn)
    }

    @Test
    fun `a wrong code keeps the code step, pulls nothing and reports why`() = runTest {
        val controller = controller()
        controller.requestEmailCode("a@example.com")
        runCurrent()
        sync.verifyResult = Result.failure(IllegalStateException("That code is wrong or has expired."))

        controller.signInWithEmailCode("a@example.com", "000000")
        runCurrent()

        assertEquals(0, sync.downloads)
        assertEquals("a@example.com", state.value.emailCodeSentTo)
        assertFalse(state.value.isSigningIn)
        assertEquals(
            CloudSyncEvent.Failure("That code is wrong or has expired."),
            state.value.pendingCloudSyncEvent,
        )
    }

    @Test
    fun `using a different email returns to the address step`() = runTest {
        val controller = controller()
        controller.requestEmailCode("a@example.com")
        runCurrent()

        controller.resetEmailCode()

        assertNull(state.value.emailCodeSentTo)
    }

    @Test
    fun `a sign-in requested from settings can be backed out of`() = runTest {
        val controller = controller()

        controller.requestSignIn()
        assertTrue(state.value.isSignInRequested)
        controller.requestEmailCode("a@example.com")
        runCurrent()

        controller.cancelSignInRequest()
        assertFalse(state.value.isSignInRequested)
        assertNull(state.value.emailCodeSentTo)
    }
}
