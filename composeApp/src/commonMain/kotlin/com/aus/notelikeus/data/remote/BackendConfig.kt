package com.aus.notelikeus.data.remote

enum class RemoteBackend {
    FIREBASE,
    SUPABASE,
}

expect object BackendConfig {
    val remoteBackend: RemoteBackend
    val supabaseUrl: String
    val supabaseAnonKey: String
    val attachmentsWorkerUrl: String
}
