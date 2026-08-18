package com.aus.notelikeus.util

import com.aus.notelikeus.shared.BuildConfig

actual object AppConfig {
    actual val isDebug: Boolean = BuildConfig.DEBUG
    actual val versionName: String = GENERATED_APP_VERSION_NAME
    actual val supportsAppLock: Boolean = true
    actual val supportsCloudSync: Boolean = true
    actual val isDesktop: Boolean = false
}
