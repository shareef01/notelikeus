package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.isolateToNewDataset
import com.aus.notelikeus.data.sync.awaitStableEpoch
import com.aus.notelikeus.domain.platform.PendingSyncKind
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.InMemoryDatasetEpochStore
import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudNoteSyncCoordinatorTest {

    private lateinit var sessionManager: CloudSessionManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var workManager: WorkManager
    private lateinit var store: InMemoryDatasetEpochStore
    private lateinit var epochAuthority: DatasetEpochAuthority
    private lateinit var coordinator: CloudNoteSyncCoordinator

    @Before
    fun setup() {
        sessionManager = mockk()
        settingsRepository = mockk()
        workManager = mockk(relaxed = true)
        // The real epoch authority over a fake durable store: the queue and the epoch it stamps are
        // what these cases are about, so a relaxed mock here would assert nothing.
        store = InMemoryDatasetEpochStore()
        epochAuthority = store.authority()
        coordinator = CloudNoteSyncCoordinator(
            sessionManager,
            settingsRepository,
            workManager,
            epochAuthority
        )
    }

    @Test
    fun `flush enqueues upload work when auto sync and Google account are enabled`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 42L)
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork("sync_42", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `flush skips enqueue when auto sync is disabled`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(false)
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 42L)
        coordinator.flushNowForTest()

        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `flush skips enqueue when not signed in with Google`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "anon",
            email = null,
            isGoogleAccount = false,
            isAnonymous = true
        )

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 42L)
        coordinator.flushNowForTest()

        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `flush enqueues delete work for pending note`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.DELETE, 7L)
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork("sync_7", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `flush enqueues restore work carrying the restore flag`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        val request = slot<OneTimeWorkRequest>()

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.RESTORE, 3L)
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork("sync_3", ExistingWorkPolicy.REPLACE, capture(request))
        }
        val data = request.captured.workSpec.input
        assertTrue(data.getBoolean(SyncWorker.KEY_IS_RESTORE, false))
        assertFalse(data.getBoolean(SyncWorker.KEY_IS_DELETE, false))
    }

    @Test
    fun `scheduleRestore supersedes a pending delete for the same note`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        val requests = mutableListOf<OneTimeWorkRequest>()

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.DELETE, 4L)
        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.RESTORE, 4L)
        coordinator.flushNowForTest()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork("sync_4", ExistingWorkPolicy.REPLACE, capture(requests))
        }
        assertTrue(requests.single().workSpec.input.getBoolean(SyncWorker.KEY_IS_RESTORE, false))
    }

    @Test
    fun `a dispatched job carries the dataset epoch it was queued under`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        val request = slot<OneTimeWorkRequest>()

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 42L)
        val epoch = epochAuthority.awaitStableEpoch()
        coordinator.flushNowForTest()

        verify {
            workManager.enqueueUniqueWork("sync_42", ExistingWorkPolicy.REPLACE, capture(request))
        }
        val data = request.captured.workSpec.input
        org.junit.Assert.assertEquals("uid", data.getString(SyncWorker.KEY_EXPECTED_UID))
        org.junit.Assert.assertEquals(epoch.value, data.getString(SyncWorker.KEY_DATASET_EPOCH))
    }

    @Test
    fun `clearPending cancels WorkManager sync jobs`() = runTest {
        every { sessionManager.getCurrentAccount() } returns CloudSessionAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 5L)
        coordinator.clearPending()

        verify { workManager.cancelAllWorkByTag(SyncWorker.WORK_TAG) }
        verify { workManager.cancelUniqueWork("sync_5") }
    }
}
