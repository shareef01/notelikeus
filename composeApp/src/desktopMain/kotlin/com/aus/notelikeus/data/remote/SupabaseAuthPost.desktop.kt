package com.aus.notelikeus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal actual suspend fun supabaseAuthPost(
    supabaseUrl: String,
    anonKey: String,
    path: String,
    body: String,
): String = withContext(Dispatchers.IO) {
    val request = HttpRequest.newBuilder()
        .uri(URI("$supabaseUrl$path"))
        .timeout(Duration.ofSeconds(30))
        .header("apikey", anonKey)
        .header("Authorization", "Bearer $anonKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        throw SupabaseTransportException("auth", response.statusCode(), response.body())
    }
    response.body()
}
