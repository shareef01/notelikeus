package com.aus.notelikeus.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.repository.NoteRepositoryImpl
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.platform.DesktopSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NL-03 over a real Room database: signing out keeps unsynced work sealed for its account, gives it
 * back to that account, and never to another one.
 *
 * Everything that decides is real — the isolator, the vault over the `sealed_accounts` table, the
 * repository, the engine's own count of unsynced work and its upload — so "the edit uploads when the
 * account comes back" is observed, not assumed. Only the cloud and the platform hooks are doubles.
 */
class SignedOutAccountVaultDatabaseTest {

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase

    private var signedIn: String? = null
    private val transport = FakeCloudNoteTransport()
    private val stateStore = FakeNoteSyncStateStore()
    private lateinit var engine: NoteSyncEngine
    private lateinit var repository: NoteRepositoryImpl
    private lateinit var isolator: LocalAccountIsolator

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-sealed-accounts-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(name = File(tempDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { signedIn },
            noteDao = database.noteDao,
            labelDao = database.labelDao,
            syncStateStore = stateStore,
            uidProvider = { signedIn?.let { Result.success(it) } ?: Result.failure(IllegalStateException("signed out")) },
            platform = "desktop",
            datasetEpochAuthority = authority,
        )
        val coordinator = DesktopSyncCoordinator(
            syncEngine = engine,
            epochAuthority = authority,
            ownerUidProvider = { signedIn },
            // Never dispatches: uploads below are run directly, so the test sees exactly one.
            scope = CoroutineScope(SupervisorJob().apply { cancel() }),
        )
        repository = NoteRepositoryImpl(
            database = database,
            noteDao = database.noteDao,
            labelDao = database.labelDao,
            reminderManager = NoopReminderManager(),
            widgetManager = NoopWidgetManager(),
            syncCoordinator = coordinator,
            ioDispatcher = Dispatchers.Unconfined,
        )
        isolator = LocalAccountIsolator(
            noteRepository = repository,
            syncStateStore = stateStore,
            syncCoordinator = coordinator,
            signedOutAccounts = SignedOutAccountVault(
                noteRepository = repository,
                syncStateStore = stateStore,
                store = RoomSealedAccountStore(database.sealedAccountDao),
                unsyncedWork = { engine.unsyncedWork() },
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    /** What `DesktopSyncManager.signOut` does, minus the session. */
    private suspend fun signOut(uid: String) {
        isolator.sealUnsyncedWork(uid)
        signedIn = null
        isolator.isolate(sealFor = uid)
    }

    /** What `DesktopSyncManager.onSignedIn` does, minus the session. */
    private suspend fun signIn(uid: String) {
        signedIn = uid
        isolator.isolateIfAccountChanged(uid)
    }

    /** Alice, signed in, with a labelled note written offline that never reached the cloud. */
    private suspend fun aliceWithANoteTheCloudHasNotSeen() {
        signIn(ALICE)
        stateStore.setLastMergedUserId(ALICE)
        val label = repository.insertLabel(Label(name = "Groceries"))
        repository.insertNoteWithoutSync(
            Note(
                id = NOTE_ID, title = "Written offline", content = "milk", timestamp = 1_000L, color = 0,
                labels = listOf(Label(id = label, name = "Groceries")),
            ),
        )
        assertEquals(1, engine.unsyncedWork().edits)
    }

    private suspend fun localTitles() = database.noteDao.getAllNotesForBackup().map { it.note.title }

    @Test
    fun `signing out with an unsynced note wipes the library and seals it in the database`() = runTest {
        aliceWithANoteTheCloudHasNotSeen()

        signOut(ALICE)

        assertTrue(localTitles().isEmpty())
        assertTrue(database.labelDao.getAllLabelsOnce().isEmpty())
        val sealed = database.sealedAccountDao.getAll().single()
        assertEquals(ALICE, sealed.ownerUid)
        assertTrue(sealed.armed)
    }

    @Test
    fun `the same account signing back in gets the note back and uploads it`() = runTest {
        aliceWithANoteTheCloudHasNotSeen()
        signOut(ALICE)

        signIn(ALICE)

        assertEquals(listOf("Written offline"), localTitles())
        assertEquals(listOf("Groceries"), repository.getNoteById(NOTE_ID)!!.labels.map { it.name })
        assertTrue(database.sealedAccountDao.getAll().isEmpty())
        assertTrue(engine.uploadAllNotes().isSuccess)
        assertEquals("Written offline", transport.notes.getValue(NOTE_ID).title)
        assertTrue(engine.unsyncedWork().isEmpty, "the edit the sign-out kept is now in the cloud")
    }

    @Test
    fun `a different account after a fully synced sign-out finds nothing of the first`() = runTest {
        aliceWithANoteTheCloudHasNotSeen()
        assertTrue(engine.uploadAllNotes().isSuccess)
        assertTrue(engine.unsyncedWork().isEmpty)

        signOut(ALICE)
        signIn(BOB)

        assertTrue(localTitles().isEmpty())
        assertTrue(database.sealedAccountDao.getAll().isEmpty(), "the cloud has it all, so nothing is kept")
    }

    @Test
    fun `a different account cannot reach unsynced work the first account sealed, which waits for it`() = runTest {
        aliceWithANoteTheCloudHasNotSeen()
        signOut(ALICE)

        signIn(BOB)

        assertTrue(localTitles().isEmpty(), "bob must not inherit alice's note")
        assertTrue(engine.unsyncedWork().isEmpty, "nor anything that would upload under his account")
        assertEquals(listOf(ALICE), database.sealedAccountDao.getAll().map { it.ownerUid })

        signOut(BOB)
        signIn(ALICE)

        assertEquals(listOf("Written offline"), localTitles())
        assertEquals(1, engine.unsyncedWork().edits)
    }

    private class NoopReminderManager : ReminderManager {
        override fun scheduleReminder(noteId: Long, timestamp: Long) {}
        override fun cancelReminder(noteId: Long) {}
    }

    private class NoopWidgetManager : PlatformWidgetManager {
        override suspend fun refreshWidgets() {}
    }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        const val NOTE_ID = 1_700_000_000_000_001L
    }
}
