package com.aus.notelikeus.util

/**
 * Shared log sink for deliberate catch blocks. Common code (ViewModels, editor navigation) had
 * no way to record a failure it recovers from, so recoverable-but-unexpected errors left nothing
 * to diagnose from. Kept to one call per site so the recovery path stays readable.
 */
internal expect object AppLog {
    fun warn(tag: String, message: String, error: Throwable? = null)
}
