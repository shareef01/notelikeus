package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.backup.NoteBackupImporter
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

    @Test
    fun `multibyte UTF-8 over byte limit is refused even when character count is below limit`() {
        // 20,000 3-byte CJK chars = 60,000 UTF-8 bytes
        val cjkText = "漢".repeat(20_000)
        val bytes = cjkText.toByteArray(Charsets.UTF_8)
        assertEquals(60_000, bytes.size)

        // With maxBytes = 50_000, must be refused because 60,000 > 50,000 bytes
        assertNull(BackupFileReads.readTextWithin(ByteArrayInputStream(bytes), maxBytes = 50_000L))
    }

    @Test
    fun `multibyte emojis over byte limit are refused`() {
        // 15,000 4-byte emoji chars = 60,000 UTF-8 bytes
        val emojiText = "🎉".repeat(15_000)
        val bytes = emojiText.toByteArray(Charsets.UTF_8)
        assertEquals(60_000, bytes.size)

        assertNull(BackupFileReads.readTextWithin(ByteArrayInputStream(bytes), maxBytes = 50_000L))
    }

    @Test
    fun `exact byte boundary is respected`() {
        val exact = "a".repeat(50_000)
        assertEquals(exact, BackupFileReads.readTextWithin(ByteArrayInputStream(exact.toByteArray()), 50_000L))

        val oneByteOver = "a".repeat(50_001)
        assertNull(BackupFileReads.readTextWithin(ByteArrayInputStream(oneByteOver.toByteArray()), 50_000L))
    }


    @Test
    fun `copyWithin handles 40 MiB bundle stream cleanly without 25 MiB restriction`() {
        val fortyMb = 40L * 1024 * 1024
        val stream = FiniteByteStream(fortyMb)
        val copied = BackupFileReads.copyWithin(stream, target, maxBytes = BackupBundleLimits.MAX_BUNDLE_FILE_BYTES)
        assertTrue(copied, "40 MiB bundle stream must copy cleanly under 256 MiB limit")
        assertEquals(fortyMb, target.length())
    }

    @Test
    fun `readTextWithin accepts 25_500_000 ASCII chars within 25 MiB limit`() {
        val totalBytes = 25_500_000L
        val stream = FiniteByteStream(totalBytes, fillByte = 'a'.code.toByte())
        val text = BackupFileReads.readTextWithin(stream, NoteBackupImporter.MAX_BACKUP_BYTES)
        assertEquals(totalBytes.toInt(), text?.length)
    }

    @Test
    fun `readTextWithin rejects stream one byte over 25 MiB limit`() {
        val totalBytes = NoteBackupImporter.MAX_BACKUP_BYTES + 1L
        val stream = FiniteByteStream(totalBytes, fillByte = 'a'.code.toByte())
        val text = BackupFileReads.readTextWithin(stream, NoteBackupImporter.MAX_BACKUP_BYTES)
        assertNull(text)
    }

    private class FiniteByteStream(private val total: Long, private val fillByte: Byte = 0) : InputStream() {
        private var served = 0L
        override fun read(): Int {
            if (served >= total) return -1
            served++
            return fillByte.toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= total) return -1
            val toRead = minOf(len.toLong(), total - served).toInt()
            b.fill(fillByte, off, off + toRead)
            served += toRead
            return toRead
        }
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
