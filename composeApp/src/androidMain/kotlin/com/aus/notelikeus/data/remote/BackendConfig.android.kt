package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppConfig

private const val DEFAULT_LOCAL_SUPABASE_URL = "http://127.0.0.1:54321"
private const val DEFAULT_LOCAL_SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"

actual object BackendConfig {
    actual val remoteBackend: RemoteBackend
        get() = if (AppConfig.isDebug && System.getenv("NOTELIKEUS_REMOTE_BACKEND") == "supabase") {
            RemoteBackend.SUPABASE
        } else {
            RemoteBackend.FIREBASE
        }

    actual val supabaseUrl: String
        get() = System.getenv("NOTELIKEUS_SUPABASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_LOCAL_SUPABASE_URL

    actual val supabaseAnonKey: String
        get() = System.getenv("NOTELIKEUS_SUPABASE_ANON_KEY")?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_LOCAL_SUPABASE_ANON_KEY
}
