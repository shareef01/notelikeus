package com.aus.notelikeus

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.aus.notelikeus.ui.window.NotelikeusTitleBar
import com.aus.notelikeus.ui.window.toggledMaximize
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import androidx.compose.ui.window.Notification
import com.aus.notelikeus.di.initKoin
import com.aus.notelikeus.platform.DesktopBiometricPrompt
import com.aus.notelikeus.platform.DesktopReminderManager
import com.aus.notelikeus.data.backup.BackupExportResult
import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.util.SidebarCollapsedStore
import com.aus.notelikeus.util.WindowMetrics
import com.aus.notelikeus.util.WindowMetricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.awt.GraphicsEnvironment
import java.awt.Point
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun main() {
    // Not inside application { }: that block is composable and re-runs whenever the state
    // remembered in it changes (Ctrl+N, the tray item, the About dialog), and startKoin throws
    // KoinApplicationAlreadyStartedException the second time.
    initKoin()
    // Read before the window exists, so the first frame is already the right size rather than
    // resizing itself once a flow emits.
    val metricsStore = GlobalContext.get().get<WindowMetricsStore>()
    val sidebarStore = GlobalContext.get().get<SidebarCollapsedStore>()
    launchApp(
        metricsStore,
        runBlocking { metricsStore.metrics.first() },
        sidebarStore,
        runBlocking { sidebarStore.collapsed.first() }
    )
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
private fun launchApp(
    metricsStore: WindowMetricsStore,
    initialMetrics: WindowMetrics,
    sidebarStore: SidebarCollapsedStore,
    initialSidebarCollapsed: Boolean
) = application {
    val windowState = rememberWindowState(
        width = initialMetrics.width,
        height = initialMetrics.height,
        // With undecorated chrome the drag area is part of the window, so a position that puts
        // the top edge above y=0 clips the title bar off-screen and leaves no way to drag the
        // window back with the mouse. restorablePosition() centres rather than restore one.
        position = restorablePosition(initialMetrics),
        placement = if (initialMetrics.isMaximized) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        }
    )

    // The last size and position seen while floating. While maximized the window reports the
    // screen's bounds, which must not be saved as the size to restore to when un-maximizing.
    var floatingMetrics by remember { mutableStateOf(initialMetrics) }

    LaunchedEffect(windowState.size, windowState.position, windowState.placement) {
        // Settle first: a drag-resize churns through hundreds of intermediate values.
        delay(500.milliseconds)
        val position = windowState.position
        if (windowState.placement == WindowPlacement.Floating && windowState.size.isSpecified) {
            floatingMetrics = WindowMetrics(
                width = windowState.size.width,
                height = windowState.size.height,
                // Still unresolved from Aligned: keep whatever we last knew rather than
                // dropping the save entirely, which would lose the resize as well.
                x = (position as? WindowPosition.Absolute)?.x ?: floatingMetrics.x,
                y = (position as? WindowPosition.Absolute)?.y ?: floatingMetrics.y
            )
        }
        metricsStore.saveMetrics(
            floatingMetrics.copy(isMaximized = windowState.placement == WindowPlacement.Maximized)
        )
    }

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
    // Side drawer collapse is a manual preference, so persist it the moment the user toggles it
    // (mirrors the web app's persisted `sidebarCollapsed`) rather than on some later settle.
    val onSidebarCollapsedChange: (Boolean) -> Unit = { collapsed ->
        coroutineScope.launch { sidebarStore.save(collapsed) }
    }
    LaunchedEffect(Unit) {
        reminderManager.notify = { title, message ->
            trayState.sendNotification(Notification(title, message, Notification.Type.Info))
        }
        reminderManager.restoreScheduledReminders()
    }

    if (showAboutDialog) {
        com.aus.notelikeus.platform.DesktopAboutDialog(
            onDismiss = { showAboutDialog = false },
            version = AppConfig.versionName
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

    val newNote: () -> Unit = {
        pendingCreateNote = true
        navigationRequest++
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Notelikeus",
        icon = appIcon,
        // The OS caption bar is a fixed light strip that cannot follow the app's theme, so the
        // window draws its own (see NotelikeusTitleBar) and keeps one continuous surface.
        undecorated = true,
        resizable = true,
        // Replaces the MenuBar's Ctrl+N accelerator, which went away with the native chrome.
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.N) {
                newNote()
                true
            } else {
                false
            }
        }
    ) {
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
            initialSidebarCollapsed = initialSidebarCollapsed,
            onSidebarCollapsedChange = onSidebarCollapsedChange,
            onShowBiometricPrompt = { title, onSuccess, onCancel ->
                biometricTitle = title
                biometricOnSuccess = onSuccess
                biometricOnCancel = onCancel
            },
            onGoogleSignInClick = { viewModel ->
                coroutineScope.launch {
                    googleSignInHelper.requestIdToken()
                        .onSuccess {
                            // The helper already exchanged the Google token and saved the
                            // Supabase session. completeExternalSignIn runs account isolation.
                            viewModel.completeExternalSignIn()
                        }
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
            navigationRequest = navigationRequest,
            titleBar = {
                NotelikeusTitleBar(
                    title = "Notelikeus",
                    isMaximized = windowState.placement == WindowPlacement.Maximized,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = {
                        windowState.placement = windowState.placement.toggledMaximize()
                    },
                    onClose = ::exitApplication,
                    onNewNote = newNote,
                    onAbout = { showAboutDialog = true }
                )
            }
        )

        LaunchedEffect(navigationRequest) {
            if (navigationRequest > 0) {
                delay(100.milliseconds)
                pendingCreateNote = false
            }
        }
    }
}

/**
 * The saved position, unless it falls outside every attached display.
 *
 * After a second monitor is unplugged the stored coordinates can land the window somewhere the
 * user cannot reach — indistinguishable from the app failing to launch, and unrecoverable with
 * undecorated chrome, where dragging the window back means grabbing a title bar that is itself
 * off-screen. AWT reports display bounds in the same user-space units Compose uses for [Dp], so
 * they compare directly.
 */
private fun restorablePosition(metrics: WindowMetrics): WindowPosition {
    val x = metrics.x ?: return WindowPosition(Alignment.Center)
    val y = metrics.y ?: return WindowPosition(Alignment.Center)

    val topLeft = Point(x.value.roundToInt(), y.value.roundToInt())
    val onAnyScreen = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
            device.configurations.any { it.bounds.contains(topLeft) }
        }
    }.getOrDefault(false)

    return if (onAnyScreen) WindowPosition(x, y) else WindowPosition(Alignment.Center)
}
