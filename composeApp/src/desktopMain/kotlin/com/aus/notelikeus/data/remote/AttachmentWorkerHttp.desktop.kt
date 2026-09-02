package com.aus.notelikeus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal actual suspend fun attachmentWorkerPut(
    url: String,
    accessToken: String,
    body: ByteArray,
    mimeType: String,
): String = withContext(Dispatchers.IO) {
    val request = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $accessToken")
        .header("Content-Type", mimeType)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        .build()
    sendForBody(request)
}

internal actual suspend fun attachmentWorkerGet(
    url: String,
    accessToken: String,
): ByteArray = withContext(Dispatchers.IO) {
    val request = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $accessToken")
        .GET()
        .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() !in 200..299) {
        throw SupabaseTransportException("attachments", response.statusCode(), response.body().decodeToString())
    }
    response.body()
}

internal actual suspend fun attachmentWorkerDelete(
    url: String,
    accessToken: String,
) = withContext(Dispatchers.IO) {
    val request = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $accessToken")
        .DELETE()
        .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        throw SupabaseTransportException("attachments", response.statusCode(), response.body())
    }
}

private suspend fun sendForBody(request: HttpRequest): String = withContext(Dispatchers.IO) {
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        throw SupabaseTransportException("attachments", response.statusCode(), response.body())
    }
    response.body()
}
