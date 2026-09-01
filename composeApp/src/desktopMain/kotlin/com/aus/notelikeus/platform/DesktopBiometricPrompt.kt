package com.aus.notelikeus.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/**
 * Desktop stand-in for the Android biometric prompt.
 *
 * It deliberately offers **no** way to unlock: there is no Windows Hello integration yet, so
 * there is nothing here that can verify the user. The previous version showed a password field and
 * called `onSuccess` for any non-blank input, which made the app lock look like a security boundary
 * while accepting literally any text.
 *
 * `AppConfig.supportsAppLock` is false on desktop, so this should not normally be reachable; it
 * exists as the seam for a real Hello implementation (and as a safe fallback if it ever is).
 *
 * A real implementation would verify via Windows Hello / `CredUIPromptForWindowsCredentials`
 * through JNA and only then call [onSuccess].
 */
@Composable
fun DesktopBiometricPrompt(
    title: String,
    @Suppress("UNUSED_PARAMETER") onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    DialogWindow(
        onCloseRequest = onCancel,
        state = rememberDialogState(width = 400.dp, height = 260.dp),
        title = title
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "App lock isn't available on desktop yet, so this device can't verify " +
                        "you. Your notes are still stored locally.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
