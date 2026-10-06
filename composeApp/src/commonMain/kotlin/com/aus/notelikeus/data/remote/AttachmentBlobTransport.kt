package com.aus.notelikeus.data.remote

data class AttachmentBlobUploadResult(
    val objectKey: String,
    val sizeBytes: Long,
    val mimeType: String,
)

/**
 * Remote attachment blobs.
 *
 * Every operation takes an [AttachmentRemoteContext] rather than resolving credentials itself, so
 * the account it runs against is fixed by the caller at the moment the operation began and cannot
 * drift to whatever session happens to be signed in when the remote call finally goes out.
 */
interface AttachmentBlobTransport {
    /**
     * Snapshots the identity remote attachment work must execute under.
     *
     * Call it where the operation starts — before any suspension another account could land behind
     * — and pass the result to [upload], [download] and [delete]. It may refresh credentials, so it
     * must not be called from inside a held account-isolation critical section.
     */
    suspend fun captureContext(): AttachmentRemoteContext

    suspend fun upload(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AttachmentBlobUploadResult

    suspend fun download(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
    ): ByteArray

    suspend fun delete(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
    )
}
