package com.aus.notelikeus.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

private const val EDITOR_SLIDE_MS = 240
private const val EDITOR_FADE_IN_MS = 140
private const val EDITOR_FADE_OUT_MS = 120
private const val MAIN_FADE_OUT_MS = 80
private const val MAIN_FADE_IN_MS = 160

fun AnimatedContentTransitionScope<NavBackStackEntry>.editorEnterTransition(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt().coerceAtLeast(1) },
        animationSpec = tween(EDITOR_SLIDE_MS, easing = FastOutSlowInEasing),
    ) + fadeIn(tween(EDITOR_FADE_IN_MS, easing = FastOutSlowInEasing))

fun AnimatedContentTransitionScope<NavBackStackEntry>.editorPopExitTransition(): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt().coerceAtLeast(1) },
        animationSpec = tween(EDITOR_SLIDE_MS, easing = FastOutSlowInEasing),
    ) + fadeOut(tween(EDITOR_FADE_OUT_MS, easing = FastOutSlowInEasing))

fun AnimatedContentTransitionScope<NavBackStackEntry>.mainExitForEditorTransition(): ExitTransition =
    fadeOut(tween(MAIN_FADE_OUT_MS, easing = FastOutSlowInEasing))

fun AnimatedContentTransitionScope<NavBackStackEntry>.mainPopEnterFromEditorTransition(): EnterTransition =
    fadeIn(tween(MAIN_FADE_IN_MS, easing = FastOutSlowInEasing))

private fun NavBackStackEntry.isEditorDestination(): Boolean =
    destination.route?.startsWith("editor") == true

fun AnimatedContentTransitionScope<NavBackStackEntry>.mainExitTransition(): ExitTransition =
    if (targetState.isEditorDestination()) {
        mainExitForEditorTransition()
    } else {
        fadeOut(tween(180))
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.mainPopEnterTransition(): EnterTransition =
    if (initialState.isEditorDestination()) {
        mainPopEnterFromEditorTransition()
    } else {
        fadeIn(tween(180))
    }
