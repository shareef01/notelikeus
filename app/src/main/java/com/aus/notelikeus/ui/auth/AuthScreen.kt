package com.aus.notelikeus.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Sync
import com.aus.notelikeus.ui.components.GoogleSignInButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.ui.theme.BrandMark

enum class AuthMode { SIGN_IN, SIGN_UP }

@Composable
fun AuthScreen(
    initialMode: AuthMode = AuthMode.SIGN_IN,
    onDismiss: () -> Unit,
    onGoogleSignIn: () -> Unit,
    isGoogleSignInPending: Boolean = false,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }

    BackHandler(onBack = onDismiss)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandMark(modifier = Modifier.size(72.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(
                        if (mode == AuthMode.SIGN_IN) R.string.auth_welcome_back else R.string.auth_create_account_title
                    ),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (mode == AuthMode.SIGN_IN) R.string.auth_signin_subtitle else R.string.auth_signup_subtitle
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(28.dp))
                AuthModeTabs(
                    mode = mode,
                    onModeChange = { mode = it },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(28.dp))
                AuthFeatureRow(
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = stringResource(R.string.auth_feature_cloud),
                )
                Spacer(modifier = Modifier.height(12.dp))
                AuthFeatureRow(
                    icon = { Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = stringResource(R.string.auth_feature_sync),
                )
                Spacer(modifier = Modifier.height(12.dp))
                AuthFeatureRow(
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Notes,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    text = stringResource(R.string.auth_feature_offline),
                )

                Spacer(modifier = Modifier.height(28.dp))
                GoogleSignInButton(
                    onClick = onGoogleSignIn,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(
                        if (mode == AuthMode.SIGN_IN) R.string.cloud_sign_in_google else R.string.auth_sign_up_google
                    ),
                    loading = isGoogleSignInPending,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.auth_or),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(stringResource(R.string.auth_continue_local), fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.Center) {
                    Text(
                        text = stringResource(
                            if (mode == AuthMode.SIGN_IN) R.string.auth_switch_signup_prompt else R.string.auth_switch_signin_prompt
                        ) + " ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        mode = if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                    }) {
                        Text(
                            stringResource(
                                if (mode == AuthMode.SIGN_IN) R.string.auth_switch_signup_label else R.string.auth_switch_signin_label
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.auth_privacy_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AuthModeTabs(
    mode: AuthMode,
    onModeChange: (AuthMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AuthTab(
                label = stringResource(R.string.auth_tab_sign_in),
                selected = mode == AuthMode.SIGN_IN,
                onClick = { onModeChange(AuthMode.SIGN_IN) },
                modifier = Modifier.weight(1f),
            )
            AuthTab(
                label = stringResource(R.string.auth_tab_sign_up),
                selected = mode == AuthMode.SIGN_UP,
                onClick = { onModeChange(AuthMode.SIGN_UP) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AuthTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    } else {
        ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = MaterialTheme.shapes.medium,
        colors = colors,
        elevation = if (selected) ButtonDefaults.buttonElevation(defaultElevation = 1.dp) else null,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AuthFeatureRow(
    icon: @Composable () -> Unit,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            modifier = Modifier.size(36.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon()
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
