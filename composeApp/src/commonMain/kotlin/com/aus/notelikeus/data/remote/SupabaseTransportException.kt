package com.aus.notelikeus.data.remote

class SupabaseTransportException(
    val functionName: String,
    val statusCode: Int,
    body: String,
) : Exception("Supabase RPC $functionName failed: HTTP $statusCode ${body.take(500)}") {
    /**
     * Only 401 says the credential itself was rejected. A 403 is a refusal for this request (the
     * attachments Worker answers it for a note the server does not hold yet, PostgREST for a missing
     * grant), and calling that "session expired" sends the user to sign in again for nothing.
     */
    val isAuthFailure: Boolean get() = statusCode == 401
}
