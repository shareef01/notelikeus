package com.aus.notelikeus.di

import android.util.Log
import com.aus.notelikeus.data.backup.BackupBundleOperations
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.diagnostics.DiagnosticsCollector
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.editor.FakeLocalCommitTokenProvider
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncEvent
import com.aus.notelikeus.ui.main.CloudSyncStatus
import com.aus.notelikeus.ui.main.MainViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Builds [MainViewModel] through the real [sharedModule] definition rather than by hand.
 *
 * `diagnostics` and `bundleOperations` have defaults, so when a constructor parameter was added
 * ahead of them and the module's positional `get()` list was not extended, `bundleOperations`
 * quietly became the no-op and `.nlkbak` import/export stopped working on Android and Windows
 * while every hand-built test still passed. This pins the module, not the constructor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelWiringTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `the module hands MainViewModel the platform bundle operations`() {
        val bundleOperations = mockk<BackupBundleOperations>()
        every { bundleOperations.looksLikeBundle(any(), any()) } returns true

        val koin = koinApplication {
            allowOverride(true)
            modules(sharedModule, fakePlatform(bundleOperations))
        }.koin

        val viewModel = koin.get<MainViewModel>()

        assertTrue(viewModel.looksLikeBackupBundle("notes.nlkbak", byteArrayOf(0x50, 0x4b)))
        verify { bundleOperations.looksLikeBundle("notes.nlkbak", any()) }
        koin.close()
    }

    private fun fakePlatform(bundleOperations: BackupBundleOperations) = module {
        val repository = mockk<NoteRepository>(relaxed = true)
        every { repository.getActiveNotes() } returns flowOf(emptyList())
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.isAppLockEnabled } returns flowOf(false)
        every { settings.noteViewMode } returns flowOf(NoteViewMode.GRID_2)
        every { settings.noteSortOrder } returns flowOf(NoteSortOrder.MANUAL)
        every { settings.isCloudAutoSyncEnabled } returns flowOf(true)
        val syncManager = mockk<SyncManager>(relaxed = true)
        every { syncManager.cloudAccount } returns MutableStateFlow(CloudAccount())
        every { syncManager.syncStatus } returns MutableStateFlow(CloudSyncStatus.Synced)
        every { syncManager.pendingEvent } returns MutableStateFlow<CloudSyncEvent?>(null)

        single<NoteRepository> { repository }
        single<SettingsRepository> { settings }
        single<LocalCommitTokenProvider> { FakeLocalCommitTokenProvider() }
        single<CoroutineDispatcher> { testDispatcher }
        single<SyncManager> { syncManager }
        single { mockk<NoteBackupExporter>(relaxed = true) }
        single { mockk<NoteBackupImporter>(relaxed = true) }
        single { mockk<DiagnosticsCollector>(relaxed = true) }
        single { bundleOperations }
    }
}
