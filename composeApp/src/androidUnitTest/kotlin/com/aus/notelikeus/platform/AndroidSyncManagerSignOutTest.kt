package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.AccountDeletionTransport
import com.aus.notelikeus.data.remote.CloudSessionAccount
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.sync.CloudWipeCoordinator
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.InMemoryPendingCloudWipeIntentStore
import com.aus.notelikeus.data.sync.InMemorySealedAccountStore
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.RecordingSyncCoordinator
import com.aus.notelikeus.data.sync.SealedAccount
import com.aus.notelikeus.data.sync.SealedAccountStore
import com.aus.notelikeus.data.sync.SignedOutAccountVault
import com.aus.notelikeus.data.sync.testRemoteIdentityProvider
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.UnsyncedWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NL-03 through [AndroidSyncManager.signOut] itself: seal the unsynced work, end the session,
 * isolate, and drop the seal again if the session does not end.
 *
 * `LocalAccountIsolatorTest` covers the isolator and the vault by repeating the sign-out's steps by
 * hand, and `DesktopSyncManagerSignOutTest` drives the desktop manager over a real database. This is
 * the Android manager's own order, over the shared in-memory fakes: Room cannot open a database in a
 * JVM unit test here, and the manager uses no Android API, so no Robolectric is needed either.
 */
class AndroidSyncManagerSignOutTest {

    private val session = FakeSession()
    private val repository = FakeNoteRepository()
    private val stateStore = FakeNoteSyncStateStore()
    private val sealed = FailableSealedStore(InMemorySealedAccountStore())
    private val manager = AndroidSyncManager(
        sessionManager = session,
        syncEngine = NoteSyncEngine(
            transport = FakeCloudNoteTransport(),
            remoteIdentityProvider = testRemoteIdentityProvider { session.uid },
            noteDao = FakeNoteDao(),
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = { session.ensureSignedIn() },
        ),
        isolator = LocalAccountIsolator(
            repository,
            stateStore,
            RecordingSyncCoordinator(),
            // What NoteSyncEngine.unsyncedWork would report: one edit the cloud has not seen.
            signedOutAccounts = SignedOutAccountVault(repository, stateStore, sealed) { UnsyncedWork(edits = 1) },
        ),
        cloudWipeCoordinator = CloudWipeCoordinator(InMemoryPendingCloudWipeIntentStore()),
        accountDeletion = object : AccountDeletionTransport {
            override suspend fun deleteAccount() = Unit
        },
    )

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

    /** The sealed-accounts store, with a switch that makes the seal fail to write. */
    private class FailableSealedStore(private val real: SealedAccountStore) : SealedAccountStore by real {
        var failPut = false
        override suspend fun put(account: SealedAccount) {
            if (failPut) error("disk full")
            real.put(account)
        }
    }

    /** Alice signs in through the manager and has a note the cloud has not seen. */
    private suspend fun aliceWithAnUnsyncedNote() {
        assertTrue(manager.signInWithEmailCode("$ALICE@example.com", "123456").isSuccess)
        stateStore.setLastMergedUserId(ALICE)
        repository.addNote(Note(id = 1L, title = "Written offline", content = "milk", timestamp = 1_000L, color = 0))
    }

    private suspend fun titles() = repository.getAllNotesForBackup().map { it.title }

    @Test
    fun `signing out keeps the unsynced note sealed, and signing back in returns it`() = runTest {
        aliceWithAnUnsyncedNote()

        assertTrue(manager.signOut(deleteCloudData = false).isSuccess)

        assertEquals(1, session.signOuts)
        assertFalse(manager.cloudAccount.value.isSignedIn)
        assertTrue(titles().isEmpty(), "the signed-out app must not show alice's note")
        val seal = sealed.all().single()
        assertEquals(ALICE, seal.ownerUid)
        assertTrue(seal.armed)

        assertTrue(manager.signInWithEmailCode("$ALICE@example.com", "123456").isSuccess)

        assertEquals(listOf("Written offline"), titles())
        assertTrue(sealed.all().isEmpty())
    }

    @Test
    fun `a seal that cannot be written stops the sign-out with nothing lost`() = runTest {
        aliceWithAnUnsyncedNote()
        sealed.failPut = true

        assertTrue(manager.signOut(deleteCloudData = false).isFailure)

        assertEquals(0, session.signOuts, "the session must outlive a seal that failed")
        assertEquals(ALICE, session.uid)
        assertEquals(listOf("Written offline"), titles())
        assertTrue(sealed.all().isEmpty())
    }

    @Test
    fun `a session that does not end keeps the library and drops the copy just sealed`() = runTest {
        aliceWithAnUnsyncedNote()
        session.signOutFailure = IllegalStateException("network down")

        assertTrue(manager.signOut(deleteCloudData = false).isFailure)

        assertEquals(ALICE, session.uid)
        assertEquals(listOf("Written offline"), titles())
        assertTrue(
            sealed.all().isEmpty(),
            "still signed in, so the live library is current and a sealed copy would go stale",
        )
    }

    private companion object {
        const val ALICE = "alice"
    }
}
