package com.aus.notelikeus.data.remote

class SupabaseTransportException(
    val functionName: String,
    val statusCode: Int,
    val body: String,
) : Exception("Supabase RPC $functionName failed: HTTP $statusCode ${body.take(500)}") {
    /**
     * Only 401 says the credential itself was rejected. A 403 is a refusal for this request (the
     * attachments Worker answers it for a note the server does not hold yet, PostgREST for a missing
     * grant), and calling that "session expired" sends the user to sign in again for nothing.
     */
    val isAuthFailure: Boolean get() = statusCode == 401

    /**
     * Whether the database's wipe-generation guard refused this write, and why.
     *
     * Read from the SQLSTATE PostgREST passes through, with the trigger's own message prefix as a
     * fallback so a proxy that rewrites the body cannot turn a definite refusal into a silent
     * failure. A raised exception's body is the trigger's text, which carries no note content.
     */
    val wipeGenerationRefusal: WipeGenerationRefusal?
        get() = when {
            body.contains("NL001") || body.contains("account_wipe_generation_required") ->
                WipeGenerationRefusal.Required
            body.contains("NL002") || body.contains("account_wipe_generation_stale") ->
                WipeGenerationRefusal.Stale
            else -> null
        }

    /**
     * Whether the database said this account is being deleted (`NL003`). A definite answer, which is
     * what makes it safe to erase the local copy on: nothing a network or an expired session can
     * produce looks like it.
     */
    val isAccountDeleted: Boolean
        get() = body.contains("NL003") || body.contains("account_deleted:")

    /**
     * Whether the server refused a destructive action until the user signs in again (AUD-005): the
     * database's NL004, or the Worker's 403 `reauthentication_required`. Nothing was deleted, and the
     * answer only ever leads to asking for a new code, so it is read leniently.
     */
    val isReauthenticationRequired: Boolean
        get() = body.contains("NL004") || body.contains("reauthentication_required")
}

/** Whether this failure, or anything it wraps, is the server asking for a recent sign-in first. */
fun Throwable.isReauthenticationRequiredRefusal(): Boolean =
    generateSequence(this) { it.cause }
        .take(8)
        .any { it is SupabaseTransportException && it.isReauthenticationRequired }

/** Whether this failure, or anything it wraps, is the database's answer that the account is gone. */
fun Throwable.isAccountDeletedRefusal(): Boolean =
    generateSequence(this) { it.cause }
        .take(8)
        .any { it is SupabaseTransportException && it.isAccountDeleted }

/** Why the wipe-generation guard refused a write. */
enum class WipeGenerationRefusal {
    /** The write presented no generation at all, on an account that has been wiped. */
    Required,

    /** The write presented a generation older than the account's. */
    Stale,
}
