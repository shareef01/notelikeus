package com.aus.notelikeus.data.remote

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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
            workManager,
            mockk(relaxed = true)
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

        coordinator.scheduleUpload(42L)
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork("sync_42", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `flush skips enqueue when auto sync is disabled`() = runTest {
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
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `flush skips enqueue when not signed in with Google`() = runTest {
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
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `flush enqueues delete work for pending note`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )

        coordinator.scheduleDelete(7L)
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork("sync_7", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }
}
