package com.aus.notelikeus.ui.navigation

import androidx.compose.runtime.Composable
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.main.UndoAction

/**
 * Opens the note editor in a separate OS window on desktop (mirroring the web app's "Float note"
 * layout as a real window). On Android this is a no-op — the editor always opens in-pane.
 */
interface EditorWindowLauncher {
    fun launch(noteId: Long?, initialColor: Int?)
}

/**
 * Platform window launcher. The desktop implementation composes an actual [androidx.compose.ui.window.Window]
 * per open note; the Android implementation returns a no-op launcher.
 *
 * @param onStageUndo called with the note snapshot when the editor stages an undo action, so the
 * main screen can show the "Note archived/trashed" snackbar with Undo.
 */
@Composable
expect fun rememberEditorWindowLauncher(
    onStageUndo: (note: Note, action: UndoAction, message: String) -> Unit
): EditorWindowLauncher
