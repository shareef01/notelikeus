package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.attachments.NoteAttachmentMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    suspend fun listUserAttachments(): List<NoteAttachmentMetadata> {
        val element = rpcClient.callRpcElement("list_user_attachments")
        val rows = element.jsonArray
        return rows.mapNotNull { row ->
            val obj = row.jsonObject
            val attachmentId = obj.stringField("attachment_id") ?: return@mapNotNull null
            val noteId = obj.stringField("note_id") ?: return@mapNotNull null
            if (attachmentId.isBlank() || noteId.isBlank()) return@mapNotNull null
            NoteAttachmentMetadata(
                attachmentId = attachmentId,
                noteId = noteId,
                objectKey = obj.stringField("object_key").orEmpty(),
                mimeType = obj.stringField("mime_type") ?: "application/octet-stream",
                sizeBytes = obj.longField("size_bytes") ?: 0L,
                attachmentType = obj.stringField("attachment_type") ?: "image",
                createdAt = obj.longField("created_at") ?: 0L,
            )
        }
    }
}

private fun JsonObject.stringField(key: String): String? =
    get(key)?.jsonPrimitive?.content

private fun JsonObject.longField(key: String): Long? =
    get(key)?.jsonPrimitive?.longOrNull
