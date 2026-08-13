package com.aus.notelikeus

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.aus.notelikeus.platform.ForegroundActivityTracker
import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.aus.notelikeus.ui.navigation.extractEditorNoteId
import com.aus.notelikeus.ui.navigation.intentRequestsNewNote
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {

    private val googleSignInHelper: GoogleSignInHelper by inject()
    private var pendingNoteId by mutableStateOf<Long?>(null)
    private var pendingCreateNote by mutableStateOf(false)
    private var navigationRequest by mutableStateOf(0L)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNoteId = extractEditorNoteId(intent)
        pendingCreateNote = intentRequestsNewNote(intent)
        navigationRequest++
        enableEdgeToEdge()
        // Credential Manager needs an Activity to host its sign-in sheet; the helper is a
        // process-scoped singleton, so it looks the Activity up through this tracker.
        ForegroundActivityTracker.register(this)
        
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val scope = rememberCoroutineScope()

            App(
                windowSizeClass = windowSizeClass,
                onShowBiometricPrompt = { title, onSuccess, onError ->
                    showBiometricPrompt(title, onSuccess, onError)
                },
                onGoogleSignInClick = { viewModel ->
                    // Credential Manager presents its own UI from a coroutine, so there is no
                    // Intent to launch and no ActivityResult to parse.
                    scope.launch {
                        googleSignInHelper.requestIdToken()
                            .onSuccess { idToken -> viewModel.signInWithGoogleIdToken(idToken) }
                            .onFailure { error ->
                                viewModel.reportGoogleSignInFailure(error)
                            }
                    }
                },
                pendingNoteId = pendingNoteId,
                pendingCreateNote = pendingCreateNote,
                navigationRequest = navigationRequest
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNoteId = extractEditorNoteId(intent)
        pendingCreateNote = intentRequestsNewNote(intent)
        navigationRequest++
    }

    override fun onDestroy() {
        ForegroundActivityTracker.unregister(this)
        super.onDestroy()
    }

    private fun showBiometricPrompt(
        title: String,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
