package com.aus.notelikeus.ui.window

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

internal data class FloatingWindowBounds(
    val width: Dp,
    val height: Dp,
    val position: WindowPosition,
)

internal fun fitFloatingWindow(
    width: Dp,
    height: Dp,
    x: Dp? = null,
    y: Dp? = null,
    offset: Dp = 0.dp,
): FloatingWindowBounds {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val savedDisplay = if (x != null && y != null) {
        environment.screenDevices.firstOrNull {
            it.defaultConfiguration.bounds.contains(x.value.toDouble(), y.value.toDouble())
        }
    } else {
        null
    }
    val configuration = (savedDisplay ?: environment.defaultScreenDevice).defaultConfiguration
    val bounds = configuration.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    val left = bounds.x + insets.left
    val top = bounds.y + insets.top
    val availableWidth = (bounds.width - insets.left - insets.right).coerceAtLeast(1)
    val availableHeight = (bounds.height - insets.top - insets.bottom).coerceAtLeast(1)
    val fittedWidth = minOf(width, availableWidth.dp)
    val fittedHeight = minOf(height, availableHeight.dp)
    val maxX = left.dp + availableWidth.dp - fittedWidth
    val maxY = top.dp + availableHeight.dp - fittedHeight
    val preferredX = if (savedDisplay != null) requireNotNull(x) else (left.dp + maxX) / 2 + offset
    val preferredY = if (savedDisplay != null) requireNotNull(y) else (top.dp + maxY) / 2 + offset

    return FloatingWindowBounds(
        width = fittedWidth,
        height = fittedHeight,
        position = WindowPosition(preferredX.coerceIn(left.dp, maxX), preferredY.coerceIn(top.dp, maxY)),
    )
}
