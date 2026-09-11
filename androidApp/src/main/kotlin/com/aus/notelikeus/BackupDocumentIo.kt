package com.aus.notelikeus

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Reading and writing the backup document the user picked through the Storage Access Framework.
 *
 * Separate from [MainActivity] so the byte handling — which is the part that can silently corrupt
 * or refuse a backup — can be exercised against a real `ContentResolver` in an instrumented test.
 * Driving the picker itself still needs a person; this covers everything after it returns a URI.
 */
object BackupDocumentIo {

    /** Matches `NoteBackupImporter.MAX_BACKUP_CHARS`. */
    const val MAX_BACKUP_DOCUMENT_CHARS = 10 * 1024 * 1024

    /** Matches `BackupBundleLimits.MAX_BUNDLE_FILE_BYTES`. */
    const val MAX_BUNDLE_DOCUMENT_BYTES = 256L * 1024 * 1024

    private const val TAG = "BackupDocumentIo"

    /** Writes [json] to [uri], reporting whether the bytes actually landed. */
    fun write(resolver: ContentResolver, uri: Uri, json: String): Boolean =
        writeBytes(resolver, uri, json.toByteArray())

    fun writeBytes(resolver: ContentResolver, uri: Uri, bytes: ByteArray): Boolean =
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: error("no output stream for $uri")
        }.onFailure { Log.w(TAG, "Writing the backup document failed", it) }.isSuccess

    /**
     * Reads a picked backup document, refusing anything larger than the importer would accept.
     *
     * The cap is applied while reading rather than after: `NoteBackupImporter` does reject an
     * oversized backup, but only once the whole document is already a String in memory, which a
     * hostile or simply enormous file could exhaust first.
     */
    fun read(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val reader = input.reader()
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                val text = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    if (text.length + read > MAX_BACKUP_DOCUMENT_CHARS) {
                        Log.w(TAG, "Backup document exceeds the import limit; refusing it")
                        return null
                    }
                    text.appendRange(buffer, 0, read)
                }
                text.toString()
            }
        }.onFailure { Log.w(TAG, "Reading the backup document failed", it) }.getOrNull()

    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray? =
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val out = ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BUNDLE_DOCUMENT_BYTES) {
                        Log.w(TAG, "Backup bundle exceeds the import limit; refusing it")
                        return null
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.onFailure { Log.w(TAG, "Reading the backup bytes failed", it) }.getOrNull()
}
