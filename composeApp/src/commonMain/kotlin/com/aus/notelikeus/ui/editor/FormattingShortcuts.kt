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
import androidx.compose.ui.text.input.TextFieldValue

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

/**
 * Keeps the body's selection when focus leaves it for the formatting toolbar.
 *
 * Compose collapses a text field's selection to its end as the field loses focus. With the
 * toolbar's buttons in the focus order, reaching Bold by Tab — or clicking it with a mouse on
 * desktop, which focuses the button — left nothing selected, and Bold inserted an empty `****` at
 * the caret instead of wrapping the words the user had chosen. Measured on a Pixel 7: selection,
 * Shift+Tab to Bold, Enter gave `_**hello******_`.
 *
 * Compose may deliver the collapse before or after its own focus-change callback, so both orders
 * are handled: a collapse that arrives after [onFocusChanged] reported the loss is refused, and one
 * that arrived before it is undone by the value [onFocusChanged] returns.
 */
class BlurSelectionKeeper {
    private var focused = false
    private var beforeCollapse: TextFieldValue? = null

    /** Whether to pass [new] on as the body's value, given the value [old] it replaces. */
    fun accept(old: TextFieldValue, new: TextFieldValue): Boolean {
        val collapse = isBlurCollapse(old, new)
        if (collapse && !focused) return false
        beforeCollapse = if (collapse) old else null
        return true
    }

    /**
     * Reports the body's focus. On a loss, returns the value to restore when a collapse slipped in
     * just before it, or null when there is nothing to undo. [current] is only compared by text.
     */
    fun onFocusChanged(isFocused: Boolean, current: TextFieldValue): TextFieldValue? {
        focused = isFocused
        val previous = beforeCollapse
        beforeCollapse = null
        if (isFocused || previous == null || previous.text != current.text) return null
        // [current] may not show the collapse yet: on Android the screen reads its state before
        // recomposing, so it still held the old selection when this ran (logged on a Pixel 7).
        return previous
    }

    private fun isBlurCollapse(old: TextFieldValue, new: TextFieldValue): Boolean =
        new.text == old.text &&
            !old.selection.collapsed &&
            new.selection.collapsed &&
            new.selection.start == old.selection.max
}
