package com.aus.notelikeus

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import androidx.compose.ui.window.Notification
import com.aus.notelikeus.di.initKoin
import com.aus.notelikeus.platform.DesktopBiometricPrompt
import com.aus.notelikeus.platform.DesktopReminderManager
import com.aus.notelikeus.data.backup.BackupExportResult
import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun main() {
    // Not inside application { }: that block is composable and re-runs whenever the state
    // remembered in it changes (Ctrl+N, the tray item, the About dialog), and startKoin throws
    // KoinApplicationAlreadyStartedException the second time.
    initKoin()
    launchApp()
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
private fun launchApp() = application {
    val windowState = rememberWindowState(
        width = 1000.dp,
        height = 800.dp
    )
    val trayState = rememberTrayState()
    
    var pendingCreateNote by remember { mutableStateOf(false) }
    var navigationRequest by remember { mutableLongStateOf(0L) }
    
    var biometricTitle by remember { mutableStateOf<String?>(null) }
    var biometricOnSuccess by remember { mutableStateOf<(() -> Unit)?>(null) }
    var biometricOnCancel by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    var showAboutDialog by remember { mutableStateOf(false) }

    val appIcon = org.jetbrains.compose.resources.painterResource(Res.drawable.ic_launcher)

    // java.util.Timer schedules die with the process, so the reminder set has to be rebuilt from
    // the database at every launch or a reminder set for tomorrow silently never fires.
    val reminderManager = remember { GlobalContext.get().get<DesktopReminderManager>() }
    val googleSignInHelper = remember { GlobalContext.get().get<GoogleSignInHelper>() }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        reminderManager.notify = { title, message ->
            trayState.sendNotification(Notification(title, message, Notification.Type.Info))
        }
        reminderManager.restoreScheduledReminders()
    }

    if (showAboutDialog) {
        com.aus.notelikeus.platform.DesktopAboutDialog(
            onDismiss = { showAboutDialog = false },
            version = "1.0.0"
        )
    }

    if (biometricTitle != null) {
        DesktopBiometricPrompt(
            title = biometricTitle!!,
            onSuccess = {
                biometricOnSuccess?.invoke()
                biometricTitle = null
            },
            onCancel = {
                biometricOnCancel?.invoke()
                biometricTitle = null
            }
        )
    }

    Tray(
        state = trayState,
        icon = appIcon,
        tooltip = "Notelikeus",
        menu = {
            Item("New Note", onClick = {
                pendingCreateNote = true
                navigationRequest++
                windowState.isMinimized = false
            })
            Item("Exit", onClick = ::exitApplication)
        }
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Notelikeus",
        icon = appIcon
    ) {
        MenuBar {
            Menu("File") {
                Item("New Note", shortcut = KeyShortcut(Key.N, ctrl = true), onClick = {
                    pendingCreateNote = true
                    navigationRequest++
                })
                Separator()
                Item("Exit", onClick = ::exitApplication)
            }
            Menu("Help") {
                Item("About", onClick = { showAboutDialog = true })
            }
        }

        // Reactively recalculate on every resize
        val windowSizeClass by remember {
            derivedStateOf {
                if (windowState.size.isSpecified) {
                    WindowSizeClass.calculateFromSize(windowState.size)
                } else {
                    WindowSizeClass.calculateFromSize(androidx.compose.ui.unit.DpSize(800.dp, 600.dp))
                }
            }
        }
        
        App(
            windowSizeClass = windowSizeClass,
            onShowBiometricPrompt = { title, onSuccess, onCancel ->
                biometricTitle = title
                biometricOnSuccess = onSuccess
                biometricOnCancel = onCancel
            },
            onGoogleSignInClick = { viewModel ->
                coroutineScope.launch {
                    googleSignInHelper.requestIdToken()
                        .onSuccess { idToken -> viewModel.signInWithGoogleIdToken(idToken) }
                        .onFailure { error -> viewModel.reportGoogleSignInFailure(error) }
                }
            },
            onExportBackup = { viewModel ->
                coroutineScope.launch(Dispatchers.IO) {
                    val result = viewModel.exportBackup()
                    if (result is BackupExportResult.Success) {
                        withContext(Dispatchers.Main) {
                            val chooser = javax.swing.JFileChooser().apply {
                                selectedFile = java.io.File("notelikeus-backup.json")
                            }
                            if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                chooser.selectedFile.writeText(result.json)
                            }
                        }
                    }
                }
            },
            onImportBackup = { viewModel ->
                coroutineScope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) {
                        val chooser = javax.swing.JFileChooser()
                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                            val json = chooser.selectedFile.readText()
                            launch(Dispatchers.IO) {
                                viewModel.importBackup(json)
                            }
                        }
                    }
                }
            },
            pendingCreateNote = pendingCreateNote,
            navigationRequest = navigationRequest
        )
        
        LaunchedEffect(navigationRequest) {
            if (navigationRequest > 0) {
                kotlinx.coroutines.delay(100.milliseconds)
                pendingCreateNote = false
            }
        }
    }
}
