package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.JsonObject

interface SupabaseRpcClient {
    suspend fun callRpc(functionName: String, body: JsonObject): JsonObject
}
