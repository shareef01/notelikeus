package com.aus.notelikeus.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Brand glyph: disc with surface-colored bars so contrast holds in every theme
 * (light = dark disc + light bars; dark = light disc + dark bars).
 *
 * Five full-opacity bars with rounded caps, matching the web BrandMark
 * (bar width ≈ 8% of the mark, gap ≈ 6%, height ≈ 50%). Opacity fades are
 * deliberately not used — they smudged into a blob at small sizes.
 *
 * @param ringColor optional 1.dp rim around the disc so its edge contrasts with the
 * background; pass the theme's secondary/onSurfaceVariant at ~50% alpha to mirror the
 * web's ring-brand-secondary/50.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    stripeColor: Color = Color.White,
    circular: Boolean = true,
    ringColor: Color = Color.Transparent
) {
    val shapeModifier = if (circular) Modifier.clip(CircleShape) else Modifier
    val ringModifier = if (circular && ringColor != Color.Transparent) {
        Modifier.border(1.dp, ringColor, CircleShape)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(shapeModifier)
            .then(ringModifier)
            .background(backgroundColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = 5
            val barWidth = size.width * 0.08f
            val gap = size.width * 0.06f
            val totalWidth = barCount * barWidth + (barCount - 1) * gap
            var x = (size.width - totalWidth) / 2f
            val top = size.height * 0.25f
            val height = size.height * 0.5f
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
            repeat(barCount) {
                drawRoundRect(
                    color = stripeColor,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, height),
                    cornerRadius = corner
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
    stripeColor: Color = Color.White,
    ringColor: Color = Color.Transparent
) {
    BrandMark(
        modifier = modifier.size(size),
        backgroundColor = backgroundColor,
        stripeColor = stripeColor,
        circular = true,
        ringColor = ringColor
    )
}
