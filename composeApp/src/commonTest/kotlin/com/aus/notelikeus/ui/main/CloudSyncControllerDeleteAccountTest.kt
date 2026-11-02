package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.repository.SyncManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerDeleteAccountTest {
    private class FakeSyncManager : SyncManager {
        override val syncStatus = MutableStateFlow(CloudSyncStatus.Unknown)
        override val cloudAccount = MutableStateFlow(CloudAccount())
        override val pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)

        var deleteResult: Result<Unit> = Result.success(Unit)
        var gate: CompletableDeferred<Unit>? = null
        var deletes = 0

        override suspend fun deleteAccount(): Result<Unit> {
            deletes++
            gate?.await()
            return deleteResult
        }

        override suspend fun requestEmailCode(email: String) = Result.success(Unit)
        override suspend fun signInWithEmailCode(email: String, code: String) = Result.success(Unit)
        override suspend fun signInWithEmail(email: String, password: String, create: Boolean) = Result.success(Unit)
        override suspend fun signOut(deleteCloudData: Boolean) = Result.success(Unit)
        override suspend fun syncNotes() = Unit
        override suspend fun allowUploadAfterCloudWipe(): Result<Unit> = Result.success(Unit)
        override suspend fun downloadNotes() = Unit
        override fun clearPendingEvent() = Unit
    }

    private val sync = FakeSyncManager()
    private val state = MutableStateFlow(MainState())

    private fun TestScope.controller() = CloudSyncController(state, backgroundScope, sync).also {
        it.observe()
        runCurrent()
    }

    @Test
    fun `a deleted account is reported as this device's doing`() = runTest {
        val controller = controller()

        controller.deleteAccount()
        runCurrent()

        assertEquals(1, sync.deletes)
        assertEquals(CloudSyncEvent.AccountDeleted(elsewhere = false), state.value.pendingCloudSyncEvent)
        assertFalse(state.value.isDeletingAccount)
    }

    @Test
    fun `a failure says nothing was deleted and allows a retry`() = runTest {
        val controller = controller()
        sync.deleteResult = Result.failure(IllegalStateException("HTTP 503 from the Worker"))

        controller.deleteAccount()
        runCurrent()

        val event = assertIs<CloudSyncEvent.Failure>(state.value.pendingCloudSyncEvent)
        assertTrue("nothing was deleted" in event.message, event.message)
        assertFalse("503" in event.message, "transport detail is not for the user")
        assertFalse(state.value.isDeletingAccount)
    }

    @Test
    fun `a second tap while the request runs sends nothing more`() = runTest {
        val controller = controller()
        sync.gate = CompletableDeferred()

        controller.deleteAccount()
        runCurrent()
        assertTrue(state.value.isDeletingAccount)
        controller.deleteAccount()
        runCurrent()

        assertEquals(1, sync.deletes)
        sync.gate?.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a deletion from another device reaches the screen`() = runTest {
        controller()

        sync.pendingEvent.value = CloudSyncEvent.AccountDeleted(elsewhere = true)
        runCurrent()

        assertEquals(CloudSyncEvent.AccountDeleted(elsewhere = true), state.value.pendingCloudSyncEvent)
    }

    @Test
    fun `the confirmation has to be the exact word`() {
        assertTrue(isDeleteAccountConfirmed("DELETE"))
        listOf("", "delete", "DELET", " DELETE", "DELETE ").forEach {
            assertFalse(isDeleteAccountConfirmed(it), "armed by '$it'")
        }
    }
}
