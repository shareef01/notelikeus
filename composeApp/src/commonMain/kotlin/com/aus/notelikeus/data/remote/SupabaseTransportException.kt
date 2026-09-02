package com.aus.notelikeus.data.remote

class SupabaseTransportException(
    val functionName: String,
    val statusCode: Int,
    body: String,
) : Exception("Supabase RPC $functionName failed: HTTP $statusCode ${body.take(500)}") {
    val isAuthFailure: Boolean get() = statusCode == 401 || statusCode == 403
}
