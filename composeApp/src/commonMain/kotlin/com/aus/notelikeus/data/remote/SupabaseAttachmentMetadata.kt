package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseAttachmentMetadata(
    private val rpcClient: SupabaseRpcClient,
) {
    suspend fun register(
        attachmentId: String,
        noteId: String,
        objectKey: String,
        mimeType: String,
        sizeBytes: Long,
        attachmentType: String = "image",
    ) {
        rpcClient.callRpc(
            functionName = "register_note_attachment",
            body = buildJsonObject {
                put("p_attachment_id", attachmentId)
                put("p_note_id", noteId)
                put("p_object_key", objectKey)
                put("p_mime_type", mimeType)
                put("p_size_bytes", sizeBytes)
                put("p_attachment_type", attachmentType)
            },
        )
    }

    suspend fun delete(attachmentId: String, noteId: String) {
        rpcClient.callRpc(
            functionName = "delete_note_attachment",
            body = buildJsonObject {
                put("p_attachment_id", attachmentId)
                put("p_note_id", noteId)
            },
        )
    }
}
