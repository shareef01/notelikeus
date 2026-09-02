package com.aus.notelikeus.data.remote

class NoopAttachmentBlobTransport : AttachmentBlobTransport {
    override suspend fun upload(
        noteId: String,
        attachmentId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AttachmentBlobUploadResult {
        throw UnsupportedOperationException("Attachment storage is not enabled")
    }

    override suspend fun download(noteId: String, attachmentId: String): ByteArray {
        throw UnsupportedOperationException("Attachment storage is not enabled")
    }

    override suspend fun delete(noteId: String, attachmentId: String) {
        throw UnsupportedOperationException("Attachment storage is not enabled")
    }
}
