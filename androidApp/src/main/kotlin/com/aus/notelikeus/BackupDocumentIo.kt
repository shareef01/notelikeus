package com.aus.notelikeus

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.aus.notelikeus.data.backup.bundle.BackupFileReads
import java.io.File
import okio.Sink
import okio.sink

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

    private fun writeBytes(resolver: ContentResolver, uri: Uri, bytes: ByteArray): Boolean =
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: error("no output stream for $uri")
        }.onFailure { Log.w(TAG, "Writing the backup document failed", it) }.isSuccess

    /**
     * A sink into the picked document, for a bundle streamed straight into it (F-07). The caller's
     * writer closes it. Null when the document cannot be opened for writing.
     */
    fun openSink(resolver: ContentResolver, uri: Uri): Sink? =
        runCatching { resolver.openOutputStream(uri)?.sink() }
            .onFailure { Log.w(TAG, "Opening the backup document for writing failed", it) }
            .getOrNull()

    /** Removes a document whose bundle could not be written, so no partial backup is left behind. */
    fun delete(resolver: ContentResolver, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .onFailure { Log.w(TAG, "Could not remove the incomplete backup document", it) }
    }

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
                BackupFileReads.readTextWithin(input, MAX_BACKUP_DOCUMENT_CHARS)
                    ?: run {
                        Log.w(TAG, "Backup document exceeds the import limit; refusing it")
                        null
                    }
            }
        }.onFailure { Log.w(TAG, "Reading the backup document failed", it) }.getOrNull()

    /** The first bytes of the picked document, enough to tell a bundle from a JSON backup. */
    fun readHead(resolver: ContentResolver, uri: Uri): ByteArray? =
        runCatching { resolver.openInputStream(uri)?.use { BackupFileReads.readHead(it) } }
            .onFailure { Log.w(TAG, "Reading the backup header failed", it) }.getOrNull()

    /**
     * Streams the picked bundle into [target] so it can be imported from disk, never from a buffer
     * the size of the bundle (F-07). False — with no [target] left behind — when it is larger than
     * the import limit or cannot be read.
     */
    fun copyToFile(resolver: ContentResolver, uri: Uri, target: File): Boolean =
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                BackupFileReads.copyWithin(input, target, MAX_BUNDLE_DOCUMENT_BYTES)
            } ?: false
        }.onFailure { Log.w(TAG, "Copying the backup bundle failed", it) }.getOrDefault(false)
            .also { copied -> if (!copied) Log.w(TAG, "Backup bundle was refused or unreadable") }
}
