package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.util.readLocalProperty

actual object BackendConfig {
    actual val supabaseUrl: String
        get() = resolveSupabaseUrl(
            firstNonBlank(
                System.getenv("NOTELIKEUS_SUPABASE_URL"),
                readLocalProperty("notelikeus.supabaseUrl"),
            ),
            allowLocalFallback = AppConfig.isDebug,
        )

    actual val supabaseAnonKey: String
        get() = resolveSupabaseAnonKey(
            firstNonBlank(
                System.getenv("NOTELIKEUS_SUPABASE_ANON_KEY"),
                readLocalProperty("notelikeus.supabaseAnonKey"),
            ),
            allowLocalFallback = AppConfig.isDebug,
        )

    actual val attachmentsWorkerUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_ATTACHMENTS_WORKER_URL"),
            readLocalProperty("notelikeus.attachmentsWorkerUrl"),
        ).orEmpty()
}
