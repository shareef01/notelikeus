package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.remote.AttachmentBlobTransport

/** Downloads one image's bytes for a complete export. */
fun interface CloudAttachmentDownloader {
    suspend fun download(noteId: String, attachmentId: String): ByteArray
}

/**
 * Where a complete export gets images that live only in the cloud (AUD-001).
 *
 * [open] fixes the account for one whole export before any image is read, so an export that began
 * signed in as one account can never fetch another's images if the session moves halfway through.
 * It returns that account's id with the downloader, for the export to check against its own.
 */
interface CloudAttachmentSource {
    suspend fun open(): Pair<String, CloudAttachmentDownloader>
}

/**
 * The editor's own authenticated Worker GET, so a complete export is authorized exactly as viewing
 * an image is: the Worker derives the object key from the signed-in user and answers 404 for any
 * other account's attachment.
 */
class BlobTransportCloudAttachmentSource(
    private val transport: AttachmentBlobTransport,
) : CloudAttachmentSource {
    override suspend fun open(): Pair<String, CloudAttachmentDownloader> {
        val context = transport.captureContext()
        return context.ownerId to CloudAttachmentDownloader { noteId, attachmentId ->
            transport.download(context, noteId, attachmentId)
        }
    }
}
