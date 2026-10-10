package com.aus.notelikeus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URI

class AndroidSupabaseRpcClient(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val accessTokenProvider: SupabaseAccessTokenProvider,
) : SupabaseRpcClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
        callRpcElement(functionName, body).jsonObject

    /**
     * Live-session call. The bearer is resolved here, at send time — which is the F-1 defect for
     * anything account-owned. Protected note RPCs use the [OperationRemoteIdentity] overload below
     * instead; this one is retained for callers outside this remediation's scope.
     */
    override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            val token = accessTokenProvider.accessToken()
                ?: throw SupabaseTransportException(functionName, 401, "missing access token")
            send(functionName, body, token)
        }

    /** Identity-bound call: the bearer is the captured one, and no session lookup happens here. */
    override suspend fun callRpcElement(
        identity: OperationRemoteIdentity,
        functionName: String,
        body: JsonObject,
    ): JsonElement =
        withContext(Dispatchers.IO) {
            send(functionName, body, identity.accessToken)
        }

    private fun send(functionName: String, body: JsonObject, token: String): JsonElement {
        val connection = (URI("$supabaseUrl/rest/v1/rpc/$functionName").toURL().openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            doOutput = true
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }

        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        if (code !in 200..299) {
            throw SupabaseTransportException(functionName, code, responseBody)
        }

        return json.parseToJsonElement(responseBody)
    }

    private companion object {
        private const val REQUEST_TIMEOUT_MS = 30_000
    }
}
