package com.aus.notelikeus.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.main.UndoAction

/** The editor always opens in-pane on Android; nothing to launch. */
@Composable
actual fun rememberEditorWindowLauncher(
    onStageUndo: (note: Note, action: UndoAction, message: String) -> Unit
): EditorWindowLauncher = remember {
    object : EditorWindowLauncher {
        override fun launch(noteId: Long?, initialColor: Int?) = Unit
    }
}
