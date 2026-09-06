package com.aus.notelikeus.data.remote

import com.aus.notelikeus.shared.BuildConfig

actual object BackendConfig {
    actual val supabaseUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_SUPABASE_URL"),
            BuildConfig.NOTELIKEUS_SUPABASE_URL,
        ).orEmpty()

    actual val supabaseAnonKey: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_SUPABASE_ANON_KEY"),
            BuildConfig.NOTELIKEUS_SUPABASE_ANON_KEY,
        ).orEmpty()

    actual val attachmentsWorkerUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_ATTACHMENTS_WORKER_URL"),
            BuildConfig.NOTELIKEUS_ATTACHMENTS_WORKER_URL,
        ).orEmpty()
}
