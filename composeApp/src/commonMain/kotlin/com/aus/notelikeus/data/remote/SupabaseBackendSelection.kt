package com.aus.notelikeus.data.remote

/** Well-known `supabase start` defaults. Debug/local-dev only — never a release fallback. */
internal const val DEFAULT_LOCAL_SUPABASE_URL = "http://127.0.0.1:54321"
internal const val DEFAULT_LOCAL_SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"

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

/** First non-blank trimmed value. Empty BuildConfig fields and unset env vars are skipped. */
internal fun firstNonBlank(vararg values: String?): String? =
    values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }

/**
 * Release builds fail closed when the hosted project is missing, matching web production.
 * Debug/local-dev may still fall back to the CLI defaults.
 */
internal fun resolveSupabaseUrl(configured: String?, allowLocalFallback: Boolean): String {
    val resolved = firstNonBlank(configured) ?: if (allowLocalFallback) DEFAULT_LOCAL_SUPABASE_URL else ""
    if (!allowLocalFallback && isLocalSupabaseUrl(resolved)) return ""
    return resolved
}

internal fun resolveSupabaseAnonKey(configured: String?, allowLocalFallback: Boolean): String {
    val resolved = firstNonBlank(configured)
    if (resolved != null) return resolved
    return if (allowLocalFallback) DEFAULT_LOCAL_SUPABASE_ANON_KEY else ""
}
