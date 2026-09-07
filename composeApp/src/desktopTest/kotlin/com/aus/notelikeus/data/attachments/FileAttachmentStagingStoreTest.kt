package com.aus.notelikeus.data.attachments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The durability invariant behind pending attachments: bytes a saved note references must outlive
 * the process that staged them, and must never cross an account boundary.
 */
class FileAttachmentStagingStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = FileAttachmentStagingStore(
        root = temp.root.absolutePath.toPath(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `staged bytes survive the store instance that wrote them`() = runTest {
        val staged = store().stage("att-1", "owner-1", noteId = null, bytes = bytes, mimeType = "image/png")
        assertNotNull(staged)

        // A completely new instance stands in for the next process: nothing is carried over in
        // memory, so anything readable here came off disk.
        val afterRestart = store()

        assertArrayEquals(bytes, afterRestart.readBytes("att-1", "owner-1"))
        assertEquals("image/png", afterRestart.metadata("att-1", "owner-1")?.mimeType)
        assertEquals(bytes.size.toLong(), afterRestart.metadata("att-1", "owner-1")?.sizeBytes)
    }

    @Test
    fun `note id binds after the insert issues one`() = runTest {
        val subject = store()
        subject.stage("att-2", "owner-1", noteId = null, bytes = bytes, mimeType = "image/png")
        assertNull(subject.metadata("att-2", "owner-1")?.noteId)

        subject.bindNote("att-2", "owner-1", 42L)

        assertEquals(42L, store().metadata("att-2", "owner-1")?.noteId)
    }

    @Test
    fun `one account cannot read another account's staged bytes`() = runTest {
        val subject = store()
        subject.stage("att-3", "owner-a", noteId = 1L, bytes = bytes, mimeType = "image/png")

        assertNull(subject.readBytes("att-3", "owner-b"))
        assertNull(subject.metadata("att-3", "owner-b"))
        assertTrue(subject.list("owner-b").isEmpty())
        assertEquals(1, subject.list("owner-a").size)
    }

    @Test
    fun `releasing one owner's copy leaves another owner's untouched`() = runTest {
        val subject = store()
        subject.stage("att-4", "owner-a", noteId = 1L, bytes = bytes, mimeType = "image/png")
        subject.stage("att-4", "owner-b", noteId = 1L, bytes = bytes, mimeType = "image/png")

        subject.release("att-4", "owner-a")

        assertNull(subject.readBytes("att-4", "owner-a"))
        assertArrayEquals(bytes, subject.readBytes("att-4", "owner-b"))
    }

    @Test
    fun `ids that are not plain identifiers are refused rather than sanitised`() = runTest {
        val subject = store()

        // Nothing may be written outside the root, and a crafted id must not resolve to a
        // different-but-valid path either.
        assertNull(subject.stage("../escape", "owner-a", null, bytes, "image/png"))
        assertNull(subject.stage("att-5", "../escape", null, bytes, "image/png"))
        assertNull(subject.stage("with/slash", "owner-a", null, bytes, "image/png"))
        assertNull(subject.readBytes("../escape", "owner-a"))
    }

    @Test
    fun `list reports only entries whose bytes are actually present`() = runTest {
        val subject = store()
        subject.stage("att-6", "owner-a", noteId = 7L, bytes = bytes, mimeType = "image/png")
        assertEquals(1, subject.list("owner-a").size)

        subject.release("att-6", "owner-a")

        assertTrue(subject.list("owner-a").isEmpty())
    }

    @Test
    fun `restaging the same id replaces its bytes without duplicating the entry`() = runTest {
        val subject = store()
        subject.stage("att-7", "owner-a", noteId = 1L, bytes = bytes, mimeType = "image/png")
        val replacement = byteArrayOf(9, 9, 9)

        subject.stage("att-7", "owner-a", noteId = 1L, bytes = replacement, mimeType = "image/jpeg")

        assertEquals(1, subject.list("owner-a").size)
        assertArrayEquals(replacement, subject.readBytes("att-7", "owner-a"))
        assertEquals("image/jpeg", subject.metadata("att-7", "owner-a")?.mimeType)
    }
}
