package com.aus.notelikeus.data.remote

data class AttachmentBlobUploadResult(
    val objectKey: String,
    val sizeBytes: Long,
    val mimeType: String,
)

interface AttachmentBlobTransport {
    suspend fun upload(
        noteId: String,
        attachmentId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AttachmentBlobUploadResult

    suspend fun download(noteId: String, attachmentId: String): ByteArray

    suspend fun delete(noteId: String, attachmentId: String)
}
