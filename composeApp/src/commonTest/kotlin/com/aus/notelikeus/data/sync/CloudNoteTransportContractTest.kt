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
