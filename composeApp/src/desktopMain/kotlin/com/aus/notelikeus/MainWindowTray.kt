package com.aus.notelikeus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.WindowState
import com.aus.notelikeus.util.SingleInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.tray_exit
import notelikeus.composeapp.generated.resources.tray_new_note
import notelikeus.composeapp.generated.resources.tray_open
import notelikeus.composeapp.generated.resources.tray_still_running_message
import notelikeus.composeapp.generated.resources.tray_still_running_title
import org.jetbrains.compose.resources.stringResource

/**
 * Whether the main window is showing, and what its close button does (NL-01).
 *
 * Closing the window used to exit, and with it every reminder still waiting in the in-process
 * timer. While one is waiting, [close] hides the window instead and the app stays in the tray;
 * Exit in the tray menu is the way to quit. With nothing waiting, closing still exits, so the app
 * does not linger for no reason.
 */
internal class MainWindowController(
    private val hasPendingReminders: () -> Boolean,
    private val exit: () -> Unit,
    private val explainTray: () -> Unit,
    private val restore: () -> Unit = {},
) {
    var isVisible by mutableStateOf(true)
        private set

    /** Bumped to bring an already-visible window to the front, which needs the AWT window itself. */
    var bringToFrontRequest by mutableLongStateOf(0L)
        private set

    private var hasExplainedTray = false

    fun show() {
        isVisible = true
        restore()
        bringToFrontRequest++
    }

    fun close() {
        if (!hasPendingReminders()) {
            exit()
            return
        }
        isVisible = false
        if (!hasExplainedTray) {
            hasExplainedTray = true
            explainTray()
        }
    }
}

@Composable
internal fun ApplicationScope.rememberMainWindowController(
    hasPendingReminders: () -> Boolean,
    trayState: TrayState,
    windowState: WindowState,
): MainWindowController {
    val title = stringResource(Res.string.tray_still_running_title)
    val message = stringResource(Res.string.tray_still_running_message)
    return remember(trayState, windowState) {
        MainWindowController(
            hasPendingReminders = hasPendingReminders,
            exit = ::exitApplication,
            explainTray = {
                trayState.sendNotification(Notification(title, message, Notification.Type.Info))
            },
            restore = { windowState.isMinimized = false },
        )
    }
}

/**
 * The tray icon and its menu, and the hand-over from a second launch: Start menu or taskbar asks
 * this copy to show itself instead of opening the database a second time (see [SingleInstance]).
 */
@Composable
internal fun ApplicationScope.NotelikeusTray(
    controller: MainWindowController,
    trayState: TrayState,
    icon: Painter,
    instance: SingleInstance?,
    onNewNote: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    DisposableEffect(instance) {
        // The request arrives on a watcher thread.
        val listener = instance?.listenForActivation {
            scope.launch(Dispatchers.Main) { controller.show() }
        }
        onDispose { listener?.close() }
    }

    Tray(
        state = trayState,
        icon = icon,
        tooltip = "Notelikeus",
        onAction = controller::show,
        menu = {
            Item(stringResource(Res.string.tray_open), onClick = controller::show)
            Item(stringResource(Res.string.tray_new_note), onClick = {
                onNewNote()
                controller.show()
            })
            Item(stringResource(Res.string.tray_exit), onClick = ::exitApplication)
        }
    )
}
