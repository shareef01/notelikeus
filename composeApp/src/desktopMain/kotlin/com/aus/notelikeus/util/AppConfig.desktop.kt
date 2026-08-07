package com.aus.notelikeus.util

actual object AppConfig {
    actual val isDebug: Boolean = true
    actual val versionName: String = "1.0.0"

    // App lock needs Windows Hello via JNA — not implemented yet.
    actual val supportsAppLock: Boolean = false

    // Cloud sync is implemented via OAuth 2.0 loopback flow + Firestore REST.
    actual val supportsCloudSync: Boolean = true
}
