package com.aus.notelikeus.platform

import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.about_description
import notelikeus.composeapp.generated.resources.about_running_on
import notelikeus.composeapp.generated.resources.about_title
import notelikeus.composeapp.generated.resources.about_version
import notelikeus.composeapp.generated.resources.action_close
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.aus.notelikeus.ui.theme.BrandMarkIcon

@Composable
fun DesktopAboutDialog(
    onDismiss: () -> Unit,
    version: String
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(width = 450.dp, height = 350.dp),
        title = stringResource(Res.string.about_title)
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
                    stripeColor = MaterialTheme.colorScheme.surface,
                    ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Notelikeus",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(Res.string.about_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        Res.string.about_running_on,
                        System.getProperty("os.name").orEmpty(),
                        System.getProperty("os.arch").orEmpty()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(Res.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_close))
                }
            }
        }
    }
}
