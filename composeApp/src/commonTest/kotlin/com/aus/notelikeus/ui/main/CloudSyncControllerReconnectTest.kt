package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.repository.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pull that runs when the network comes back (the Pixel test: an offline edit uploaded on
 * reconnect, but the web's edit of the same note only appeared after a restart), and the
 * pull-to-refresh entry point that shares its guards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerReconnectTest {

    private class FakeSyncManager : SyncManager {
        override val syncStatus = MutableStateFlow(CloudSyncStatus.Unknown)
        override val cloudAccount = MutableStateFlow(SIGNED_IN)
        override val pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)
        var downloads = 0

        override suspend fun requestEmailCode(email: String) = Result.success(Unit)
        override suspend fun signInWithEmailCode(email: String, code: String) = Result.success(Unit)
        override suspend fun signInWithEmail(email: String, password: String, create: Boolean) = Result.success(Unit)
        override suspend fun signOut(deleteCloudData: Boolean) = Result.success(Unit)
        override suspend fun syncNotes() = Unit
        override suspend fun downloadNotes() {
            downloads++
        }
        override fun clearPendingEvent() = Unit
    }

    private val sync = FakeSyncManager()
    private val online = MutableStateFlow(true)
    private val state = MutableStateFlow(MainState())

    private fun TestScope.controller(
        scope: CoroutineScope = backgroundScope,
        isOnline: StateFlow<Boolean>? = online,
    ) = CloudSyncController(state, scope, sync, isOnline).also {
        it.observe()
        runCurrent()
    }

    private fun TestScope.reconnect() {
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
    }

    @Test
    fun `a reconnect pulls once the delay has passed`() = runTest {
        controller()
        reconnect()

        advanceTimeBy(RECONNECT_PULL_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, sync.downloads, "the queued uploads get their head start first")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, sync.downloads)
    }

    @Test
    fun `being online from the start is not a reconnect`() = runTest {
        controller()
        advanceTimeBy(RECONNECT_PULL_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, sync.downloads)
    }

    @Test
    fun `losing the network again before the delay cancels the pull`() = runTest {
        controller()
        reconnect()
        advanceTimeBy(RECONNECT_PULL_DELAY_MS / 2)
        online.value = false
        runCurrent()

        advanceTimeBy(RECONNECT_PULL_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, sync.downloads)
    }

    @Test
    fun `a flapping connection pulls once`() = runTest {
        controller()
        reconnect()
        advanceTimeBy(RECONNECT_PULL_DELAY_MS / 2)
        reconnect()

        advanceTimeBy(RECONNECT_PULL_DELAY_MS * 2)
        runCurrent()
        assertEquals(1, sync.downloads)
    }

    @Test
    fun `no reconnect pull when signed out or with auto-sync off`() = runTest {
        sync.cloudAccount.value = CloudAccount()
        controller()
        reconnect()
        advanceTimeBy(RECONNECT_PULL_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, sync.downloads)

        sync.cloudAccount.value = SIGNED_IN
        state.value = state.value.copy(isCloudAutoSyncEnabled = false)
        runCurrent()
        reconnect()
        advanceTimeBy(RECONNECT_PULL_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, sync.downloads)
    }

    @Test
    fun `the reconnect pull is not swallowed by a recent foreground pull`() = runTest {
        val controller = controller()
        controller.autoSyncOnForeground()
        runCurrent()
        assertEquals(1, sync.downloads)

        // Well inside the 30 s foreground throttle.
        reconnect()
        advanceTimeBy(RECONNECT_PULL_DELAY_MS)
        runCurrent()
        assertEquals(2, sync.downloads)

        // It stamps the throttle, so the resume that usually follows does not pull again.
        controller.autoSyncOnForeground()
        runCurrent()
        assertEquals(2, sync.downloads)
    }

    @Test
    fun `a sync already running delays the reconnect pull instead of dropping it`() = runTest {
        controller()
        sync.syncStatus.value = CloudSyncStatus.Syncing
        runCurrent()
        reconnect()
        advanceTimeBy(RECONNECT_PULL_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, sync.downloads)

        sync.syncStatus.value = CloudSyncStatus.Offline
        runCurrent()
        assertEquals(1, sync.downloads)
    }

    @Test
    fun `without a network monitor nothing changes`() = runTest {
        controller(isOnline = null)
        advanceTimeBy(RECONNECT_PULL_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, sync.downloads)
    }

    @Test
    fun `pull-to-refresh pulls when signed in, even with auto-sync off`() = runTest {
        val controller = controller()
        state.value = state.value.copy(isCloudAutoSyncEnabled = false)
        controller.refreshFromCloud()
        runCurrent()
        assertEquals(1, sync.downloads)
    }

    @Test
    fun `pull-to-refresh is a no-op when signed out or already syncing`() = runTest {
        val controller = controller()
        sync.syncStatus.value = CloudSyncStatus.Syncing
        runCurrent()
        controller.refreshFromCloud()
        runCurrent()
        assertEquals(0, sync.downloads)

        sync.syncStatus.value = CloudSyncStatus.Synced
        sync.cloudAccount.value = CloudAccount()
        runCurrent()
        controller.refreshFromCloud()
        runCurrent()
        assertEquals(0, sync.downloads)
    }

    private companion object {
        val SIGNED_IN = CloudAccount(email = "a@example.com", isSignedIn = true, isAnonymous = false)
    }
}
