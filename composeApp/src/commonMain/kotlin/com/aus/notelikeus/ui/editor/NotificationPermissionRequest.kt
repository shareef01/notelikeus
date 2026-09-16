package com.aus.notelikeus.ui.editor

import androidx.compose.runtime.Composable

/**
 * Asks the platform for permission to show notifications, if it has such a thing to ask.
 *
 * Returns a function that takes what to do once the answer is in: `true` when notifications can
 * now be shown, `false` when they cannot. Platforms with no runtime permission call back `true`
 * immediately, so the caller has one shape to handle rather than a branch per platform.
 *
 * The callback is the whole point. A reminder is confirmed to the user the moment it is saved,
 * and confirming before the permission dialog is answered would announce a delivery nobody has
 * agreed to yet.
 */
@Composable
expect fun rememberNotificationPermissionRequest(): (onResult: (granted: Boolean) -> Unit) -> Unit
