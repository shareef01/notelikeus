package com.aus.notelikeus.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.ui.theme.BrandMark
import com.aus.notelikeus.util.AppConfig
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size

/**
 * Mandatory sign-in screen shown whenever there is no Google account signed in.
 * After [onIdToken] completes, the caller finishes Firebase Auth and shows the main UI.
 * Debug builds also expose email/password test login via [onEmailPassword].
 */
@Composable
fun SignInGate(
    onGoogleSignInClick: () -> Unit,
    onEmailPassword: ((email: String, password: String, createAccount: Boolean) -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    isSigningIn: Boolean = false,
    externalError: String? = null,
    modifier: Modifier = Modifier,
) {
    val googleSignInHelper: GoogleSignInHelper = koinInject()

    var errorResource by remember { mutableStateOf<StringResource?>(null) }
    var externalErrorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(externalError) {
        if (externalError != null) {
            externalErrorMessage = externalError
        }
    }
    var playServicesAvailable by remember { mutableStateOf(googleSignInHelper.isAvailable()) }
    var testEmail by remember { mutableStateOf("") }
    var testPassword by remember { mutableStateOf("") }
    // Matches the rule the backend already enforces: FirebaseSessionManager rejects email/password
    // outright unless AppConfig.isDebug. Gating only on the callback meant desktop rendered a
    // "Test login (debug)" form in release builds that could never succeed — DesktopSyncManager
    // answers every email sign-in with UnsupportedOperationException.
    val showTestLogin = onEmailPassword != null && AppConfig.isDebug

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xxxl, vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandMark(
                modifier = Modifier.size(56.dp),
                backgroundColor = MaterialTheme.colorScheme.onSurface,
                stripeColor = MaterialTheme.colorScheme.surface,
                ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(Size.iconLarge))
            Text(
                text = stringResource(Res.string.sign_in_gate_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(Res.string.sign_in_gate_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.xxxl))
            AnimatedVisibility(
                visible = !playServicesAvailable,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(Res.string.sign_in_no_play_services),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = Spacing.lg)
                    )
                    OutlinedButton(
                        onClick = { playServicesAvailable = googleSignInHelper.isAvailable() }
                    ) {
                        Text(stringResource(Res.string.action_retry), fontWeight = FontWeight.SemiBold)
                    }
                    if (onSkip != null) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        TextButton(onClick = onSkip) {
                            Text(
                                stringResource(Res.string.sign_in_skip_offline),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = playServicesAvailable,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val displayError = externalErrorMessage ?: errorResource?.let { stringResource(it) }
                    AnimatedVisibility(
                        visible = displayError != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Text(
                            text = displayError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = Spacing.lg)
                        )
                    }
                    Button(
                        onClick = {
                            errorResource = null
                            externalErrorMessage = null
                            onGoogleSignInClick()
                        },
                        enabled = !isSigningIn,
                        shape = MaterialTheme.shapes.large
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Size.iconMedium),
                                strokeWidth = Spacing.xxs,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock, // More stable
                                contentDescription = null,
                                modifier = Modifier.size(Size.iconMedium),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(stringResource(Res.string.cloud_sign_in_google), fontWeight = FontWeight.SemiBold)
                    }

                    // The subtitle above promises local-only use, so the way out has to be here,
                    // next to the sign-in button — not only in the no-Play-Services branch below,
                    // which never renders on a healthy machine.
                    if (onSkip != null) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        TextButton(onClick = onSkip, enabled = !isSigningIn) {
                            Text(
                                stringResource(Res.string.sign_in_skip_offline),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showTestLogin) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = stringResource(Res.string.test_login_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = testEmail,
                    onValueChange = { testEmail = it },
                    label = { Text(stringResource(Res.string.test_login_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = testPassword,
                    onValueChange = { testPassword = it },
                    label = { Text(stringResource(Res.string.test_login_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Button(
                        onClick = {
                            errorResource = null
                            externalErrorMessage = null
                            onEmailPassword!!(testEmail, testPassword, false)
                        },
                        enabled = !isSigningIn && testEmail.isNotBlank() && testPassword.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.test_login_sign_in))
                    }
                    OutlinedButton(
                        onClick = {
                            errorResource = null
                            externalErrorMessage = null
                            onEmailPassword!!(testEmail, testPassword, true)
                        },
                        enabled = !isSigningIn && testEmail.isNotBlank() && testPassword.length >= 6,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.test_login_create))
                    }
                }
                Text(
                    text = stringResource(Res.string.test_login_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}
