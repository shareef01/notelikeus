package com.aus.notelikeus.ui.main

import com.aus.notelikeus.data.remote.SupabaseTransportException
import com.aus.notelikeus.domain.repository.SyncManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AUD-005: deleting the account or wiping its cloud data needs a recent sign-in. When the server says
 * so, nothing was deleted; the app asks for a new code for the same account, then retries the action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerIdentityCheckTest {
    private val email = "me@example.com"

    /** The Worker's answer to DELETE /v1/account on a stale session. */
    private fun staleForDeletion() = SupabaseTransportException(
        "account",
        403,
        """{"error":"reauthentication_required"}""",
    )

    /** The database's answer to delete_all_user_cloud_data on a stale session. */
    private fun staleForWipe() = SupabaseTransportException(
        "delete_all_user_cloud_data",
        400,
        """{"code":"NL004","message":"reauthentication_required: confirm it is you"}""",
    )

    private class FakeSyncManager : SyncManager {
        override val syncStatus = MutableStateFlow(CloudSyncStatus.Unknown)
        override val cloudAccount = MutableStateFlow(CloudAccount())
        override val pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)

        val calls = mutableListOf<String>()
        val deleteResults = ArrayDeque<Result<Unit>>()
        val signOutResults = ArrayDeque<Result<Unit>>()
        var signInResult: Result<Unit> = Result.success(Unit)

        override suspend fun deleteAccount(): Result<Unit> {
            calls += "deleteAccount"
            return deleteResults.removeFirstOrNull() ?: Result.success(Unit)
        }

        override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
            calls += "signOut($deleteCloudData)"
            return signOutResults.removeFirstOrNull() ?: Result.success(Unit)
        }

        override suspend fun requestEmailCode(email: String): Result<Unit> {
            calls += "requestEmailCode($email)"
            return Result.success(Unit)
        }

        override suspend fun signInWithEmailCode(email: String, code: String): Result<Unit> {
            calls += "signInWithEmailCode($email,$code)"
            return signInResult
        }

        override suspend fun signInWithEmail(email: String, password: String, create: Boolean) = Result.success(Unit)
        override suspend fun syncNotes() = Unit
        override suspend fun allowUploadAfterCloudWipe(): Result<Unit> = Result.success(Unit)
        override suspend fun downloadNotes() = Unit
        override fun clearPendingEvent() = Unit
    }

    private val sync = FakeSyncManager().apply {
        cloudAccount.value = CloudAccount(email = "me@example.com", isSignedIn = true)
    }
    private val state = MutableStateFlow(MainState())

    private fun TestScope.controller() = CloudSyncController(state, backgroundScope, sync).also {
        it.observe()
        runCurrent()
    }

    @Test
    fun `a stale session asks to confirm instead of reporting a failed deletion`() = runTest {
        val controller = controller()
        sync.deleteResults += Result.failure(staleForDeletion())

        controller.deleteAccount()
        runCurrent()

        assertEquals(IdentityCheck.DeleteAccount, state.value.pendingIdentityCheck)
        assertFalse(state.value.identityCodeSent)
        assertFalse(state.value.isDeletingAccount)
        assertNull(state.value.pendingCloudSyncEvent, "a refusal that deleted nothing is not a failure")
    }

    @Test
    fun `the code goes to the signed-in address, and the deletion is retried once it is accepted`() = runTest {
        val controller = controller()
        sync.deleteResults += Result.failure(staleForDeletion())
        controller.deleteAccount()
        runCurrent()

        controller.requestIdentityCode()
        runCurrent()
        assertTrue(state.value.identityCodeSent)
        controller.confirmIdentity(" 123456 ")
        runCurrent()

        assertEquals(
            listOf("deleteAccount", "requestEmailCode($email)", "signInWithEmailCode($email,123456)", "deleteAccount"),
            sync.calls,
        )
        assertNull(state.value.pendingIdentityCheck)
        assertEquals(CloudSyncEvent.AccountDeleted(elsewhere = false), state.value.pendingCloudSyncEvent)
    }

    @Test
    fun `a wrong code retries nothing and keeps the dialog`() = runTest {
        val controller = controller()
        sync.deleteResults += Result.failure(staleForDeletion())
        controller.deleteAccount()
        runCurrent()
        sync.signInResult = Result.failure(IllegalStateException("Token has expired or is invalid"))

        controller.confirmIdentity("000000")
        runCurrent()

        assertEquals(listOf("deleteAccount", "signInWithEmailCode($email,000000)"), sync.calls)
        assertEquals(IdentityCheck.DeleteAccount, state.value.pendingIdentityCheck)
        assertIs<CloudSyncEvent.Failure>(state.value.pendingCloudSyncEvent)
    }

    @Test
    fun `a stale wipe asks to confirm, then wipes and signs out with the fresh session`() = runTest {
        val controller = controller()
        sync.signOutResults += Result.failure(staleForWipe())

        controller.signOutFromCloud(deleteCloudData = true)
        runCurrent()
        assertEquals(IdentityCheck.WipeCloudData, state.value.pendingIdentityCheck)
        assertNull(state.value.pendingCloudSyncEvent)

        controller.confirmIdentity("654321")
        runCurrent()

        assertEquals(listOf("signOut(true)", "signInWithEmailCode($email,654321)", "signOut(true)"), sync.calls)
        assertEquals(CloudSyncEvent.SignedOut(cloudDataDeleted = true), state.value.pendingCloudSyncEvent)
    }

    @Test
    fun `cancelling leaves the action undone`() = runTest {
        val controller = controller()
        sync.deleteResults += Result.failure(staleForDeletion())
        controller.deleteAccount()
        runCurrent()

        controller.cancelIdentityCheck()
        runCurrent()
        controller.confirmIdentity("123456")
        runCurrent()

        assertNull(state.value.pendingIdentityCheck)
        assertEquals(listOf("deleteAccount"), sync.calls)
    }

    @Test
    fun `any other failure is still reported as a failure`() = runTest {
        val controller = controller()
        sync.deleteResults += Result.failure(SupabaseTransportException("account", 403, "Forbidden"))

        controller.deleteAccount()
        runCurrent()

        assertNull(state.value.pendingIdentityCheck)
        assertIs<CloudSyncEvent.Failure>(state.value.pendingCloudSyncEvent)
    }

    @Test
    fun `only a whole numeric code can be sent for checking`() {
        assertTrue(isIdentityCodeComplete("123456"))
        listOf("", "12345", "1234567", "12345a").forEach { assertFalse(isIdentityCodeComplete(it), it) }
    }
}
