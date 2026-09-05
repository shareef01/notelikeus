package com.aus.notelikeus.data.remote

import com.aus.notelikeus.shared.BuildConfig

private const val DEFAULT_LOCAL_SUPABASE_URL = "http://127.0.0.1:54321"
private const val DEFAULT_LOCAL_SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"

actual object BackendConfig {
    actual val supabaseUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_SUPABASE_URL"),
            BuildConfig.NOTELIKEUS_SUPABASE_URL,
        ) ?: DEFAULT_LOCAL_SUPABASE_URL

    actual val supabaseAnonKey: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_SUPABASE_ANON_KEY"),
            BuildConfig.NOTELIKEUS_SUPABASE_ANON_KEY,
        ) ?: DEFAULT_LOCAL_SUPABASE_ANON_KEY

    actual val attachmentsWorkerUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_ATTACHMENTS_WORKER_URL"),
            BuildConfig.NOTELIKEUS_ATTACHMENTS_WORKER_URL,
        ).orEmpty()
}
