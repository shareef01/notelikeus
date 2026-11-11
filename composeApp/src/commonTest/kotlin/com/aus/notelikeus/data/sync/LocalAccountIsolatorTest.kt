package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.UnsyncedWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAccountIsolatorTest {

    /**
     * Isolating wipes the notes and the staged bytes from disk. The in-memory image cache holds
     * decrypted picture bytes and has to go with them, or a session's images outlive the session.
     * The clear existed, documented as doing exactly this, and nothing called it.
     */
    @Test
    fun `isolating drops the cached attachment bytes`() = runTest {
        var cleared = 0
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            FakeNoteSyncStateStore(),
            RecordingSyncCoordinator(),
            clearDatasetScopedInMemoryState = { cleared++ },
        )

        isolator.isolate()

        assertEquals(1, cleared)
    }

    @Test
    fun `a different account signing in also drops the previous session's cached bytes`() = runTest {
        val stateStore = FakeNoteSyncStateStore()
        stateStore.setLastMergedUserId("alice")
        var cleared = 0
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            stateStore,
            RecordingSyncCoordinator(),
            clearDatasetScopedInMemoryState = { cleared++ },
        )

        isolator.isolateIfAccountChanged("bob")

        assertEquals(1, cleared)
    }

    /**
     * A picture attached before signing in must survive signing in.
     *
     * Staged attachment bytes are filed under an owner namespace, so anything staged while signed
     * out sits under the guest one. `AttachmentSyncService.adoptGuestStagedAttachments` exists to
     * move them across at sign-in — and for months nothing called it, from either platform. The
     * upload path then looked only under the new account, found nothing, and left the note pointing
     * at a picture that could never arrive: a permanent broken image, no error, no request ever
     * made for it, and no amount of re-syncing would fix it.
     */
    @Test
    fun `a first sign-in adopts what the guest session staged`() = runTest {
        val adopted = mutableListOf<String>()
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            FakeNoteSyncStateStore(),
            RecordingSyncCoordinator(),
            adoptGuestStagedAttachments = { uid -> adopted.add(uid) },
        )

        isolator.isolateIfAccountChanged("bob")

        assertEquals(listOf("bob"), adopted)
    }

    @Test
    fun `signing in again as the same account still adopts anything left staged`() = runTest {
        val stateStore = FakeNoteSyncStateStore()
        stateStore.setLastMergedUserId("alice")
        val adopted = mutableListOf<String>()
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            stateStore,
            RecordingSyncCoordinator(),
            adoptGuestStagedAttachments = { uid -> adopted.add(uid) },
        )

        isolator.isolateIfAccountChanged("alice")

        assertEquals(listOf("alice"), adopted)
    }

    /**
     * The mirror case, and the one that would be a privacy bug: a different account signing in
     * wipes the device, and must not inherit the previous session's staged pictures either.
     */
    @Test
    fun `a different account does not adopt the previous session's staged bytes`() = runTest {
        val stateStore = FakeNoteSyncStateStore()
        stateStore.setLastMergedUserId("alice")
        val adopted = mutableListOf<String>()
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            stateStore,
            RecordingSyncCoordinator(),
            adoptGuestStagedAttachments = { uid -> adopted.add(uid) },
        )

        isolator.isolateIfAccountChanged("bob")

        assertTrue(adopted.isEmpty(), "isolating wipes the device; staged bytes must not follow")
    }

    @Test
    fun `isolate clears notes, sync state, and the pending queue`() = runTest {
        val repository = FakeNoteRepository()
        val stateStore = FakeNoteSyncStateStore()
        val coordinator = RecordingSyncCoordinator()
        repository.addNote(Note(id = 1L, title = "Alice", content = "", timestamp = 1L, color = 0))
        stateStore.setLastMergedUserId("alice")
        stateStore.setKnownCloudIds(setOf(1L))
        stateStore.markDeleted(2L, 99L)

        val isolator = LocalAccountIsolator(repository, stateStore, coordinator)
        isolator.isolate()

        assertEquals(0, repository.getCloudEligibleNoteCount())
        assertTrue(repository.clearedAll.isNotEmpty())
        assertNull(stateStore.lastMergedUserId())
        assertTrue(stateStore.knownCloudIds().isEmpty())
        assertTrue(stateStore.deletedIds().isEmpty())
        assertEquals(1, coordinator.clearPendingCount)
    }

    @Test
    fun `isolateIfAccountChanged is a no-op on a first sign-in`() = runTest {
        val repository = FakeNoteRepository()
        repository.addNote(Note(id = 1L, title = "Guest", content = "", timestamp = 1L, color = 0))
        val isolator = LocalAccountIsolator(
            repository,
            FakeNoteSyncStateStore(),
            RecordingSyncCoordinator(),
        )

        isolator.isolateIfAccountChanged("bob")

        assertEquals(1, repository.getCloudEligibleNoteCount())
        assertTrue(repository.clearedAll.isEmpty())
    }

    @Test
    fun `isolateIfAccountChanged is a no-op when the same account signs in again`() = runTest {
        val repository = FakeNoteRepository()
        val stateStore = FakeNoteSyncStateStore()
        repository.addNote(Note(id = 1L, title = "Mine", content = "", timestamp = 1L, color = 0))
        stateStore.setLastMergedUserId("alice")
        val isolator = LocalAccountIsolator(repository, stateStore, RecordingSyncCoordinator())

        isolator.isolateIfAccountChanged("alice")

        assertEquals(1, repository.getCloudEligibleNoteCount())
        assertEquals("alice", stateStore.lastMergedUserId())
    }

    @Test
    fun `isolateIfAccountChanged wipes when a different account signs in`() = runTest {
        val repository = FakeNoteRepository()
        val stateStore = FakeNoteSyncStateStore()
        val coordinator = RecordingSyncCoordinator()
        repository.addNote(Note(id = 1L, title = "Alice", content = "", timestamp = 1L, color = 0))
        stateStore.setLastMergedUserId("alice")
        stateStore.setKnownCloudIds(setOf(1L))
        val isolator = LocalAccountIsolator(repository, stateStore, coordinator)

        isolator.isolateIfAccountChanged("bob")

        assertEquals(0, repository.getCloudEligibleNoteCount())
        assertNull(stateStore.lastMergedUserId())
        assertEquals(1, coordinator.clearPendingCount)
    }

    // ---- NL-03: unsynced work is sealed for its account, not wiped ----

    /**
     * One device, with the vault wired in. [unsynced] is what `NoteSyncEngine.unsyncedWork` would
     * report; the engine itself is exercised by the desktop test over a real database.
     */
    private class Device {
        val repository = FakeNoteRepository()
        val stateStore = FakeNoteSyncStateStore()
        val sealed = InMemorySealedAccountStore()
        var unsynced = UnsyncedWork.NONE
        val isolator = LocalAccountIsolator(
            repository,
            stateStore,
            RecordingSyncCoordinator(),
            signedOutAccounts = SignedOutAccountVault(repository, stateStore, sealed) { unsynced },
        )

        /** What the sync managers do on an ordinary sign-out. */
        suspend fun signOut(uid: String) {
            isolator.sealUnsyncedWork(uid)
            isolator.isolate(sealFor = uid)
        }

        suspend fun signIn(uid: String) = isolator.isolateIfAccountChanged(uid)

        suspend fun titles() = repository.getAllNotesForBackup().map { it.title }.sorted()
    }

    /** Alice, signed in, with one synced note and one edit the cloud has not seen. */
    private suspend fun Device.aliceWithAnUnsyncedEdit() {
        repository.insertLabel(Label(name = "Work"))
        repository.addNote(Note(id = 1L, title = "Synced", content = "", timestamp = 10L, color = 0))
        repository.addNote(
            Note(
                id = 2L, title = "Edited offline", content = "", timestamp = 25L, color = 0,
                labels = listOf(Label(id = 1L, name = "Work")),
            ),
        )
        stateStore.setLastMergedUserId("alice")
        stateStore.setKnownCloudIds(setOf(1L, 2L))
        stateStore.updateSyncedTimestamps(mapOf(1L to 10L, 2L to 20L))
        stateStore.updateKnownServerRevisions(mapOf(1L to 3L, 2L to 5L))
        unsynced = UnsyncedWork(edits = 1)
    }

    @Test
    fun `signing out with unsynced edits wipes the device but seals the library for that account`() = runTest {
        val device = Device()
        device.aliceWithAnUnsyncedEdit()

        device.signOut("alice")

        assertTrue(device.titles().isEmpty(), "the next person at the device must see nothing")
        assertNull(device.stateStore.lastMergedUserId())
        val sealed = device.sealed.all().single()
        assertEquals("alice", sealed.ownerUid)
        assertTrue(sealed.armed, "the wipe has run, so the sealed copy is now the only one")
    }

    @Test
    fun `the same account signing back in gets its unsynced edits back, ready to upload`() = runTest {
        val device = Device()
        device.aliceWithAnUnsyncedEdit()
        device.signOut("alice")

        device.signIn("alice")

        assertEquals(listOf("Edited offline", "Synced"), device.titles())
        val edited = device.repository.getNoteById(2L)!!
        assertEquals(25L, edited.timestamp, "the edit comes back as it was, so it still reads as unsynced")
        assertEquals(listOf("Work"), edited.labels.map { it.name })
        // The sync state comes back with it: the engine sees one edit against the revision it was based
        // on, not a library it has never seen.
        assertEquals(mapOf(1L to 10L, 2L to 20L), device.stateStore.syncedTimestampById())
        assertEquals(mapOf(1L to 3L, 2L to 5L), device.stateStore.knownServerRevisionById())
        assertEquals("alice", device.stateStore.lastMergedUserId())
        assertEquals(listOf(1L, 2L), device.repository.finalizedIds.sorted(), "uploads are queued")
        assertTrue(device.sealed.all().isEmpty(), "restored once, then gone")
    }

    @Test
    fun `a different account signing in after a fully synced sign-out inherits nothing`() = runTest {
        val device = Device()
        device.aliceWithAnUnsyncedEdit()
        device.unsynced = UnsyncedWork.NONE

        device.signOut("alice")
        device.signIn("bob")

        assertTrue(device.titles().isEmpty())
        assertTrue(device.sealed.all().isEmpty(), "everything was in the cloud, so nothing is kept")
        assertTrue(device.repository.finalizedIds.isEmpty())
    }

    @Test
    fun `a different account cannot reach the previous account's sealed edits, which wait for it`() = runTest {
        val device = Device()
        device.aliceWithAnUnsyncedEdit()
        device.signOut("alice")

        device.signIn("bob")

        assertTrue(device.titles().isEmpty(), "bob must not inherit alice's notes")
        assertTrue(device.stateStore.syncedTimestampById().isEmpty(), "nor her sync state")
        assertEquals(listOf("alice"), device.sealed.all().map { it.ownerUid })

        // Bob leaves with nothing of his own unsynced, and alice comes back.
        device.unsynced = UnsyncedWork.NONE
        device.signOut("bob")
        device.signIn("alice")

        assertEquals(listOf("Edited offline", "Synced"), device.titles())
    }

    @Test
    fun `an account replaced without signing out has its unsynced edits sealed too`() = runTest {
        val device = Device()
        device.aliceWithAnUnsyncedEdit()

        device.signIn("bob")

        assertTrue(device.titles().isEmpty())
        assertEquals(listOf("alice"), device.sealed.all().map { it.ownerUid })
    }

    /**
     * A sign-out that sealed and then failed to end the session never crossed the boundary, so the
     * live library is newer than the sealed copy. Restoring it would put old versions back.
     */
    @Test
    fun `a sealed copy from a sign-out that never happened is not restored`() = runTest {
        val device = Device()
        device.aliceWithAnUnsyncedEdit()
        device.isolator.sealUnsyncedWork("alice")
        device.repository.updateNote(device.repository.getNoteById(2L)!!.copy(title = "Edited again", timestamp = 30L))

        device.signIn("alice")

        assertEquals(30L, device.repository.getNoteById(2L)!!.timestamp)
        assertFalse(device.sealed.all().single().armed)
    }
}
