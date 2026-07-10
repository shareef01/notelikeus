package com.aus.notelikeus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.aus.notelikeus.ui.main.MainViewModel
import com.aus.notelikeus.ui.navigation.NavGraph
import com.aus.notelikeus.ui.navigation.Screen
import com.aus.notelikeus.ui.navigation.extractEditorNoteId
import com.aus.notelikeus.ui.navigation.intentRequestsNewNote
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private var pendingNoteId by mutableStateOf<Long?>(null)
    private var pendingCreateNote by mutableStateOf(false)
    private var navigationRequest by mutableStateOf(0L)
    private var showNotificationRationale by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            showNotificationRationale = true
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNoteId = extractEditorNoteId(intent)
        pendingCreateNote = intentRequestsNewNote(intent)
        navigationRequest++
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val viewModel: MainViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            var isUnlocked by remember { mutableStateOf(true) }
            var needsUnlock by remember { mutableStateOf(false) }
            var hasInitializedLock by remember { mutableStateOf(false) }
            val lifecycleOwner = LocalLifecycleOwner.current

            LaunchedEffect(state.isAppLockEnabled) {
                if (!hasInitializedLock) {
                    hasInitializedLock = true
                    if (state.isAppLockEnabled) {
                        isUnlocked = false
                        needsUnlock = true
                    }
                }
            }

            DisposableEffect(lifecycleOwner, state.isAppLockEnabled) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            if (state.isAppLockEnabled) isUnlocked = false
                        }
                        Lifecycle.Event.ON_START -> {
                            if (state.isAppLockEnabled && !isUnlocked) {
                                needsUnlock = true
                            }
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(needsUnlock) {
                if (needsUnlock && state.isAppLockEnabled) {
                    needsUnlock = false
                    showBiometricPrompt(
                        title = getString(R.string.unlock_app),
                        onSuccess = { isUnlocked = true },
                        onError = { isUnlocked = false }
                    )
                }
            }

            NotelikeusTheme(
                appTheme = state.appTheme,
                isTrueDarkMode = state.isTrueDarkMode, // Fallback/Legacy
                useMonochromeTheme = state.useMonochromeTheme
            ) {
                if (showNotificationRationale) {
                    AlertDialog(
                        onDismissRequest = { showNotificationRationale = false },
                        shape = MaterialTheme.shapes.large,
                        title = { Text(stringResource(R.string.notification_permission_title)) },
                        text = { Text(stringResource(R.string.reminder_permission_denied)) },
                        confirmButton = {
                            TextButton(onClick = { showNotificationRationale = false }) {
                                Text(stringResource(R.string.action_ok))
                            }
                        }
                    )
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        LaunchedEffect(navigationRequest, isUnlocked, state.isAppLockEnabled) {
                            if (navigationRequest == 0L) return@LaunchedEffect
                            if (state.isAppLockEnabled && !isUnlocked) return@LaunchedEffect
                            if (pendingCreateNote) {
                                navController.navigate(Screen.Editor.createRoute(null)) {
                                    popUpTo(Screen.Main.route) { saveState = true }
                                    launchSingleTop = true
                                }
                                pendingCreateNote = false
                            }
                            pendingNoteId?.let { id ->
                                navController.navigate(Screen.Editor.createRoute(id)) {
                                    popUpTo(Screen.Main.route) { saveState = true }
                                    launchSingleTop = true
                                }
                                pendingNoteId = null
                            }
                        }
                        NavGraph(
                            navController = navController,
                            mainViewModel = viewModel,
                            windowSizeClass = windowSizeClass,
                            isAppLockEnabled = state.isAppLockEnabled,
                            onRequestAppUnlock = { onSuccess ->
                                showBiometricPrompt(
                                    title = getString(R.string.unlock_app),
                                    onSuccess = {
                                        isUnlocked = true
                                        onSuccess()
                                    },
                                    onError = { }
                                )
                            },
                            onAppLockEnabled = { isUnlocked = true }
                        )

                        if (state.isAppLockEnabled && !isUnlocked) {
                            AppLockOverlay(
                                onUnlock = {
                                    showBiometricPrompt(
                                        title = getString(R.string.unlock_app),
                                        onSuccess = { isUnlocked = true },
                                        onError = { }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNoteId = extractEditorNoteId(intent)
        pendingCreateNote = intentRequestsNewNote(intent)
        navigationRequest++
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun showBiometricPrompt(
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
            .setSubtitle(getString(R.string.authenticate_subtitle))
            .setNegativeButtonText(getString(R.string.action_cancel))
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}

@Composable
private fun AppLockOverlay(onUnlock: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = stringResource(R.string.app_lock_title),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_lock_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onUnlock) {
                Text(
                    stringResource(R.string.unlock_app),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
