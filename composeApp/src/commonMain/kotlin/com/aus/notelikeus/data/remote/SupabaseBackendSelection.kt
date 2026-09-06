package com.aus.notelikeus.data.remote

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
 * there, so Google sign-in got an ID token and then died with CLEARTEXT communication not permitted.
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
