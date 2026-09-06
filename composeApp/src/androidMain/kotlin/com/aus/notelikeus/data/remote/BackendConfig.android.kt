package com.aus.notelikeus.data.remote

import com.aus.notelikeus.shared.BuildConfig

actual object BackendConfig {
    actual val supabaseUrl: String
        get() = resolveSupabaseUrl(
            firstNonBlank(
                System.getenv("NOTELIKEUS_SUPABASE_URL"),
                BuildConfig.NOTELIKEUS_SUPABASE_URL,
            ),
            // Debug APKs still install on phones. A localhost HTTP fallback is blocked
            // there as cleartext, so Android never uses the CLI demo defaults.
            allowLocalFallback = false,
        )

    actual val supabaseAnonKey: String
        get() = resolveSupabaseAnonKey(
            firstNonBlank(
                System.getenv("NOTELIKEUS_SUPABASE_ANON_KEY"),
                BuildConfig.NOTELIKEUS_SUPABASE_ANON_KEY,
            ),
            allowLocalFallback = false,
        )

    actual val attachmentsWorkerUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_ATTACHMENTS_WORKER_URL"),
            BuildConfig.NOTELIKEUS_ATTACHMENTS_WORKER_URL,
        ).orEmpty()
}
