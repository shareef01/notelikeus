package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class R2AttachmentBlobTransport(
    private val workerBaseUrl: String,
    private val accessTokenProvider: SupabaseAccessTokenProvider,
    private val ownerIdProvider: suspend () -> String,
) : AttachmentBlobTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = workerBaseUrl.trimEnd('/')

    /**
     * The only place the live session is consulted.
     *
     * Both the token and the owner come from here in one snapshot, so a caller that holds the
     * result is pinned to one account even if the session changes while its remote work is in
     * flight. Resolving them per call instead is the bug this exists to prevent.
     */
    override suspend fun captureContext(): AttachmentRemoteContext {
        val token = accessTokenProvider.accessToken()
            ?: throw SupabaseTransportException("attachments", 401, "missing access token")
        return AttachmentRemoteContext(ownerId = ownerIdProvider(), accessToken = token)
    }

    override suspend fun upload(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AttachmentBlobUploadResult {
        // The stored key is built from the *captured* owner: whatever the session says now, this
        // upload's object belongs to the account the operation was initiated under.
        val objectKey = AttachmentObjectKey.build(context.ownerId, noteId, attachmentId)
        // R19.2: uploads use the deferred protocol, so their metadata row is provisional until the note
        // that references it commits. There is deliberately no fallback to the legacy route: an
        // unsupported `v2` is an unsupported deployment, and retrying on `v1` would commit the row and
        // recreate the orphan. Reads and deletes keep the `v1` route — they are protocol-independent.
        val path = AttachmentObjectKey.deferredWorkerPath(noteId, attachmentId)
        val responseBody = attachmentWorkerPut(
            url = "$baseUrl$path",
            accessToken = context.accessToken,
            body = bytes,
            mimeType = mimeType,
        )
        val payload = json.parseToJsonElement(responseBody).jsonObject
        val uploadedKey = payload["objectKey"]?.jsonPrimitive?.content ?: objectKey
        val uploadedMime = payload["mimeType"]?.jsonPrimitive?.content ?: mimeType
        val uploadedSize = payload["sizeBytes"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong()
        return AttachmentBlobUploadResult(
            objectKey = uploadedKey,
            sizeBytes = uploadedSize,
            mimeType = uploadedMime,
        )
    }

    override suspend fun download(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
    ): ByteArray {
        val path = AttachmentObjectKey.workerPath(noteId, attachmentId)
        return attachmentWorkerGet("$baseUrl$path", context.accessToken)
    }

    override suspend fun delete(
        context: AttachmentRemoteContext,
        noteId: String,
        attachmentId: String,
    ) {
        val path = AttachmentObjectKey.workerPath(noteId, attachmentId)
        attachmentWorkerDelete("$baseUrl$path", context.accessToken)
    }
}
