package com.aus.notelikeus.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Ctrl+B and Ctrl+I (Cmd on a Mac keyboard) in the note body, as in every other editor.
 *
 * The toolbar's buttons were out of the focus order and there was no shortcut, so a keyboard-only
 * user on Windows — or on Android with a hardware keyboard — had no way to format at all. The web
 * client binds the same two keys.
 */
fun Modifier.formattingShortcuts(onBold: () -> Unit, onItalic: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val command = event.isCtrlPressed || event.isMetaPressed
        if (!command || event.isAltPressed || event.isShiftPressed) return@onPreviewKeyEvent false
        when (event.key) {
            Key.B -> {
                onBold()
                true
            }
            Key.I -> {
                onItalic()
                true
            }
            else -> false
        }
    }
