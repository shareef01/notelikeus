package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class CloudNoteSyncCoordinatorTest {

    private lateinit var firebaseNoteSync: FirebaseNoteSync
    private lateinit var firebaseSessionManager: FirebaseSessionManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var coordinator: CloudNoteSyncCoordinator

    @Before
    fun setup() {
        firebaseNoteSync = mockk(relaxed = true)
        firebaseSessionManager = mockk()
        settingsRepository = mockk()
        coordinator = CloudNoteSyncCoordinator(
            firebaseNoteSync,
            firebaseSessionManager,
            settingsRepository
        )
    }

    @Test
    fun `flush uploads pending note when auto sync and Google account are enabled`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        coEvery { firebaseNoteSync.uploadNote(42L) } returns Result.success(Unit)

        coordinator.scheduleUpload(42L)
        coordinator.flushNowForTest()

        coVerify { firebaseNoteSync.uploadNote(42L) }
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

        coVerify(exactly = 0) { firebaseNoteSync.uploadNote(any()) }
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

        coVerify(exactly = 0) { firebaseNoteSync.uploadNote(any()) }
    }

    @Test
    fun `flush deletes pending note`() = runTest {
        every { settingsRepository.isCloudAutoSyncEnabled } returns kotlinx.coroutines.flow.flowOf(true)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid",
            email = "user@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        coEvery { firebaseNoteSync.deleteNote(7L) } returns Result.success(Unit)

        coordinator.scheduleDelete(7L)
        coordinator.flushNowForTest()

        coVerify { firebaseNoteSync.deleteNote(7L) }
    }
}
