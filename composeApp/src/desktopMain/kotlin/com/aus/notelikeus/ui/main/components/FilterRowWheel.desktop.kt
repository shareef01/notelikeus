package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * A wheel notch only reports a few pixels of `scrollDelta`, so the delta is amplified to a
 * comfortable horizontal scroll speed (trackpads report larger, smoother deltas and stay
 * controllable).
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.wheelHorizontalScroll(state: ScrollState): Modifier =
    onPointerEvent(PointerEventType.Scroll) { event ->
        var verticalDelta = 0f
        event.changes.forEach { change -> verticalDelta += change.scrollDelta.y }
        if (verticalDelta != 0f) {
            state.dispatchRawDelta(verticalDelta * 10f)
            event.changes.forEach { it.consume() }
        }
    }
