package com.aus.notelikeus.platform

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.remote.AccountDeletionTransport
import com.aus.notelikeus.data.remote.CloudSessionAccount
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.repository.NoteRepositoryImpl
import com.aus.notelikeus.data.sync.CloudWipeCoordinator
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.InMemoryDatasetEpochStore
import com.aus.notelikeus.data.sync.InMemoryPendingCloudWipeIntentStore
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.RoomSealedAccountStore
import com.aus.notelikeus.data.sync.SealedAccount
import com.aus.notelikeus.data.sync.SealedAccountStore
import com.aus.notelikeus.data.sync.SignedOutAccountVault
import com.aus.notelikeus.data.sync.testRemoteIdentityProvider
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NL-03 through [DesktopSyncManager.signOut] itself, over a real Room database.
 *
 * `SignedOutAccountVaultDatabaseTest` covers the vault and the isolator by repeating the sign-out's
 * steps by hand. This drives the manager, so the order it runs them in — seal, end the session,
 * isolate, and undo the seal if the session does not end — is what is tested.
 *
 * On a real dispatcher, like `DesktopSyncManagerAccountDeletionTest`: the manager moves its work to
 * `Dispatchers.IO` and Room does real I/O.
 */
class DesktopSyncManagerSignOutTest {

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase
    private lateinit var repository: NoteRepositoryImpl
    private lateinit var manager: DesktopSyncManager

    private val session = FakeSession()
    private val stateStore = FakeNoteSyncStateStore()
    private val transport = FakeCloudNoteTransport()
    private lateinit var sealedStore: FailableSealedStore

    private class FakeSession : CloudSessionManager {
        var uid: String? = null
        var signOuts = 0
        var signOutFailure: Throwable? = null

        override fun getCurrentAccount() = CloudSessionAccount(uid, uid?.let { "$it@example.com" }, uid != null, false)
        override suspend fun requestEmailCode(email: String) = Result.success(Unit)
        override suspend fun signInWithEmailCode(email: String, code: String): Result<Unit> {
            uid = email.substringBefore('@')
            return Result.success(Unit)
        }
        override suspend fun signInWithEmailPassword(email: String, password: String, createAccount: Boolean) =
            signInWithEmailCode(email, password)
        override suspend fun signOut(): Result<Unit> {
            signOutFailure?.let { return Result.failure(it) }
            signOuts++
            uid = null
            return Result.success(Unit)
        }
        override suspend fun ensureSignedIn() =
            uid?.let { Result.success(it) } ?: Result.failure(IllegalStateException("signed out"))
        override fun diagnose(error: Throwable) = error.message.orEmpty()
    }

    /** The `sealed_accounts` table, with a switch that makes the seal fail to write. */
    private class FailableSealedStore(private val real: SealedAccountStore) : SealedAccountStore by real {
        var failPut = false
        override suspend fun put(account: SealedAccount) {
            if (failPut) error("disk full")
            real.put(account)
        }
    }

    private class NoopDeletion : AccountDeletionTransport {
        override suspend fun deleteAccount() = Unit
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-sign-out-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(name = File(tempDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        val engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { session.uid },
            noteDao = database.noteDao,
            labelDao = database.labelDao,
            syncStateStore = stateStore,
            uidProvider = { session.ensureSignedIn() },
            platform = "desktop",
            datasetEpochAuthority = authority,
        )
        val coordinator = DesktopSyncCoordinator(
            syncEngine = engine,
            epochAuthority = authority,
            ownerUidProvider = { session.uid },
            // Never dispatches, so no background upload races the assertions.
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
        sealedStore = FailableSealedStore(RoomSealedAccountStore(database.sealedAccountDao))
        manager = DesktopSyncManager(
            syncEngine = engine,
            sessionManager = session,
            isolator = LocalAccountIsolator(
                noteRepository = repository,
                syncStateStore = stateStore,
                syncCoordinator = coordinator,
                signedOutAccounts = SignedOutAccountVault(
                    noteRepository = repository,
                    syncStateStore = stateStore,
                    store = sealedStore,
                    unsyncedWork = { engine.unsyncedWork() },
                ),
            ),
            cloudWipeCoordinator = CloudWipeCoordinator(InMemoryPendingCloudWipeIntentStore()),
            accountDeletion = NoopDeletion(),
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    /** Alice signs in through the manager and writes a note the cloud has not seen. */
    private suspend fun aliceWithANoteTheCloudHasNotSeen() {
        assertTrue(manager.signInWithEmailCode("$ALICE@example.com", "123456").isSuccess)
        stateStore.setLastMergedUserId(ALICE)
        repository.insertNoteWithoutSync(
            Note(id = NOTE_ID, title = "Written offline", content = "milk", timestamp = 1_000L, color = 0),
        )
    }

    private suspend fun localTitles() = database.noteDao.getAllNotesForBackup().map { it.note.title }

    @Test
    fun `signing out keeps the unsynced note sealed, and signing back in returns it`() = runBlocking {
        aliceWithANoteTheCloudHasNotSeen()

        assertTrue(manager.signOut(deleteCloudData = false).isSuccess)

        assertEquals(1, session.signOuts)
        assertTrue(manager.cloudAccount.value.isOfflineMode)
        assertTrue(localTitles().isEmpty(), "the signed-out app must not show alice's note")
        val sealed = database.sealedAccountDao.getAll().single()
        assertEquals(ALICE, sealed.ownerUid)
        assertTrue(sealed.armed)

        assertTrue(manager.signInWithEmailCode("$ALICE@example.com", "123456").isSuccess)

        assertEquals(listOf("Written offline"), localTitles())
        assertTrue(database.sealedAccountDao.getAll().isEmpty())
    }

    @Test
    fun `a seal that cannot be written stops the sign-out with nothing lost`() = runBlocking {
        aliceWithANoteTheCloudHasNotSeen()
        sealedStore.failPut = true

        assertTrue(manager.signOut(deleteCloudData = false).isFailure)

        assertEquals(0, session.signOuts, "the session must outlive a seal that failed")
        assertEquals(ALICE, session.uid)
        assertEquals(listOf("Written offline"), localTitles())
        assertTrue(database.sealedAccountDao.getAll().isEmpty())
    }

    @Test
    fun `a session that does not end keeps the library and drops the copy just sealed`() = runBlocking {
        aliceWithANoteTheCloudHasNotSeen()
        session.signOutFailure = IllegalStateException("network down")

        assertTrue(manager.signOut(deleteCloudData = false).isFailure)

        assertEquals(ALICE, session.uid)
        assertEquals(listOf("Written offline"), localTitles())
        assertTrue(
            database.sealedAccountDao.getAll().isEmpty(),
            "still signed in, so the live library is current and a sealed copy would go stale",
        )
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
        const val NOTE_ID = 1_700_000_000_000_001L
    }
}
