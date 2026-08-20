package com.aus.notelikeus.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The 4dp spacing grid.
 *
 * The scale is not invented: it is what the screens were already converging on. Counting every
 * `.dp` literal under `ui/` before this file existed, the top six values were 8 (41 uses), 16
 * (41), 12 (37), 4 (28), 24 (16) and 32 (12) — a clean 4dp grid — with a long tail of 6, 10, 14,
 * 22 and 26 that were one-offs rather than decisions. This names the grid so the tail stops
 * growing.
 *
 * [hairline] is here for convenience, not because 1dp is a spacing step: borders and dividers are
 * the only things that should use it.
 */
object Spacing {
    val none = 0.dp

    /** Borders and dividers only. Never padding. */
    val hairline = 1.dp

    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Horizontal inset from the screen edge. The one value that must stay consistent. */
    val gutter = 16.dp

    /** Inside a note card, all four sides. */
    val cardPadding = 20.dp

    /**
     * Bottom inset for a snackbar sharing the screen with the FAB, so it clears rather than
     * covers it. 56dp FAB + 16dp margin + 16dp gap.
     */
    val snackbarAboveFab = 88.dp
}

/**
 * Fixed component dimensions.
 *
 * Distinct from [Spacing] because these are not multiples of a grid — they are minimums, optical
 * sizes and reading measures, each with its own reason to be the number it is. Mixing them into
 * the spacing scale would invite someone to "round" a 48dp touch target down to 44.
 */
object Size {
    /**
     * The minimum interactive target, per WCAG 2.5.8 and Material's own guidance.
     *
     * A control may *look* smaller — the colour swatches paint 26dp inside a 48dp target — but
     * nothing tappable may be smaller than this.
     */
    val touchTarget = 48.dp

    // ---- icons ----
    val iconTiny = 14.dp
    val iconSmall = 16.dp
    val iconMedium = 18.dp
    val icon = 20.dp
    val iconLarge = 24.dp

    // ---- controls ----

    /** Pills and segmented controls that sit inside a row rather than owning one. */
    val controlHeight = 36.dp

    val chipHeightCompact = 40.dp
    val chipHeight = 44.dp

    /** A row that owns its line in the top bar. */
    val topBarRowHeight = 44.dp

    /** A tappable row in the drawer, a sheet or a settings list. */
    val listRowHeight = 48.dp

    /** The painted circle of a note-colour swatch, inside a [touchTarget]-sized hit area. */
    val swatch = 26.dp

    // ---- layout ----

    /**
     * Maximum width for single-column content on a wide window.
     *
     * At the body size in Type.kt this is roughly 80 characters a line — near the top of the
     * comfortable range. Without it, a note body on a maximised desktop window runs the full
     * window width and becomes the widest thing on screen.
     */
    val readingMeasure = 720.dp

    /** Below this, an extra grid column costs more in legibility than it gains in density. */
    val gridMinCardWidth = 300.dp
}
