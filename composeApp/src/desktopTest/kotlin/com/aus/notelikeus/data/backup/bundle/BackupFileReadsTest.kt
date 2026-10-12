package com.aus.notelikeus.data.backup.bundle

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-07: the picker reads stop at their limit instead of buffering whatever the user picked. */
class BackupFileReadsTest {

    private val target = File.createTempFile("backup-file-reads", ".nlkbak")

    @AfterTest
    fun cleanUp() {
        target.delete()
    }

    @Test
    fun `copy within the limit lands intact`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }

        assertTrue(BackupFileReads.copyWithin(ByteArrayInputStream(bytes), target, maxBytes = 200_000))
        assertContentEquals(bytes, target.readBytes())
    }

    @Test
    fun `copy over the limit stops early and leaves no file`() {
        val endless = CountingZeros()

        assertFalse(BackupFileReads.copyWithin(endless, target, maxBytes = 1_000_000))
        assertFalse(target.exists(), "a partial copy was left behind")
        assertTrue(endless.served < 1_000_000 + 2 * DEFAULT_BUFFER_SIZE, "read far past the limit")
    }

    @Test
    fun `text over the limit is refused rather than decoded whole`() {
        val endless = CountingZeros()

        assertNull(BackupFileReads.readTextWithin(endless, maxChars = 50_000))
        assertTrue(endless.served < 50_000 + 4 * DEFAULT_BUFFER_SIZE, "read far past the limit")
    }

    @Test
    fun `text at the limit is read`() {
        val text = "x".repeat(50_000)

        assertEquals(text, BackupFileReads.readTextWithin(ByteArrayInputStream(text.toByteArray()), 50_000))
    }

    @Test
    fun `head is just the first bytes`() {
        assertContentEquals(byteArrayOf(0x50, 0x4b), BackupFileReads.readHead(CountingZeros(0x50, 0x4b)))
    }

    /** A stream that never ends, counting what it hands out. Starts with [prefix]. */
    private class CountingZeros(vararg prefix: Int) : InputStream() {
        private val head = prefix.map { it.toByte() }
        var served = 0L

        override fun read(): Int {
            val value = head.getOrNull(served.toInt())?.toInt() ?: 'a'.code
            served++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            for (i in 0 until len) b[off + i] = read().toByte()
            return len
        }
    }
}
