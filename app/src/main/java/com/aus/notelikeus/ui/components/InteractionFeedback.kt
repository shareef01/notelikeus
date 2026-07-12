package com.aus.notelikeus.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DefaultRippleRadius = 24.dp

@Composable
fun rememberConstrainedRipple(radius: Dp = DefaultRippleRadius) =
    ripple(bounded = true, radius = radius)

@Composable
fun Modifier.clickableWithFeedback(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = null,
    hapticType: HapticFeedbackType = HapticFeedbackType.ContextClick,
    rippleRadius: Dp = DefaultRippleRadius,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Modifier {
    val haptic = LocalHapticFeedback.current
    return clickable(
        interactionSource = interactionSource,
        indication = rememberConstrainedRipple(rippleRadius),
        enabled = enabled,
        role = role,
        onClick = {
            haptic.performHapticFeedback(hapticType)
            onClick()
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.combinedClickableWithFeedback(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickHaptic: HapticFeedbackType = HapticFeedbackType.ContextClick,
    onLongClickHaptic: HapticFeedbackType = HapticFeedbackType.LongPress,
    rippleRadius: Dp = 28.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Modifier {
    val haptic = LocalHapticFeedback.current
    return combinedClickable(
        interactionSource = interactionSource,
        indication = rememberConstrainedRipple(rippleRadius),
        enabled = enabled,
        onClick = {
            haptic.performHapticFeedback(onClickHaptic)
            onClick()
        },
        onLongClick = onLongClick?.let { longClick ->
            {
                haptic.performHapticFeedback(onLongClickHaptic)
                longClick()
            }
        },
    )
}

fun HapticFeedback.performItemToggleFeedback(isChecked: Boolean) {
    performHapticFeedback(
        if (isChecked) HapticFeedbackType.TextHandleMove else HapticFeedbackType.ContextClick
    )
}
