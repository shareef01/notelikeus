package com.aus.notelikeus.data.attachments

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedAttachmentReadTest {
    @Test
    fun zeroLengthProviderReadsStillMakeBoundedProgress() {
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertContentEquals(byteArrayOf(1, 2, 3), readBoundedAttachment(stream, 3))
    }

    @Test
    fun readsExactLimitAndRejectsEmptyOrOversizedStreams() {
        assertContentEquals(byteArrayOf(1, 2, 3), readBoundedAttachment(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        assertNull(readBoundedAttachment(ByteArrayInputStream(byteArrayOf()), 3))
        assertNull(readBoundedAttachment(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3))
    }

    @Test
    fun oversizedStreamStopsAfterOneByteBeyondLimit() {
        var read = 0
        val unlimited = object : InputStream() {
            override fun read(): Int {
                read++
                check(read <= 33) { "Reader exceeded the allocation bound" }
                return 42
            }
        }
        assertNull(readBoundedAttachment(unlimited, 32))
        assertEquals(33, read)
    }
}
