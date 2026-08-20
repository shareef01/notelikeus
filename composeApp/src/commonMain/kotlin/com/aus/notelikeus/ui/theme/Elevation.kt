package com.aus.notelikeus.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Elevation steps, named by the job rather than the number.
 *
 * The values match what the screens already used — cards rest flat and lift on hover, menus and
 * sheets sit above everything — but they were spelled as bare literals at each call site, so
 * "how high does a hovered card sit" had no answer except grep.
 *
 * Depth in this app is deliberately shallow. On the dark themes a shadow is nearly invisible
 * against a near-black background, so separation comes from surface tone and hairline outlines;
 * elevation is a secondary cue, not the primary one. That is why nothing here exceeds 6dp.
 */
object Elevation {
    /** Flat against its parent. The resting state of every note card. */
    val none = 0.dp

    /** Barely lifted: tonal elevation on a menu, a pressed-but-not-raised control. */
    val raised = 1.dp

    /** A selected card, or a FAB at rest. */
    val card = 2.dp

    /** Pointer hover on a FAB or a focused control. */
    val hover = 3.dp

    /** A card the user is dragging, or a pressed FAB. */
    val dragging = 4.dp

    /** Menus, dropdowns and sheets — anything drawn over the content it describes. */
    val overlay = 6.dp
}
