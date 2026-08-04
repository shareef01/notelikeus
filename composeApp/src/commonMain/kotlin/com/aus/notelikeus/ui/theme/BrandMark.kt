package com.aus.notelikeus.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    stripeColor: Color = Color.White,
    circular: Boolean = true
) {
    val shapeModifier = if (circular) Modifier.clip(CircleShape) else Modifier
    Box(
        modifier = modifier
            .then(shapeModifier)
            .background(backgroundColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = 5
            val barWidth = size.width * 0.07f
            val gap = size.width * 0.055f
            val totalWidth = barCount * barWidth + (barCount - 1) * gap
            var x = (size.width - totalWidth) / 2f
            val top = size.height * 0.2f
            val height = size.height * 0.6f
            repeat(barCount) { index ->
                val alpha = when (index) {
                    0, 4 -> 0.72f
                    2 -> 1f
                    else -> 0.88f
                }
                drawRect(
                    color = stripeColor.copy(alpha = alpha),
                    topLeft = Offset(x, top),
                    size = Size(barWidth, height)
                )
                x += barWidth + gap
            }
        }
    }
}

@Composable
fun BrandMarkIcon(
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    stripeColor: Color = Color.White
) {
    BrandMark(
        modifier = modifier.size(size),
        backgroundColor = backgroundColor,
        stripeColor = stripeColor,
        circular = true
    )
}
