package com.aus.notelikeus.data.remote

internal expect suspend fun attachmentWorkerPut(
    url: String,
    accessToken: String,
    body: ByteArray,
    mimeType: String,
): String

internal expect suspend fun attachmentWorkerGet(
    url: String,
    accessToken: String,
): ByteArray

internal expect suspend fun attachmentWorkerDelete(
    url: String,
    accessToken: String,
)
