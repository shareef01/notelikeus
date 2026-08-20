package com.aus.notelikeus.util

import java.util.logging.Level
import java.util.logging.Logger

/**
 * java.util.logging rather than android.util.Log: the shared ViewModels that log through here are
 * covered by plain (non-Robolectric) unit tests on this target, where every android.util.Log call
 * throws "not mocked".
 */
internal actual object AppLog {

    actual fun warn(tag: String, message: String, error: Throwable?) {
        Logger.getLogger(tag).log(Level.WARNING, message, error)
    }
}
