package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared behavioral contract for [CloudNoteTransport] implementations.
 *
 * Runs against [FakeCloudNoteTransport] today. Future Phase 4 should add an emulator-backed
 * subclass for a fake transport and the live Supabase adapter.
 */
abstract class CloudNoteTransportContractTest {

    abstract fun createTransport(): CloudNoteTransport

    private fun sampleNote(id: Long) = Note(
        id = id,
        title = "Title $id",
        content = "Body $id",
        timestamp = 1_000L + id,
        color = 0xFF1A1A1A.toInt(),
        position = id.toInt(),
    )

    @Test
    fun putNotes_roundTripsThroughFetchNotes() = runTest {
        val transport = createTransport()
        val uid = "user-contract"

        transport.putNotes(uid, listOf(sampleNote(1), sampleNote(2)))
        val fetched = transport.fetchNotes(uid).associateBy { it.noteId }

        assertEquals(2, fetched.size)
        assertEquals("Title 1", fetched[1]?.title)
        assertEquals("Body 2", fetched[2]?.content)
        assertNotNull(fetched[1]?.serverUpdatedAt)
    }

    @Test
    fun fetchNote_returnsSingleDocument() = runTest {
        val transport = createTransport()
        val uid = "user-contract"

        transport.putNotes(uid, listOf(sampleNote(42)))
        val record = transport.fetchNote(uid, 42)

        assertNotNull(record)
        assertEquals(42, record.noteId)
        assertEquals("Title 42", record.title)
    }

    @Test
    fun deleteNotes_removesDocuments() = runTest {
        val transport = createTransport()
        val uid = "user-contract"

        transport.putNotes(uid, listOf(sampleNote(1), sampleNote(2)))
        transport.deleteNotes(uid, listOf(1))

        assertNull(transport.fetchNote(uid, 1))
        assertNotNull(transport.fetchNote(uid, 2))
    }

    @Test
    fun tombstones_roundTripAndDelete() = runTest {
        val transport = createTransport()
        val uid = "user-contract"

        transport.writeTombstone(uid, 7, 9_999)
        assertEquals(9_999, transport.fetchTombstone(uid, 7))
        assertEquals(mapOf(7L to 9_999L), transport.fetchTombstones(uid))

        transport.deleteTombstones(uid, listOf(7))
        assertNull(transport.fetchTombstone(uid, 7))
        assertTrue(transport.fetchTombstones(uid).isEmpty())
    }

    /**
     * The completeness proof the engine reconciles against.
     *
     * A transport may legitimately answer `null` — it cannot all prove completeness — but a
     * transport that *does* report a count must report the server's own, and it must agree with
     * the records it delivered. A count recomputed from `records.size` would satisfy this
     * assertion while proving nothing, so the fake is asked to drop a record and the count is
     * expected to stay put.
     */
    @Test
    fun fetchNotesSnapshot_carriesRecordsAndAnyAuthoritativeCount() = runTest {
        val transport = createTransport()
        val uid = "user-contract"

        transport.putNotes(uid, listOf(sampleNote(1), sampleNote(2)))
        val snapshot = transport.fetchNotesSnapshot(uid)

        assertEquals(
            transport.fetchNotes(uid).map { it.noteId }.toSet(),
            snapshot.records.map { it.noteId }.toSet(),
            "the snapshot must carry the same records fetchNotes returns",
        )
        val count = snapshot.authoritativeNoteCount
        if (count != null) {
            assertEquals(snapshot.records.size, count, "a complete read must agree with its count")
        }
    }

    @Test
    fun fetchNotesSnapshot_reportsTheServerCountEvenWhenRecordsAreLost() = runTest {
        val fake = createTransport() as? FakeCloudNoteTransport ?: return@runTest
        val uid = "user-contract"

        fake.putNotes(uid, listOf(sampleNote(1), sampleNote(2), sampleNote(3)))
        fake.truncateSnapshotBy = 1
        val snapshot = fake.fetchNotesSnapshot(uid)

        assertEquals(2, snapshot.records.size)
        assertEquals(
            3,
            snapshot.authoritativeNoteCount,
            "the count must come from the server, not from the records that arrived",
        )
    }

    @Test
    fun syncMeta_writesAndDeletes() = runTest {
        val transport = createTransport()
        val uid = "user-contract"

        transport.writeSyncMeta(uid, noteCount = 3, platform = "test")
        transport.deleteSyncMeta(uid)

        val fake = transport as? FakeCloudNoteTransport
        if (fake != null) {
            assertEquals(listOf(Triple(uid, 3, "test")), fake.syncMetaCalls)
            assertTrue(fake.deleteSyncMetaCalled)
        }
    }
}

class FakeCloudNoteTransportContractTest : CloudNoteTransportContractTest() {
    override fun createTransport(): CloudNoteTransport = FakeCloudNoteTransport()
}
