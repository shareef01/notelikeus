package com.aus.notelikeus.data.remote

expect object BackendConfig {
    val supabaseUrl: String
    val supabaseAnonKey: String
    val attachmentsWorkerUrl: String
}
