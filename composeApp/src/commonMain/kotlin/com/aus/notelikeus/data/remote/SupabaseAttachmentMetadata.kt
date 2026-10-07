package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.attachments.NoteAttachmentMetadata
import com.aus.notelikeus.data.attachments.PendingDeletedAttachment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * [AttachmentRemoteMetadata] over an identity-bound RPC client.
 *
 * Every call passes the caller's captured [OperationRemoteIdentity] to the identity-bound
 * `SupabaseRpcClient` method, which sends *that* bearer and never reads the live session. There is no
 * live-session overload on this class at all (R17 removed them): the previous shape resolved the
 * token at send time, so an operation that began under account A and suspended could send its
 * listing or its purge as account B. The bearer and the account it belongs to travel together in the
 * identity value, which is why no method here takes an owner argument that could drift from it.
 *
 * The client is not consulted for a credential anywhere in this file, and the identity-bound methods
 * fail closed by default — a client that has not implemented identity binding throws rather than
 * silently substituting the live session.
 */
class SupabaseAttachmentMetadata(
    private val rpcClient: SupabaseRpcClient,
) : AttachmentRemoteMetadata {

    override suspend fun register(
        identity: OperationRemoteIdentity,
        metadata: NoteAttachmentMetadata,
    ) {
        rpcClient.callRpc(
            identity = identity,
            functionName = "register_note_attachment",
            body = buildJsonObject {
                put("p_attachment_id", metadata.attachmentId)
                put("p_note_id", metadata.noteId)
                put("p_object_key", metadata.objectKey)
                put("p_mime_type", metadata.mimeType)
                put("p_size_bytes", metadata.sizeBytes)
                put("p_attachment_type", metadata.attachmentType)
            },
        )
    }

    override suspend fun delete(
        identity: OperationRemoteIdentity,
        attachmentId: String,
        noteId: String,
    ) {
        rpcClient.callRpc(
            identity = identity,
            functionName = "delete_note_attachment",
            body = buildJsonObject {
                put("p_attachment_id", attachmentId)
                put("p_note_id", noteId)
            },
        )
    }

    override suspend fun listPendingDeletedAttachments(
        identity: OperationRemoteIdentity,
    ): List<PendingDeletedAttachment> {
        val element = rpcClient.callRpcElement(identity, "list_pending_deleted_attachments")
        return element.jsonArray.mapNotNull { row ->
            val obj = row.jsonObject
            val attachmentId = obj.stringField("attachment_id") ?: return@mapNotNull null
            val noteId = obj.stringField("note_id") ?: return@mapNotNull null
            if (attachmentId.isBlank() || noteId.isBlank()) return@mapNotNull null
            PendingDeletedAttachment(
                attachmentId = attachmentId,
                noteId = noteId,
                objectKey = obj.stringField("object_key").orEmpty(),
            )
        }
    }

    override suspend fun purgeDeleted(
        identity: OperationRemoteIdentity,
        attachmentId: String,
        noteId: String,
    ) {
        rpcClient.callRpc(
            identity = identity,
            functionName = "purge_deleted_note_attachment",
            body = buildJsonObject {
                put("p_attachment_id", attachmentId)
                put("p_note_id", noteId)
            },
        )
    }

    override suspend fun listUserAttachments(
        identity: OperationRemoteIdentity,
    ): List<NoteAttachmentMetadata> {
        val element = rpcClient.callRpcElement(identity, "list_user_attachments")
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
