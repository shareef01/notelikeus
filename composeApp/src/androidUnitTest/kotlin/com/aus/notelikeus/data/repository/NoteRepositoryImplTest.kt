package com.aus.notelikeus.data.repository

import android.content.Context
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.Dispatchers
import io.mockk.*
import androidx.room.Transactor
import androidx.room.TransactionScope
import androidx.room.useWriterConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NoteRepositoryImplTest {

    private lateinit var repository: NoteRepositoryImpl
    private lateinit var database: NotelikeusDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var labelDao: LabelDao
    private lateinit var reminderManager: ReminderManager
    private lateinit var widgetManager: PlatformWidgetManager
    private lateinit var syncCoordinator: SyncCoordinator

    @Before
    fun setup() {
        database = mockk()
        noteDao = mockk(relaxed = true)
        labelDao = mockk(relaxed = true)
        reminderManager = mockk(relaxed = true)
        widgetManager = mockk(relaxed = true)
        syncCoordinator = mockk(relaxed = true)

        // Stand in for the real writer connection: hand the repository a Transactor whose
        // immediateTransaction actually runs its block, so the production code path under test
        // is the transactional one rather than a relaxed no-op.
        // immediateTransaction is an inline extension over Transactor.withTransaction, so the
        // interface method is what actually gets called and therefore what has to be stubbed.
        val transactor = mockk<Transactor>()
        coEvery { transactor.withTransaction<Any?>(any(), any()) } coAnswers {
            val txBlock = it.invocation.args[1] as suspend TransactionScope<Any?>.() -> Any?
            txBlock(mockk(relaxed = true))
        }
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.useWriterConnection<Any?>(any()) } coAnswers {
            val block = it.invocation.args[1] as suspend (Transactor) -> Any?
            block(transactor)
        }

        repository = NoteRepositoryImpl(database, noteDao, labelDao, reminderManager, widgetManager, syncCoordinator, Dispatchers.Unconfined)
    }

    @Test
    fun `insertNoteWithResult inserts note and schedules upload`() = runTest {
        val note = Note(title = "Test", content = "Content", timestamp = 0L, color = 0)
        coEvery { noteDao.insertNote(any()) } returns 1L

        val result = repository.insertNoteWithResult(note)

        assertEquals(1L, result)
        coVerify { noteDao.insertNote(match { it.title == "Test" }) }
        coVerify { syncCoordinator.scheduleUpload(1L) }
    }

    @Test
    fun `restoreNote inserts note and schedules restore`() = runTest {
        val note = Note(title = "Restored", content = "Content", timestamp = 0L, color = 0)
        coEvery { noteDao.insertNote(any()) } returns 2L

        val result = repository.restoreNote(note)

        assertEquals(2L, result)
        coVerify { noteDao.insertNote(match { it.title == "Restored" }) }
        coVerify { syncCoordinator.scheduleRestore(2L) }
    }

    @Test
    fun `updateNote updates note and schedules reminder`() = runTest {
        val reminderTime = System.currentTimeMillis() + 1000
        val note = Note(id = 1L, title = "Test", content = "Content", timestamp = 0L, color = 0, reminderTimestamp = reminderTime)

        repository.updateNote(note)

        coVerify { noteDao.updateNote(match { it.id == 1L }) }
        coVerify { reminderManager.scheduleReminder(1L, reminderTime) }
    }

    @Test
    fun `deleteNote cancels reminder and deletes from DAO`() = runTest {
        val note = Note(id = 1L, title = "Test", content = "Content", timestamp = 0L, color = 0)

        repository.deleteNote(note)

        coVerify { reminderManager.cancelReminder(1L) }
        coVerify { noteDao.deleteNote(any()) }
    }
}
