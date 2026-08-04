package com.aus.notelikeus.ui.auth

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.ui.theme.BrandMark
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject

interface GoogleSignInHelper {
    fun isPlayServicesAvailable(): Boolean
    fun getSignInIntent(): Any?
    fun parseIdToken(data: Any?): Result<String>
}

private fun describeSignInError(error: Throwable): StringResource {
    return Res.string.cloud_sign_in_failed
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
    val googleSignInHelper: GoogleSignInHelper = koinInject()

    var isSigningIn by remember { mutableStateOf(false) }
    var errorResource by remember { mutableStateOf<StringResource?>(null) }
    var externalErrorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(externalError) {
        if (externalError != null) {
            externalErrorMessage = externalError
            isSigningIn = false
        }
    }
    var playServicesAvailable by remember { mutableStateOf(googleSignInHelper.isPlayServicesAvailable()) }
    var testEmail by remember { mutableStateOf("") }
    var testPassword by remember { mutableStateOf("") }
    val showTestLogin = onEmailPassword != null // Simplified for KMP cleanup

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleSignInHelper.parseIdToken(result.data)
            .onSuccess {
                errorResource = null
                externalErrorMessage = null
                onIdToken(it)
            }
            .onFailure {
                isSigningIn = false
                errorResource = describeSignInError(it)
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
                text = stringResource(Res.string.sign_in_gate_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.sign_in_gate_subtitle),
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
                        text = stringResource(Res.string.sign_in_no_play_services),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedButton(
                        onClick = { playServicesAvailable = googleSignInHelper.isPlayServicesAvailable() }
                    ) {
                        Text(stringResource(Res.string.action_retry), fontWeight = FontWeight.SemiBold)
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
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                    Button(
                        onClick = {
                            errorResource = null
                            externalErrorMessage = null
                            isSigningIn = true
                            val intent = googleSignInHelper.getSignInIntent()
                            if (intent != null) {
                                signInLauncher.launch(intent)
                            }
                        },
                        enabled = !isSigningIn,
                        shape = MaterialTheme.shapes.large
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                painter = painterResource(Res.drawable.ic_google_g),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(stringResource(Res.string.cloud_sign_in_google), fontWeight = FontWeight.SemiBold)
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
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = testEmail,
                    onValueChange = { testEmail = it },
                    label = { Text(stringResource(Res.string.test_login_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = testPassword,
                    onValueChange = { testPassword = it },
                    label = { Text(stringResource(Res.string.test_login_password)) },
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
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
