package com.aus.notelikeus.platform

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.remote.AccountDeletionTransport
import com.aus.notelikeus.data.remote.CloudSessionAccount
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.remote.SupabaseTransportException
import com.aus.notelikeus.data.sync.CloudWipeCoordinator
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.FaultInjectingTransport
import com.aus.notelikeus.data.sync.InMemoryPendingCloudWipeIntentStore
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.RecordingSyncCoordinator
import com.aus.notelikeus.data.sync.testRemoteIdentityProvider
import com.aus.notelikeus.ui.main.CloudSyncEvent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Local notes go exactly when the server definitely said the account is gone, and at no other time.
 * Erasing on a network failure or an ended session would destroy the only copy of a live account's edits.
 *
 * On a real dispatcher: the sync timeout is measured in virtual time under `runTest`, while Room does
 * real I/O, so the timeout would fire before the sync got anywhere.
 */
class DesktopSyncManagerAccountDeletionTest {
    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase
    private lateinit var transport: FaultInjectingTransport
    private lateinit var manager: DesktopSyncManager

    private val session = FakeSession()
    private val deletion = FakeDeletion()
    private var isolations = 0

    private class FakeSession : CloudSessionManager {
        var signOuts = 0
        var uid = UID
        override fun getCurrentAccount() =
            CloudSessionAccount(uid.takeIf { signOuts == 0 }, "a@example.com", signOuts == 0, false)
        override suspend fun requestEmailCode(email: String) = Result.success(Unit)
        override suspend fun signInWithEmailCode(email: String, code: String) = Result.success(Unit)
        override suspend fun signInWithEmailPassword(email: String, password: String, createAccount: Boolean) =
            Result.success(Unit)
        override suspend fun signOut(): Result<Unit> {
            signOuts++
            return Result.success(Unit)
        }
        override suspend fun ensureSignedIn() = Result.success(UID)
        override fun diagnose(error: Throwable) = error.message.orEmpty()
    }

    private class FakeDeletion : AccountDeletionTransport {
        var failure: Throwable? = null
        var calls = 0
        var beforeReply: suspend () -> Unit = {}
        override suspend fun deleteAccount() {
            calls++
            beforeReply()
            failure?.let { throw it }
        }
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-account-delete-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(name = File(tempDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        val cloud = FakeCloudNoteTransport()
        transport = FaultInjectingTransport { cloud }
        val stateStore = FakeNoteSyncStateStore().apply { setLastMergedUserId(UID) }
        val engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { UID },
            noteDao = database.noteDao,
            labelDao = database.labelDao,
            syncStateStore = stateStore,
            uidProvider = { Result.success(UID) },
            platform = "desktop",
            runInTransaction = { block ->
                database.useWriterConnection { tx -> tx.immediateTransaction { block() } }
            },
        )
        manager = DesktopSyncManager(
            syncEngine = engine,
            sessionManager = session,
            isolator = LocalAccountIsolator(
                FakeNoteRepository(),
                stateStore,
                RecordingSyncCoordinator(),
                clearDatasetScopedInMemoryState = { isolations++ },
            ),
            cloudWipeCoordinator = CloudWipeCoordinator(InMemoryPendingCloudWipeIntentStore()),
            accountDeletion = deletion,
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `an accepted deletion ends the session and erases the local copy`() = runBlocking {
        assertTrue(manager.deleteAccount().isSuccess)

        assertEquals(1, deletion.calls)
        assertEquals(1, session.signOuts)
        assertEquals(1, isolations)
    }

    @Test
    fun `late account deletion does not sign out or isolate replacement session`() = runBlocking {
        deletion.beforeReply = { session.uid = "replacement-account" }
        assertTrue(manager.deleteAccount().isSuccess)
        assertEquals(0, session.signOuts)
        assertEquals(0, isolations)
        assertEquals("replacement-account", session.getCurrentAccount().userId)
    }

    @Test
    fun `late deletion cannot isolate a newer dataset for the same account`() = runBlocking {
        deletion.beforeReply = { com.aus.notelikeus.data.sync.LocalCommitGate.isolate { Unit } }
        assertTrue(manager.deleteAccount().isSuccess)
        assertEquals(0, session.signOuts)
        assertEquals(0, isolations)
    }

    @Test
    fun `a refused deletion changes nothing on this device`() = runBlocking {
        deletion.failure = SupabaseTransportException("attachments", 503, "not available")

        assertTrue(manager.deleteAccount().isFailure)

        assertEquals(0, session.signOuts)
        assertEquals(0, isolations)
    }

    @Test
    fun `a pull told the account is gone erases the local copy and says so`() = runBlocking {
        transport.failOnce(
            "fetchNotes",
            SupabaseTransportException(
                "fetch_full_snapshot",
                400,
                """{"code":"NL003","message":"account_deleted: this Notelikeus account was deleted."}""",
            ),
        )

        manager.downloadNotes()

        assertTrue("fetchNotes" in transport.calls, "${manager.pendingEvent.value}")
        assertEquals(1, isolations)
        assertEquals(1, session.signOuts)
        assertEquals(CloudSyncEvent.AccountDeleted(elsewhere = true), manager.pendingEvent.value)
    }

    @Test
    fun `an ordinary sync failure erases nothing`() = runBlocking {
        listOf(
            RuntimeException("Unable to resolve host"),
            SupabaseTransportException("fetch_full_snapshot", 401, "JWT expired"),
            SupabaseTransportException("fetch_full_snapshot", 503, "upstream"),
        ).forEach { failure ->
            transport.failOnce("fetchNotes", failure)

            transport.calls.clear()
            manager.downloadNotes()

            assertTrue("fetchNotes" in transport.calls, "never reached the snapshot: ${manager.pendingEvent.value}")
            assertEquals(0, isolations, "$failure")
            assertEquals(0, session.signOuts, "$failure")
            assertIs<CloudSyncEvent.Failure>(manager.pendingEvent.value)
            assertNotEquals(CloudSyncEvent.AccountDeleted(elsewhere = true), manager.pendingEvent.value)
        }
    }

    private companion object {
        const val UID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
