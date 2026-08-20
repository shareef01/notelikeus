package com.aus.notelikeus.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * Durations and easings for every animation in the app.
 *
 * Before this file there was no motion system at all — not an inconsistent one. All 25
 * `animate*AsState` calls under `ui/` used the library default spec, and there was not a single
 * explicit `tween` or `spring` anywhere in the module. That reads as "consistent" only because
 * nothing had an opinion; the moment two screens needed different timing they would each have
 * invented one.
 *
 * Four durations is the whole vocabulary, deliberately. Anything that needs a fifth is probably
 * a transition that should not exist.
 *
 * Respecting the system's "remove animations" accessibility setting is a separate concern and
 * lands in the accessibility pass — it needs a per-platform read of the animator scale, which
 * has no common API. This file is where that gate will hang once it exists, so call sites should
 * take their specs from here rather than constructing their own.
 */
object Motion {

    /** A state flip with no travel: a checkbox, a tint swap. */
    const val InstantMs = 100

    /** Hover, press and selection feedback. Must not feel laggy under a pointer. */
    const val QuickMs = 150

    /** The default. Chip selection, card colour, expanding and collapsing chrome. */
    const val StandardMs = 250

    /** Deliberate, noticed movement: a sheet, a pane change, an item entering the list. */
    const val EmphasisMs = 400

    /**
     * Material 3's standard easing. Starts briskly, settles slowly — the right curve for
     * something that begins and ends on screen.
     */
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For content entering the screen: full speed at the start, gentle arrival. */
    val DecelerateEasing: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** For content leaving: gentle departure, gone quickly. Never use for entrances. */
    val AccelerateEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    fun <T> instant(): FiniteAnimationSpec<T> =
        tween(durationMillis = InstantMs, easing = StandardEasing)

    fun <T> quick(): FiniteAnimationSpec<T> =
        tween(durationMillis = QuickMs, easing = StandardEasing)

    fun <T> standard(): FiniteAnimationSpec<T> =
        tween(durationMillis = StandardMs, easing = StandardEasing)

    fun <T> emphasis(): FiniteAnimationSpec<T> =
        tween(durationMillis = EmphasisMs, easing = StandardEasing)

    fun <T> enter(): FiniteAnimationSpec<T> =
        tween(durationMillis = StandardMs, easing = DecelerateEasing)

    fun <T> exit(): FiniteAnimationSpec<T> =
        tween(durationMillis = QuickMs, easing = AccelerateEasing)
}
