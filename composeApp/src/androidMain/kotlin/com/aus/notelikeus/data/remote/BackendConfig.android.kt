package com.aus.notelikeus.data.remote

import com.aus.notelikeus.shared.BuildConfig
import com.aus.notelikeus.util.AppConfig

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
                    BuildConfig.NOTELIKEUS_REMOTE_BACKEND,
                ),
                // A device process sees no environment, so a release build can only learn it is a
                // cutover from BuildConfig. That field is written solely by a build whose
                // environment set NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION — never from
                // local.properties — so an ordinary assembleRelease still resolves to null here
                // and isSupabaseRemoteSelected returns false.
                allowProductionEnv = firstNonBlank(
                    System.getenv("NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION"),
                    BuildConfig.NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION,
                ),
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
