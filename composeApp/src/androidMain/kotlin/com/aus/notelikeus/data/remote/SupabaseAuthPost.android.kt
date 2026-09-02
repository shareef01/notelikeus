package com.aus.notelikeus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

internal actual suspend fun supabaseAuthPost(
    supabaseUrl: String,
    anonKey: String,
    path: String,
    body: String,
): String = withContext(Dispatchers.IO) {
    val connection = (URI("$supabaseUrl$path").toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = REQUEST_TIMEOUT_MS
        readTimeout = REQUEST_TIMEOUT_MS
        doOutput = true
        setRequestProperty("apikey", anonKey)
        setRequestProperty("Authorization", "Bearer $anonKey")
        setRequestProperty("Content-Type", "application/json")
    }

    connection.outputStream.use { stream ->
        stream.write(body.toByteArray(Charsets.UTF_8))
    }

    val code = connection.responseCode
    val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()

    if (code !in 200..299) {
        throw SupabaseTransportException("auth", code, responseBody)
    }
    responseBody
}

private const val REQUEST_TIMEOUT_MS = 30_000
