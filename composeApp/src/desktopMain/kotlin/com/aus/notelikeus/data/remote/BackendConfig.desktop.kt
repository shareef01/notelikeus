package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.util.readLocalProperty

private const val DEFAULT_LOCAL_SUPABASE_URL = "http://127.0.0.1:54321"
private const val DEFAULT_LOCAL_SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"

actual object BackendConfig {
    actual val remoteBackend: RemoteBackend
        get() = if (
            isSupabaseRemoteSelected(
                isDebug = AppConfig.isDebug,
                remoteBackendEnv = firstNonBlank(
                    System.getenv("NOTELIKEUS_REMOTE_BACKEND"),
                    readLocalProperty("notelikeus.remoteBackend"),
                ),
                // Do not read an allow-production key from local.properties.
                allowProductionEnv = System.getenv("NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION"),
                supabaseUrl = supabaseUrl,
            )
        ) {
            RemoteBackend.SUPABASE
        } else {
            RemoteBackend.FIREBASE
        }

    actual val supabaseUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_SUPABASE_URL"),
            readLocalProperty("notelikeus.supabaseUrl"),
        ) ?: DEFAULT_LOCAL_SUPABASE_URL

    actual val supabaseAnonKey: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_SUPABASE_ANON_KEY"),
            readLocalProperty("notelikeus.supabaseAnonKey"),
        ) ?: DEFAULT_LOCAL_SUPABASE_ANON_KEY

    actual val attachmentsWorkerUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_ATTACHMENTS_WORKER_URL"),
            readLocalProperty("notelikeus.attachmentsWorkerUrl"),
        ).orEmpty()
}
