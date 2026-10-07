package com.aus.notelikeus.data.remote

/**
 * Used when attachments are not configured. [com.aus.notelikeus.data.attachments.AttachmentSyncService]
 * gates on `blobTransport !is NoopAttachmentBlobTransport`, so none of these is ever reached — they
 * exist only so the type is honest about having no storage behind it.
 */
class NoopAttachmentBlobTransport : AttachmentBlobTransport {
    override suspend fun captureContext(): AttachmentRemoteContext = unsupported()

    override suspend fun upload(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AttachmentBlobUploadResult = unsupported()

    override suspend fun download(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
    ): ByteArray = unsupported()

    override suspend fun delete(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
    ): Nothing = unsupported()

    private fun <T> unsupported(): T = throw UnsupportedOperationException(
        "Attachment storage is not enabled",
    )
}
