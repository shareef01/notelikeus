package com.aus.notelikeus.util

import java.util.logging.Level
import java.util.logging.Logger

internal actual object AppLog {

    actual fun warn(tag: String, message: String, error: Throwable?) {
        Logger.getLogger(tag).log(Level.WARNING, message, error)
    }
}
