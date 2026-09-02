package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface SupabaseRpcClient {
    suspend fun callRpc(functionName: String, body: JsonObject): JsonObject

    /** For RPCs that return a JSON array or scalar at the top level. */
    suspend fun callRpcElement(functionName: String, body: JsonObject = JsonObject(emptyMap())): JsonElement
}
