package com.aus.notelikeus.data.backup.bundle

import java.io.File
import java.io.InputStream

/**
 * Bounded reads of a user-chosen backup file, shared by the Android and desktop pickers.
 *
 * Each one enforces its limit while reading, never after: a picker can hand back any file at all,
 * and checking the size of a buffer that is already in memory is too late to avoid running out of it.
 */
object BackupFileReads {

    /** Up to [count] leading bytes, enough to tell a bundle from a JSON backup. */
    fun readHead(input: InputStream, count: Int = 2): ByteArray = input.readNBytes(count)

    /** The whole stream as UTF-8 text, or null when it is longer than [maxChars] characters. */
    fun readTextWithin(input: InputStream, maxChars: Int): String? {
        val reader = input.reader(Charsets.UTF_8)
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        val text = StringBuilder()
        while (true) {
            val read = reader.read(buffer)
            if (read <= 0) break
            if (text.length + read > maxChars) return null
            text.appendRange(buffer, 0, read)
        }
        return text.toString()
    }

    /**
     * Streams [input] into [target], or returns false — leaving no [target] behind — when more than
     * [maxBytes] arrive or the copy fails.
     */
    fun copyWithin(input: InputStream, target: File, maxBytes: Long): Boolean {
        val copied = runCatching {
            target.outputStream().buffered().use { output ->
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) return@use false
                    output.write(chunk, 0, read)
                }
                true
            }
        }.getOrDefault(false)
        if (!copied) target.delete()
        return copied
    }
}
