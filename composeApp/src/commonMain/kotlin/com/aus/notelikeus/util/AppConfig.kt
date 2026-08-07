package com.aus.notelikeus.util

expect object AppConfig {
    val isDebug: Boolean
    val versionName: String

    /**
     * True only where the platform can actually verify the user (Android BiometricPrompt).
     * Desktop has no verification path yet, so the app-lock setting is hidden there rather than
     * offering a lock screen that anything could get past.
     */
    val supportsAppLock: Boolean

    /** True only where cloud sign-in and Firestore sync are actually implemented. */
    val supportsCloudSync: Boolean
}
