package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class R2AttachmentBlobTransport(
    private val workerBaseUrl: String,
    private val accessTokenProvider: SupabaseAccessTokenProvider,
    private val metadata: SupabaseAttachmentMetadata,
    private val ownerIdProvider: suspend () -> String,
) : AttachmentBlobTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = workerBaseUrl.trimEnd('/')

    override suspend fun upload(
        noteId: String,
        attachmentId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AttachmentBlobUploadResult {
        val token = accessTokenProvider.accessToken()
            ?: throw SupabaseTransportException("attachments", 401, "missing access token")
        val ownerId = ownerIdProvider()
        val objectKey = AttachmentObjectKey.build(ownerId, noteId, attachmentId)
        val path = AttachmentObjectKey.workerPath(noteId, attachmentId)
        val responseBody = attachmentWorkerPut(
            url = "$baseUrl$path",
            accessToken = token,
            body = bytes,
            mimeType = mimeType,
        )
        val payload = json.parseToJsonElement(responseBody).jsonObject
        val uploadedKey = payload["objectKey"]?.jsonPrimitive?.content ?: objectKey
        val uploadedMime = payload["mimeType"]?.jsonPrimitive?.content ?: mimeType
        val uploadedSize = payload["sizeBytes"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong()
        metadata.register(
            attachmentId = attachmentId,
            noteId = noteId,
            objectKey = uploadedKey,
            mimeType = uploadedMime,
            sizeBytes = uploadedSize,
        )
        return AttachmentBlobUploadResult(
            objectKey = uploadedKey,
            sizeBytes = uploadedSize,
            mimeType = uploadedMime,
        )
    }

    override suspend fun download(noteId: String, attachmentId: String): ByteArray {
        val token = accessTokenProvider.accessToken()
            ?: throw SupabaseTransportException("attachments", 401, "missing access token")
        val path = AttachmentObjectKey.workerPath(noteId, attachmentId)
        return attachmentWorkerGet("$baseUrl$path", token)
    }

    override suspend fun delete(noteId: String, attachmentId: String) {
        val token = accessTokenProvider.accessToken()
            ?: throw SupabaseTransportException("attachments", 401, "missing access token")
        val path = AttachmentObjectKey.workerPath(noteId, attachmentId)
        attachmentWorkerDelete("$baseUrl$path", token)
        metadata.delete(attachmentId, noteId)
    }
}
