package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier

/**
 * Desktop-only: adds mouse-wheel support to a horizontal scrollable, so the filter row scrolls
 * sideways instead of letting the wheel fall through to the notes grid below.
 *
 * No-op on Android, where touch drag is the primary input. Lived in FilterRow.kt until that file
 * was deleted; it is a platform capability rather than one row's private helper.
 */
expect fun Modifier.wheelHorizontalScroll(state: ScrollState): Modifier
