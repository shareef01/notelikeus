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

/**
 * Android blocks cleartext HTTP to 127.0.0.1. A debug APK with no baked URL used to fall back
 * there, so sign-in got as far as the first auth request and died with CLEARTEXT communication not
 * permitted.
 */
internal fun requireConfiguredSupabaseUrl(url: String) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) {
        error(
            "Supabase URL is not set. Run npm run kotlin:staging-properties and rebuild the APK.",
        )
    }
    if (trimmed.startsWith("http://") && isLocalSupabaseUrl(trimmed)) {
        error(
            "This build points at local Supabase over HTTP ($trimmed). " +
                "Android blocks that as cleartext on a device. Bake a hosted https URL and rebuild.",
        )
    }
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

/** Prefix of Supabase's publishable client key, the replacement for the legacy anon JWT (D31). */
internal const val SUPABASE_PUBLISHABLE_KEY_PREFIX = "sb_publishable_"

/**
 * `Authorization` value for a request that carries only the client key, no user session. The
 * legacy anon JWT doubles as a bearer, as it always has. A publishable key is not a JWT and belongs
 * in `apikey` alone, so it gets no `Authorization` header (null).
 */
internal fun clientKeyBearer(clientKey: String): String? =
    if (clientKey.startsWith(SUPABASE_PUBLISHABLE_KEY_PREFIX)) null else "Bearer $clientKey"

internal fun resolveSupabaseAnonKey(configured: String?, allowLocalFallback: Boolean): String {
    val resolved = firstNonBlank(configured)
    if (resolved != null) return resolved
    return if (allowLocalFallback) DEFAULT_LOCAL_SUPABASE_ANON_KEY else ""
}
