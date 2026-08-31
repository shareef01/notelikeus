package com.aus.notelikeus

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.aus.notelikeus.data.local.DatabaseRecoveryNotice
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.platform.ForegroundActivityTracker
import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.aus.notelikeus.ui.navigation.extractEditorNoteId
import com.aus.notelikeus.ui.navigation.intentRequestsNewNote
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf

class MainActivity : FragmentActivity() {

    private val googleSignInHelper: GoogleSignInHelper by inject()
    private val settingsRepository: SettingsRepository by inject()
    private var pendingNoteId by mutableStateOf<Long?>(null)
    private var pendingCreateNote by mutableStateOf(false)
    // mutableLongStateOf, not mutableStateOf: this is a counter bumped on every deep link and
    // every widget tap, and the generic version boxes a java.lang.Long on each one.
    private var navigationRequest by mutableLongStateOf(0L)
    private var showDatabaseRecoveryNotice by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            !AppStartup.isReady.value
        }
        super.onCreate(savedInstanceState)
        pendingNoteId = extractEditorNoteId(intent)
        pendingCreateNote = intentRequestsNewNote(intent)
        navigationRequest++
        enableEdgeToEdge()
        // Credential Manager needs an Activity to host its sign-in sheet; the helper is a
        // process-scoped singleton, so it looks the Activity up through this tracker.
        ForegroundActivityTracker.register(this)
        applySecureFlagWhileAppLockEnabled()
        
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val scope = rememberCoroutineScope()

            // Nothing below may run before AppStartup: the first koinViewModel() call resolves
            // the DAO, which opens the encrypted database. Until the background open finishes,
            // composing nothing keeps the window on the system splash screen instead of
            // blocking Main on the key-manager decrypt or a first-run re-encryption.
            // Collected rather than read directly: AppStartup publishes through a flow so its
            // value can be written from the background startup thread without creating snapshot
            // state there. collectAsState creates that state here, in composition, on the main
            // thread.
            val isStartupReady by AppStartup.isReady.collectAsState()
            if (isStartupReady) {
                // The quarantine that records this notice runs during the database open, so it
                // can only be read once that has finished — not in onCreate.
                LaunchedEffect(Unit) {
                    showDatabaseRecoveryNotice =
                        DatabaseRecoveryNotice.pending(this@MainActivity) != null
                }

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

                // Shown once, over the app, when the database had to be moved aside during startup.
                // Without this the user sees an empty note list and no reason for it.
                if (showDatabaseRecoveryNotice) {
                    AlertDialog(
                        onDismissRequest = { dismissDatabaseRecoveryNotice() },
                        confirmButton = {
                            TextButton(onClick = { dismissDatabaseRecoveryNotice() }) {
                                Text(stringResource(R.string.db_recovery_dismiss))
                            }
                        },
                        title = { Text(stringResource(R.string.db_recovery_title)) },
                        text = { Text(stringResource(R.string.db_recovery_message)) }
                    )
                }
            }
        }
    }

    /**
     * Mirrors the App Lock setting onto FLAG_SECURE.
     *
     * App Lock gates the UI behind BiometricPrompt, but without this the note list is still
     * readable from the recents switcher and still screenshottable — the lock is bypassed by
     * pressing the recents key, which is not much of a lock for a notes app. FLAG_SECURE blanks
     * the recents thumbnail and blocks capture.
     *
     * Tied to the setting rather than set unconditionally: it also blocks screen recording and
     * casting, which is a real cost to impose on someone who never asked to lock the app.
     */
    private fun applySecureFlagWhileAppLockEnabled() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.isAppLockEnabled
                    .distinctUntilChanged()
                    .collect { locked ->
                        if (locked) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
            }
        }
    }

    private fun dismissDatabaseRecoveryNotice() {
        showDatabaseRecoveryNotice = false
        DatabaseRecoveryNotice.consume(this)
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
