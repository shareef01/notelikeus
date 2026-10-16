package com.aus.notelikeus.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size

/** Matches Supabase Auth's default `otp_length`. */
internal const val EMAIL_CODE_LENGTH = 6

/** Deliberately loose: the server is the judge. This only catches a missing @ or domain. */
private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

internal fun looksLikeEmail(value: String): Boolean = EMAIL_SHAPE.matches(value.trim())

/**
 * Sign-in screen shown until the user signs in or chooses local-only use.
 *
 * Sign-in is by emailed one-time code, in two steps: the address, then the code Supabase emails
 * to it. [codeSentTo] is null on the first step and the address on the second; the caller owns
 * that state so a code that was sent survives this composable leaving and re-entering.
 * Debug builds also expose email/password test login via [onEmailPassword].
 */
@Composable
fun SignInGate(
    codeSentTo: String?,
    onRequestCode: (email: String) -> Unit,
    onSubmitCode: (email: String, code: String) -> Unit,
    onUseDifferentEmail: () -> Unit,
    onEmailPassword: ((email: String, password: String, createAccount: Boolean) -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    isSigningIn: Boolean = false,
    externalError: String? = null,
    modifier: Modifier = Modifier,
) {
    var errorResource by remember { mutableStateOf<StringResource?>(null) }
    var externalErrorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(externalError) {
        if (externalError != null) {
            externalErrorMessage = externalError
        }
    }
    var email by remember { mutableStateOf(codeSentTo.orEmpty()) }
    var code by remember { mutableStateOf("") }
    val codeFocus = remember { FocusRequester() }

    // A new code invalidates whatever was typed for the last one, and the code field is the only
    // thing left to do, so it takes focus.
    LaunchedEffect(codeSentTo) {
        code = ""
        if (codeSentTo != null) {
            email = codeSentTo
            runCatching { codeFocus.requestFocus() }
        }
    }

    // Matches the rule the backend already enforces: email/password is a debug/test path, and
    // SupabaseSessionManager refuses it outright unless AppConfig.isDebug.
    val showTestLogin = onEmailPassword != null && AppConfig.isDebug

    fun clearErrors() {
        errorResource = null
        externalErrorMessage = null
    }

    fun sendCode() {
        clearErrors()
        if (!looksLikeEmail(email)) {
            errorResource = Res.string.sign_in_invalid_email
            return
        }
        onRequestCode(email.trim())
    }

    fun submitCode() {
        val sentTo = codeSentTo ?: return
        clearErrors()
        if (code.length != EMAIL_CODE_LENGTH) {
            errorResource = Res.string.sign_in_invalid_code
            return
        }
        onSubmitCode(sentTo, code)
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
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

            Column(
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GateError(externalErrorMessage, errorResource)

                if (codeSentTo == null) {
                    EmailStep(
                        email = email,
                        onEmailChange = {
                            email = it
                            errorResource = null
                        },
                        isError = errorResource == Res.string.sign_in_invalid_email,
                        isSigningIn = isSigningIn,
                        onSend = ::sendCode
                    )
                } else {
                    CodeStep(
                        sentTo = codeSentTo,
                        code = code,
                        onCodeChange = {
                            code = it
                            errorResource = null
                        },
                        isError = errorResource == Res.string.sign_in_invalid_code,
                        isSigningIn = isSigningIn,
                        focusRequester = codeFocus,
                        onSubmit = ::submitCode,
                        onUseDifferentEmail = {
                            clearErrors()
                            onUseDifferentEmail()
                        },
                        onResend = {
                            clearErrors()
                            onRequestCode(codeSentTo)
                        }
                    )
                }

                // The subtitle above promises local-only use, so the way out has to be here,
                // next to the sign-in form.
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

            if (showTestLogin) {
                TestLoginForm(
                    isSigningIn = isSigningIn,
                    onSubmit = { testEmail, testPassword, create ->
                        clearErrors()
                        onEmailPassword!!(testEmail, testPassword, create)
                    }
                )
            }
        }
    }
}

/** The gate's one error line, announced politely when it appears or changes. */
@Composable
private fun GateError(serverMessage: String?, localError: StringResource?) {
    val message = serverMessage ?: localError?.let { stringResource(it) }
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = Spacing.lg)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/** The gate's primary action: a full-width button that swaps its label for a spinner while busy. */
@Composable
private fun ProgressButton(
    label: String,
    isBusy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val busyLabel = stringResource(Res.string.sign_in_busy)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(Size.iconMedium)
                    .semantics { contentDescription = busyLabel },
                strokeWidth = Spacing.xxs,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmailStep(
    email: String,
    onEmailChange: (String) -> Unit,
    isError: Boolean,
    isSigningIn: Boolean,
    onSend: () -> Unit,
) {
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text(stringResource(Res.string.sign_in_email_label)) },
        supportingText = { Text(stringResource(Res.string.sign_in_email_hint)) },
        isError = isError,
        singleLine = true,
        enabled = !isSigningIn,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Send
        ),
        keyboardActions = KeyboardActions(onSend = { if (!isSigningIn) onSend() }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.EmailAddress }
    )
    Spacer(modifier = Modifier.height(Spacing.md))
    ProgressButton(
        label = stringResource(Res.string.sign_in_send_code),
        isBusy = isSigningIn,
        enabled = !isSigningIn && email.isNotBlank(),
        onClick = onSend
    )
}

@Composable
private fun CodeStep(
    sentTo: String,
    code: String,
    onCodeChange: (String) -> Unit,
    isError: Boolean,
    isSigningIn: Boolean,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onUseDifferentEmail: () -> Unit,
    onResend: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.sign_in_code_sent, sentTo),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        // Announced when the step changes, so a screen-reader user hears where the code went
        // without hunting for it.
        modifier = Modifier
            .padding(bottom = Spacing.md)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
    OutlinedTextField(
        value = code,
        onValueChange = { input -> onCodeChange(input.filter(Char::isDigit).take(EMAIL_CODE_LENGTH)) },
        label = { Text(stringResource(Res.string.sign_in_code_label)) },
        supportingText = { Text(stringResource(Res.string.sign_in_code_spam_hint)) },
        isError = isError,
        singleLine = true,
        enabled = !isSigningIn,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { if (!isSigningIn) onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            // Lets Android offer the code from the email or SMS notification as autofill.
            .semantics { contentType = ContentType.SmsOtpCode }
    )
    Spacer(modifier = Modifier.height(Spacing.md))
    ProgressButton(
        label = stringResource(Res.string.sign_in_verify_code),
        isBusy = isSigningIn,
        enabled = !isSigningIn && code.length == EMAIL_CODE_LENGTH,
        onClick = onSubmit
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onUseDifferentEmail, enabled = !isSigningIn) {
            Text(stringResource(Res.string.sign_in_use_different_email))
        }
        TextButton(onClick = onResend, enabled = !isSigningIn) {
            Text(stringResource(Res.string.sign_in_resend_code))
        }
    }
}

/** Debug-only email/password login, used by local development and the tests. */
@Composable
private fun TestLoginForm(
    isSigningIn: Boolean,
    onSubmit: (email: String, password: String, createAccount: Boolean) -> Unit,
) {
    var testEmail by remember { mutableStateOf("") }
    var testPassword by remember { mutableStateOf("") }
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
            onClick = { onSubmit(testEmail, testPassword, false) },
            enabled = !isSigningIn && testEmail.isNotBlank() && testPassword.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(Res.string.test_login_sign_in))
        }
        OutlinedButton(
            onClick = { onSubmit(testEmail, testPassword, true) },
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
