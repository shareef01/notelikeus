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

/** First non-blank trimmed value. Empty BuildConfig fields and unset env vars are skipped. */
internal fun firstNonBlank(vararg values: String?): String? =
    values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
