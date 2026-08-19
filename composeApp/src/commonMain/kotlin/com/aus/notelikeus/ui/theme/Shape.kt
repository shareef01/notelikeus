package com.aus.notelikeus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * [lg] is the app's signature: 18dp on every note card, FAB and sheet, matching the web client's
 * `rounded-note`. The rest exist so smaller chrome has somewhere to land other than an ad-hoc
 * literal.
 *
 * These are raw dp values as well as [Shapes] entries because plenty of call sites clip or
 * border directly — `Modifier.clip(RoundedCornerShape(Radius.md))` — and cannot reach through
 * `MaterialTheme.shapes`.
 */
object Radius {
    val none = 0.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp

    /** Note cards, FABs, bottom sheets. The one radius the product is recognisable by. */
    val lg = 18.dp

    /**
     * Fully rounded. Not `CircleShape`, for rows that are wider than they are tall and need the
     * ends capped rather than the whole thing turned into an ellipse.
     */
    val pill = 999.dp
}

/**
 * Geometry: an 18dp corner system, applied uniformly to note cards, floating action buttons and
 * bottom sheets.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.lg)
)

/** Pre-built pill shape, so call sites do not each construct one. */
val PillShape = RoundedCornerShape(Radius.pill)
