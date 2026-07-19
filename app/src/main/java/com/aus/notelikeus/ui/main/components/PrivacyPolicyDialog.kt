package com.aus.notelikeus.ui.main.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aus.notelikeus.R

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val policyUrl = stringResource(R.string.privacy_policy_url)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(text = stringResource(R.string.privacy_policy_title))
        },
        text = {
            Text(
                text = stringResource(R.string.privacy_policy_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(policyUrl))
                    )
                }
            ) {
                Text(stringResource(R.string.privacy_policy_view_online))
            }
        }
    )
}
