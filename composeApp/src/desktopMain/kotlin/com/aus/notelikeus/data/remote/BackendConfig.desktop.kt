package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.util.readLocalProperty

/**
 * Environment first (development overrides), then the values compiled into the build
 * ([CompiledBackendConfig], from the same env vars or `local.properties` at build time), and only then
 * a `local.properties` found on disk. An installed MSI has neither of the runtime sources, so the
 * compiled values are what it syncs with; and because they come before the disk lookup, a stray
 * `local.properties` near the working directory cannot redirect a release to another backend.
 */
actual object BackendConfig {
    actual val supabaseUrl: String
        get() = resolveSupabaseUrl(
            firstNonBlank(
                System.getenv("NOTELIKEUS_SUPABASE_URL"),
                CompiledBackendConfig.SUPABASE_URL,
                readLocalProperty("notelikeus.supabaseUrl"),
            ),
            allowLocalFallback = AppConfig.isDebug,
        )

    actual val supabaseAnonKey: String
        get() = resolveSupabaseAnonKey(
            firstNonBlank(
                System.getenv("NOTELIKEUS_SUPABASE_ANON_KEY"),
                CompiledBackendConfig.SUPABASE_ANON_KEY,
                readLocalProperty("notelikeus.supabaseAnonKey"),
            ),
            allowLocalFallback = AppConfig.isDebug,
        )

    actual val attachmentsWorkerUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_ATTACHMENTS_WORKER_URL"),
            CompiledBackendConfig.ATTACHMENTS_WORKER_URL,
            readLocalProperty("notelikeus.attachmentsWorkerUrl"),
        ).orEmpty()

    actual val captchaPageUrl: String
        get() = firstNonBlank(
            System.getenv("NOTELIKEUS_CAPTCHA_PAGE_URL"),
            CompiledBackendConfig.CAPTCHA_PAGE_URL,
            readLocalProperty("notelikeus.captchaPageUrl"),
        ).orEmpty()
}
