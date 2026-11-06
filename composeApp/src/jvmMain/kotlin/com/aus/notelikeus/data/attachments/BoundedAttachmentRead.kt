package com.aus.notelikeus.data.attachments

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most the attachment limit plus one byte, without trusting file/provider metadata. */
fun readBoundedAttachment(input: InputStream, maxBytes: Int = MAX_ATTACHMENT_BYTES): ByteArray? {
    require(maxBytes in 0 until Int.MAX_VALUE)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = readChunk(input, buffer, minOf(buffer.size, maxBytes + 1 - total))
        if (count < 0) break
        total += count
        if (total > maxBytes) return null
        output.write(buffer, 0, count)
    }
    return if (total == 0) null else output.toByteArray()
}

private fun readChunk(input: InputStream, buffer: ByteArray, length: Int): Int {
    val count = input.read(buffer, 0, length)
    return if (count != 0) count else {
        val next = input.read()
        if (next < 0) -1 else {
            buffer[0] = next.toByte()
            1
        }
    }
}
