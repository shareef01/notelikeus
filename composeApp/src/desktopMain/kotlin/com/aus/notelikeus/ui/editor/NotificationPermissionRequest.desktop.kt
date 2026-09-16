package com.aus.notelikeus.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop reminders are a tray notification from a [java.util.Timer] inside this process. There is
 * no permission gate in front of that, so there is nothing to ask for.
 */
@Composable
actual fun rememberNotificationPermissionRequest(): (onResult: (granted: Boolean) -> Unit) -> Unit =
    remember { { onResult -> onResult(true) } }
