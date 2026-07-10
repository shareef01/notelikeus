package com.aus.notelikeus.data.remote

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CloudNoteSyncCoordinatorTest {

    private lateinit var firebaseNoteSync: FirebaseNoteSync
    private lateinit var firebaseSessionManager: FirebaseSessionManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var workManager: WorkManager
    private lateinit var coordinator: CloudNoteSyncCoordinator

    @Before
    fun setup() {
        firebaseNoteSync = mockk(relaxed = true)
        firebaseSessionManager = mockk()
        settingsRepository = mockk()
        workManager = mockk(relaxed = true)
        coordinator = CloudNoteSyncCoordinator(
            firebaseNoteSync,
            firebaseSessionManager,
            settingsRepository,
            workManager
        )
    }

    @Test
    fun `flush enqueues upload work when auto sync and Google account are enabled`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        val workRequestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                ExistingWorkPolicy.REPLACE,
                capture(workRequestSlot)
            )
        } returns mockk(relaxed = true)

        coordinator.scheduleUpload(42L)
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork(
                "sync_42",
                ExistingWorkPolicy.REPLACE,
                any()
            )
        }
        assertEquals(42L, workRequestSlot.captured.workSpec.input.getLong(SyncWorker.KEY_NOTE_ID, -1L))
        assertEquals(false, workRequestSlot.captured.workSpec.input.getBoolean(SyncWorker.KEY_IS_DELETE, true))
    }

    @Test
    fun `flush skips upload when auto sync is disabled`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(false)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )

        coordinator.scheduleUpload(42L)
        coordinator.flushNowForTest()

        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any())
        }
    }

    @Test
    fun `flush skips upload when not signed in with Google`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "anon",
            email = null,
            isGoogleAccount = false,
            isAnonymous = true
        )

        coordinator.scheduleUpload(42L)
        coordinator.flushNowForTest()

        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any())
        }
    }

    @Test
    fun `flush enqueues delete work`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        val workRequestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                ExistingWorkPolicy.REPLACE,
                capture(workRequestSlot)
            )
        } returns mockk(relaxed = true)

        coordinator.scheduleDelete(7L)
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork(
                "sync_7",
                ExistingWorkPolicy.REPLACE,
                any()
            )
        }
        assertEquals(7L, workRequestSlot.captured.workSpec.input.getLong(SyncWorker.KEY_NOTE_ID, -1L))
        assertEquals(true, workRequestSlot.captured.workSpec.input.getBoolean(SyncWorker.KEY_IS_DELETE, false))
    }
}
