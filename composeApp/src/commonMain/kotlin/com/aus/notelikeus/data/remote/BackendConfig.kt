package com.aus.notelikeus.data.remote

expect object BackendConfig {
    val supabaseUrl: String
    val supabaseAnonKey: String
    val attachmentsWorkerUrl: String
    /** The web app's Turnstile challenge page (D30); empty in builds without CAPTCHA. */
    val captchaPageUrl: String
}
