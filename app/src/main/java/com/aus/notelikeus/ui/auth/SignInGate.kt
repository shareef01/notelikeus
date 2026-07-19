package com.aus.notelikeus.ui.auth

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.BuildConfig
import com.aus.notelikeus.R
import com.aus.notelikeus.di.GoogleSignInEntryPoint
import com.aus.notelikeus.ui.theme.BrandMark
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import dagger.hilt.android.EntryPointAccessors

private fun describeSignInError(context: Context, error: Throwable): String {
    val statusCode = (error as? ApiException)?.statusCode
    return when (statusCode) {
        GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> context.getString(R.string.sign_in_cancelled)
        GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS ->
            context.getString(R.string.sign_in_in_progress)
        CommonStatusCodes.NETWORK_ERROR -> context.getString(R.string.sign_in_network_error)
        else -> context.getString(R.string.cloud_sign_in_failed)
    }
}

/**
 * Mandatory sign-in screen shown whenever there is no Google account signed in.
 * After [onIdToken] completes, the caller finishes Firebase Auth and shows the main UI.
 * Debug builds also expose email/password test login via [onEmailPassword].
 */
@Composable
fun SignInGate(
    onIdToken: (String) -> Unit,
    onEmailPassword: ((email: String, password: String, createAccount: Boolean) -> Unit)? = null,
    externalError: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val googleSignInHelper = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            GoogleSignInEntryPoint::class.java
        ).googleSignInHelper()
    }

    var isSigningIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(externalError) {
        if (externalError != null) {
            error = externalError
            isSigningIn = false
        }
    }
    var playServicesAvailable by remember { mutableStateOf(googleSignInHelper.isPlayServicesAvailable()) }
    var testEmail by remember { mutableStateOf("") }
    var testPassword by remember { mutableStateOf("") }
    val showTestLogin = BuildConfig.DEBUG && onEmailPassword != null

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleSignInHelper.parseIdToken(result.data)
            .onSuccess {
                error = null
                onIdToken(it)
            }
            .onFailure {
                isSigningIn = false
                error = describeSignInError(context, it)
            }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandMark(
                modifier = Modifier.size(72.dp),
                backgroundColor = MaterialTheme.colorScheme.onSurface,
                stripeColor = MaterialTheme.colorScheme.surface
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.sign_in_gate_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.sign_in_gate_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            AnimatedVisibility(
                visible = !playServicesAvailable,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.sign_in_no_play_services),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedButton(
                        onClick = { playServicesAvailable = googleSignInHelper.isPlayServicesAvailable() }
                    ) {
                        Text(stringResource(R.string.action_retry), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            AnimatedVisibility(
                visible = playServicesAvailable,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedVisibility(
                        visible = error != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                    Button(
                        onClick = {
                            error = null
                            isSigningIn = true
                            signInLauncher.launch(googleSignInHelper.getSignInIntent())
                        },
                        enabled = !isSigningIn
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.cloud_sign_in_google), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (showTestLogin) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.test_login_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = testEmail,
                    onValueChange = { testEmail = it },
                    label = { Text(stringResource(R.string.test_login_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = testPassword,
                    onValueChange = { testPassword = it },
                    label = { Text(stringResource(R.string.test_login_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            error = null
                            onEmailPassword!!(testEmail, testPassword, false)
                        },
                        enabled = !isSigningIn && testEmail.isNotBlank() && testPassword.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.test_login_sign_in))
                    }
                    OutlinedButton(
                        onClick = {
                            error = null
                            onEmailPassword!!(testEmail, testPassword, true)
                        },
                        enabled = !isSigningIn && testEmail.isNotBlank() && testPassword.length >= 6,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.test_login_create))
                    }
                }
                Text(
                    text = stringResource(R.string.test_login_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
