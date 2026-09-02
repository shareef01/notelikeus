package com.aus.notelikeus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

internal actual suspend fun attachmentWorkerPut(
    url: String,
    accessToken: String,
    body: ByteArray,
    mimeType: String,
): String = withContext(Dispatchers.IO) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "PUT"
        connectTimeout = REQUEST_TIMEOUT_MS
        readTimeout = REQUEST_TIMEOUT_MS
        doOutput = true
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Content-Type", mimeType)
    }
    connection.outputStream.use { stream -> stream.write(body) }
    readResponse(connection)
}

internal actual suspend fun attachmentWorkerGet(
    url: String,
    accessToken: String,
): ByteArray = withContext(Dispatchers.IO) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = REQUEST_TIMEOUT_MS
        readTimeout = REQUEST_TIMEOUT_MS
        setRequestProperty("Authorization", "Bearer $accessToken")
    }
    val code = connection.responseCode
    if (code !in 200..299) {
        throw SupabaseTransportException("attachments", code, connection.errorStream?.readBytes()?.decodeToString().orEmpty())
    }
    connection.inputStream.use { it.readBytes() }
}

internal actual suspend fun attachmentWorkerDelete(
    url: String,
    accessToken: String,
) {
    withContext(Dispatchers.IO) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        readResponse(connection)
    }
}

private fun readResponse(connection: HttpURLConnection): String {
    val code = connection.responseCode
    val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
        ?.readBytes()
        ?.decodeToString()
        .orEmpty()
    if (code !in 200..299) {
        throw SupabaseTransportException("attachments", code, responseBody)
    }
    return responseBody
}

private const val REQUEST_TIMEOUT_MS = 30_000
