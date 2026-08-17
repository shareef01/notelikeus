package com.aus.notelikeus.platform

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Desktop-side log sink. The desktop module had none, so its deliberate best-effort catch
 * blocks were fully silent: a failed session load or token refresh left nothing to diagnose
 * from. Thin wrapper around java.util.logging so call sites stay one line and no logging
 * dependency is added.
 */
internal object AppLog {

    fun warn(tag: String, message: String, error: Throwable? = null) {
        Logger.getLogger(tag).log(Level.WARNING, message, error)
    }

    fun info(tag: String, message: String, error: Throwable? = null) {
        Logger.getLogger(tag).log(Level.INFO, message, error)
    }
}
