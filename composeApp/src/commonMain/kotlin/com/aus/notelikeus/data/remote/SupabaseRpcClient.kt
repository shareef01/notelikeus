package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

interface SupabaseRpcClient {
    suspend fun callRpc(functionName: String, body: JsonObject): JsonObject

    /** For RPCs that return a JSON array or scalar at the top level. */
    suspend fun callRpcElement(functionName: String, body: JsonObject = JsonObject(emptyMap())): JsonElement

    /**
     * An authenticated call bound to an explicitly captured [identity] — R13A, closing F-1.
     *
     * The bearer comes from the identity and **never** from the live session, so a request issued by
     * account A cannot go out under account B's credentials merely because B signed in before the
     * request was finally built. This is the variant every protected note RPC uses.
     *
     * The live-session overloads above are retained for callers outside this remediation's scope
     * (attachment metadata, auth/session traffic); they are not silently migrated.
     */
    suspend fun callRpc(identity: OperationRemoteIdentity, functionName: String, body: JsonObject): JsonObject =
        callRpcElement(identity, functionName, body).jsonObject

    suspend fun callRpcElement(
        identity: OperationRemoteIdentity,
        functionName: String,
        body: JsonObject = JsonObject(emptyMap()),
    ): JsonElement = throw UnsupportedOperationException(
        // Fails closed on purpose: a client that has not implemented identity binding must never be
        // able to satisfy this call from the live session — that substitution is the F-1 defect. Only
        // the real platform clients implement it; doubles that never receive identity-bound traffic
        // are unaffected.
        "Identity-bound RPC '$functionName' is not supported by ${this::class.simpleName}",
    )
}
