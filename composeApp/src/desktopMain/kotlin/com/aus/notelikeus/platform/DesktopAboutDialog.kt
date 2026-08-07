package com.aus.notelikeus.platform

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import com.aus.notelikeus.ui.theme.BrandMarkIcon

@Composable
fun DesktopAboutDialog(
    onDismiss: () -> Unit,
    version: String
) {
    Dialog(
        onCloseRequest = onDismiss,
        state = rememberDialogState(width = 450.dp, height = 350.dp),
        title = "About Notelikeus"
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BrandMarkIcon(
                    size = 64.dp,
                    backgroundColor = MaterialTheme.colorScheme.onSurface,
                    stripeColor = MaterialTheme.colorScheme.surface
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Notelikeus",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Version $version",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Running on ${System.getProperty("os.name")} (${System.getProperty("os.arch")})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "A modern, cross-platform note taking app built with Kotlin Multiplatform and Compose.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}
