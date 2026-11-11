package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.domain.repository.UnsyncedWork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.cloud_sign_out_unsynced_check_failed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * AUDIT-001: an ordinary sign-out clears this device's notes. It must not do that to work the cloud
 * does not have yet unless the user, told what that work is, chooses to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerSignOutTest {

    private class FakeSyncManager : SyncManager {
        override val syncStatus = MutableStateFlow(CloudSyncStatus.Unknown)
        override val cloudAccount = MutableStateFlow(CloudAccount(email = "me@example.com", isSignedIn = true))
        override val pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)

        val calls = mutableListOf<String>()
        var unsynced: Result<UnsyncedWork> = Result.success(UnsyncedWork.NONE)

        override suspend fun unsyncedWorkBeforeSignOut(): UnsyncedWork {
            calls += "check"
            return unsynced.getOrThrow()
        }

        override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
            calls += "signOut($deleteCloudData)"
            return Result.success(Unit)
        }

        override suspend fun requestEmailCode(email: String) = Result.success(Unit)
        override suspend fun signInWithEmailCode(email: String, code: String) = Result.success(Unit)
        override suspend fun signInWithEmail(email: String, password: String, create: Boolean) = Result.success(Unit)
        override suspend fun deleteAccount() = Result.success(Unit)
        override suspend fun syncNotes() = Unit
        override suspend fun downloadNotes() = Unit
        override suspend fun allowUploadAfterCloudWipe() = Result.success(Unit)
        override fun clearPendingEvent() = Unit
    }

    private val sync = FakeSyncManager()
    private val state = MutableStateFlow(MainState())

    private fun TestScope.controller() = CloudSyncController(state, backgroundScope, sync).also {
        it.observe()
        runCurrent()
    }

    @Test
    fun `with nothing left to sync it signs out`() = runTest {
        val controller = controller()

        controller.signOutFromCloud()
        runCurrent()

        assertEquals(listOf("check", "signOut(false)"), sync.calls)
        assertNull(state.value.unsyncedBeforeSignOut)
    }

    @Test
    fun `unsynced work holds the sign-out and is shown to the user`() = runTest {
        val controller = controller()
        val work = UnsyncedWork(edits = 2, deletions = 1, images = 3)
        sync.unsynced = Result.success(work)

        controller.signOutFromCloud()
        runCurrent()

        assertEquals(listOf("check"), sync.calls, "nothing was cleared")
        assertEquals(work, state.value.unsyncedBeforeSignOut)
    }

    @Test
    fun `staying signed in keeps everything, and choosing to lose it signs out`() = runTest {
        val controller = controller()
        sync.unsynced = Result.success(UnsyncedWork(edits = 1))
        controller.signOutFromCloud()
        runCurrent()

        controller.keepUnsyncedAndStaySignedIn()
        assertNull(state.value.unsyncedBeforeSignOut)
        assertEquals(listOf("check"), sync.calls)

        controller.signOutFromCloud(discardUnsynced = true)
        runCurrent()
        assertEquals(listOf("check", "signOut(false)"), sync.calls)
        assertNull(state.value.unsyncedBeforeSignOut)
    }

    @Test
    fun `a check that fails does not clear the way to signing out`() = runTest {
        val controller = controller()
        sync.unsynced = Result.failure(IllegalStateException("database unavailable"))

        controller.signOutFromCloud()
        runCurrent()

        assertEquals(listOf("check"), sync.calls)
        val event = assertIs<CloudSyncEvent.Failure>(state.value.pendingCloudSyncEvent)
        // NL-12: the app's own wording comes from strings.xml, so it can be translated.
        assertEquals(Res.string.cloud_sign_out_unsynced_check_failed, event.messageRes)
    }

    @Test
    fun `deleting the cloud data is already a choice to lose everything, so it is not asked again`() = runTest {
        val controller = controller()
        sync.unsynced = Result.success(UnsyncedWork(edits = 1))

        controller.signOutFromCloud(deleteCloudData = true)
        runCurrent()

        assertEquals(listOf("signOut(true)"), sync.calls)
    }
}
