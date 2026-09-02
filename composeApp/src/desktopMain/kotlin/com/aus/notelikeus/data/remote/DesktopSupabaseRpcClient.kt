package com.aus.notelikeus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class DesktopSupabaseRpcClient(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val accessTokenProvider: SupabaseAccessTokenProvider,
) : SupabaseRpcClient {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
        callRpcElement(functionName, body).jsonObject

    override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            val token = accessTokenProvider.accessToken()
                ?: throw SupabaseTransportException(functionName, 401, "missing access token")

            val request = HttpRequest.newBuilder()
                .uri(URI("$supabaseUrl/rest/v1/rpc/$functionName"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val code = response.statusCode()
            if (code !in 200..299) {
                throw SupabaseTransportException(functionName, code, response.body())
            }

            json.parseToJsonElement(response.body())
        }

    private companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}
