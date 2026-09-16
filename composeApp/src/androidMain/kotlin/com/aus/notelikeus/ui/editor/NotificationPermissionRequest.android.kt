package com.aus.notelikeus.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberNotificationPermissionRequest(): (onResult: (granted: Boolean) -> Unit) -> Unit {
    val context = LocalContext.current

    // Held rather than passed through the launcher, because the contract's callback takes only the
    // grant result — there is nowhere to thread the caller's continuation through it.
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callback = pending[0]
        pending[0] = null
        callback?.invoke(granted)
    }

    return remember(context) {
        { onResult ->
            when {
                // Below API 33 there is no runtime permission; notifications can still be off
                // app-wide from Settings, which is what this reads.
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                    onResult(NotificationManagerCompat.from(context).areNotificationsEnabled())

                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED ->
                    // Granted at the permission level, but the user can still have turned
                    // notifications off for the app, and then nothing is shown either.
                    onResult(NotificationManagerCompat.from(context).areNotificationsEnabled())

                else -> {
                    // First ask shows the system dialog. After a permanent denial the system
                    // answers `false` without showing anything, which is exactly the outcome the
                    // caller needs — there is no third "ask again later" state to handle.
                    pending[0] = onResult
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
