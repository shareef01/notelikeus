package com.aus.notelikeus.ui.main

import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.domain.repository.SyncManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Backup transfer used to finish in silence on every platform: the strings for reporting it have
 * existed since the feature was added but were referenced nowhere, so a user who picked a file had
 * no way to tell whether anything happened, how much came across, or why nothing did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupTransferReportingTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: NoteRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var syncManager: SyncManager
    private lateinit var importer: NoteBackupImporter

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        importer = mockk(relaxed = true)
        every { repository.getLabels() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = MainViewModel(
        repository,
        settingsRepository,
        mockk<NoteBackupExporter>(relaxed = true),
        importer,
        syncManager,
        testDispatcher,
    )

    @Test
    fun `a successful import reports how many notes arrived`() = runTest {
        coEvery { importer.importFromJson(any()) } returns
            BackupImportResult.Success(notesImported = 7, labelsCreated = 2)
        val subject = viewModel()

        subject.importBackup("{}")

        assertEquals(
            BackupTransferEvent.Imported(7),
            subject.state.value.pendingBackupTransferEvent,
        )
    }

    @Test
    fun `a rejected backup reports the parser's own reason`() = runTest {
        coEvery { importer.importFromJson(any()) } returns
            BackupImportResult.InvalidFormat("Backup file is too deeply nested")
        val subject = viewModel()

        subject.importBackup("{}")

        // More use than a generic failure: it says whether the file was too large, too deeply
        // nested, or from a newer build.
        assertEquals(
            BackupTransferEvent.ImportRejected("Backup file is too deeply nested"),
            subject.state.value.pendingBackupTransferEvent,
        )
    }

    @Test
    fun `an import that throws is still reported rather than swallowed`() = runTest {
        coEvery { importer.importFromJson(any()) } throws IllegalStateException("boom")
        val subject = viewModel()

        subject.importBackup("{}")

        assertEquals(
            BackupTransferEvent.ImportFailed,
            subject.state.value.pendingBackupTransferEvent,
        )
    }

    @Test
    fun `the platform layer can report how the export ended`() = runTest {
        val subject = viewModel()

        // Only the platform knows whether the bytes reached the document the user picked, so
        // exporting is reported from there rather than from the view model.
        subject.reportBackupTransfer(BackupTransferEvent.Exported)
        assertEquals(BackupTransferEvent.Exported, subject.state.value.pendingBackupTransferEvent)

        subject.reportBackupTransfer(BackupTransferEvent.ExportFailed)
        assertEquals(
            BackupTransferEvent.ExportFailed,
            subject.state.value.pendingBackupTransferEvent,
        )
    }

    @Test
    fun `the event clears once reported, so it cannot show twice`() = runTest {
        val subject = viewModel()
        subject.reportBackupTransfer(BackupTransferEvent.Exported)

        subject.clearPendingBackupTransferEvent()

        assertNull(subject.state.value.pendingBackupTransferEvent)
    }
}
