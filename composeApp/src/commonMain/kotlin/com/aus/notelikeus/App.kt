package com.aus.notelikeus

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.aus.notelikeus.ui.auth.SignInGate
import com.aus.notelikeus.ui.main.CloudSyncEvent
import com.aus.notelikeus.ui.main.MainViewModel
import com.aus.notelikeus.ui.navigation.NavGraph
import com.aus.notelikeus.ui.navigation.Screen
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import org.jetbrains.compose.resources.stringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, KoinExperimentalAPI::class)
@Composable
fun App(
    windowSizeClass: WindowSizeClass,
    onShowBiometricPrompt: (title: String, onSuccess: () -> Unit, onError: () -> Unit) -> Unit,
    onGoogleSignInClick: (MainViewModel) -> Unit,
    onExportBackup: (MainViewModel) -> Unit = {},
    onImportBackup: (MainViewModel) -> Unit = {},
    pendingNoteId: Long? = null,
    pendingCreateNote: Boolean = false,
    navigationRequest: Long = 0L
) {
    val viewModel: MainViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    
    var isUnlocked by remember { mutableStateOf(false) }
    var needsUnlock by remember { mutableStateOf(false) }
    var hasInitializedLock by remember { mutableStateOf(false) }

    val unlockAppLabel = stringResource(Res.string.unlock_app)

    LaunchedEffect(state.areSettingsLoaded, state.isAppLockEnabled) {
        if (!state.areSettingsLoaded || hasInitializedLock) return@LaunchedEffect
        hasInitializedLock = true
        if (state.isAppLockEnabled) {
            needsUnlock = true
        } else {
            isUnlocked = true
        }
    }

    LaunchedEffect(needsUnlock) {
        if (needsUnlock && state.isAppLockEnabled) {
            needsUnlock = false
            onShowBiometricPrompt(
                unlockAppLabel,
                { isUnlocked = true },
                { isUnlocked = false }
            )
        }
    }

    NotelikeusTheme(
        appTheme = state.appTheme
    ) {
        var gateError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state.pendingCloudSyncEvent) {
            when (val event = state.pendingCloudSyncEvent) {
                is CloudSyncEvent.Failure -> {
                    gateError = event.message
                    viewModel.clearPendingCloudSyncEvent()
                }
                else -> Unit
            }
        }
        
        if (!state.cloudAccount.isGoogleAccount) {
            SignInGate(
                onGoogleSignInClick = { onGoogleSignInClick(viewModel) },
                isSigningIn = state.isSigningIn,
                onEmailPassword = { email, password, create ->
                    viewModel.signInWithEmailPassword(email, password, create)
                },
                externalError = gateError
            )
        } else {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    
                    var internalPendingCreateNote by remember { mutableStateOf(false) }
                    var internalPendingNoteId by remember { mutableStateOf<Long?>(null) }
                    // Sync with external triggers on each composition
                    if (pendingCreateNote) internalPendingCreateNote = true
                    pendingNoteId?.let { internalPendingNoteId = it }

                    LaunchedEffect(navigationRequest, isUnlocked, state.isAppLockEnabled) {
                        if (navigationRequest == 0L) return@LaunchedEffect
                        if (state.isAppLockEnabled && !isUnlocked) return@LaunchedEffect
                        
                        if (internalPendingCreateNote) {
                            navController.navigate(Screen.Editor.createRoute(null)) {
                                popUpTo(Screen.Main.route) { saveState = true }
                                launchSingleTop = true
                            }
                            internalPendingCreateNote = false
                        }
                        internalPendingNoteId?.let { id ->
                            navController.navigate(Screen.Editor.createRoute(id)) {
                                popUpTo(Screen.Main.route) { saveState = true }
                                launchSingleTop = true
                            }
                            internalPendingNoteId = null
                        }
                    }
                    
                    NavGraph(
                        navController = navController,
                        mainViewModel = viewModel,
                        windowSizeClass = windowSizeClass,
                        isAppLockEnabled = state.isAppLockEnabled,
                        onRequestAppUnlock = { onSuccess ->
                            onShowBiometricPrompt(
                                unlockAppLabel,
                                {
                                    isUnlocked = true
                                    onSuccess()
                                },
                                { }
                            )
                        },
                        onAppLockEnabled = { isUnlocked = true },
                        onExportBackup = { onExportBackup(viewModel) },
                        onImportBackup = { onImportBackup(viewModel) }
                    )

                    if (!isUnlocked) {
                        AppLockOverlay(
                            showLockPrompt = state.areSettingsLoaded && state.isAppLockEnabled,
                            onUnlock = {
                                onShowBiometricPrompt(
                                    unlockAppLabel,
                                    { isUnlocked = true },
                                    { }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLockOverlay(showLockPrompt: Boolean, onUnlock: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        if (!showLockPrompt) return@Surface
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = stringResource(Res.string.app_lock_title),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.app_lock_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.app_lock_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onUnlock) {
                Text(
                    stringResource(Res.string.unlock_app),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
