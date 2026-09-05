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

    /** True only where cloud sign-in and remote sync are actually implemented. */
    val supportsCloudSync: Boolean

    /**
     * True on the desktop target. Use this for form-factor decisions rather than inferring one
     * from a capability flag: `supportsCloudSync` is true on Android too, so reading it as
     * "not a phone" made every Android device at Medium width take the two-pane layout.
     */
    val isDesktop: Boolean
}
