package com.aus.notelikeus.data.remote

/**
 * Shared remote-backend selection. Firebase remains the default.
 * Release/production builds require an explicit allow flag and a non-localhost URL.
 */
internal fun isLocalSupabaseUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return true
    val host = trimmed
        .substringAfter("://", missingDelimiterValue = trimmed)
        .substringBefore('/')
        .substringBefore(':')
        .lowercase()
    return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")
}

fun isSupabaseRemoteSelected(
    isDebug: Boolean,
    remoteBackendEnv: String?,
    allowProductionEnv: String?,
    supabaseUrl: String,
): Boolean {
    if (remoteBackendEnv?.trim() != "supabase") return false
    if (isDebug) return true
    val allow = allowProductionEnv?.trim().equals("true", ignoreCase = true) ||
        allowProductionEnv?.trim() == "1"
    if (!allow) return false
    return !isLocalSupabaseUrl(supabaseUrl)
}
