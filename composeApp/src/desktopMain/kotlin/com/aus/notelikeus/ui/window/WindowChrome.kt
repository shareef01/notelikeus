package com.aus.notelikeus.ui.window

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import com.aus.notelikeus.ui.theme.Chrome

/**
 * Windows-style caption bar drawn by the app instead of the OS.
 *
 * The native bar is a fixed light strip that ignored the app's theme entirely, so a dark or
 * true-dark window wore a grey header. Drawing it here keeps the whole window one surface and
 * lets the caption buttons follow Windows 11 conventions — 46x32 hit targets, a neutral hover
 * wash, and a red close.
 *
 * Not reproducible in Compose: the Snap Layouts flyout on maximize-button hover, which needs
 * native WM_NCHITTEST handling.
 */
@Composable
fun FrameWindowScope.NotelikeusTitleBar(
    title: String,
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    onNewNote: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).background(colors.surface)) {
        // Native caption drag: hands presses to the OS so the window keeps Aero Snap
        // docking, snap previews and restore-by-drag. Caption buttons are excluded.
        NativeCaptionDragSupport(
            zoneHeight = TITLE_BAR_HEIGHT,
            excludedEndWidth = CAPTION_BUTTON_WIDTH * 4, // overflow, minimize, maximize, close
            onToggleMaximize = onToggleMaximize
        )

        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMarkIcon(
                size = 18.dp,
                backgroundColor = colors.onSurface.copy(alpha = 0.12f),
                stripeColor = colors.onSurface
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                CaptionButton(
                    onClick = { menuOpen = true },
                    hoverColor = colors.onSurface.copy(alpha = Chrome.SoftWash),
                    contentColor = colors.onSurface
                ) { color -> DrawOverflow(color) }

                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New note") },
                        trailingIcon = {
                            Text(
                                "Ctrl+N",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        },
                        onClick = { menuOpen = false; onNewNote() }
                    )
                    DropdownMenuItem(
                        text = { Text("About Notelikeus") },
                        onClick = { menuOpen = false; onAbout() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Exit") },
                        onClick = { menuOpen = false; onClose() }
                    )
                }
            }

            CaptionButton(
                onClick = onMinimize,
                hoverColor = colors.onSurface.copy(alpha = Chrome.SoftWash),
                contentColor = colors.onSurface
            ) { color -> DrawMinimize(color) }

            CaptionButton(
                onClick = onToggleMaximize,
                hoverColor = colors.onSurface.copy(alpha = Chrome.SoftWash),
                contentColor = colors.onSurface
            ) { color -> if (isMaximized) DrawRestore(color) else DrawMaximize(color) }

            CaptionButton(
                onClick = onClose,
                hoverColor = CLOSE_HOVER,
                contentColor = colors.onSurface,
                hoverContentColor = Color.White
            ) { color -> DrawClose(color) }
        }

        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            color = colors.onSurface.copy(alpha = Chrome.CardHairline)
        )
    }
}

/**
 * A caption button sized to the Windows 11 metric (46x32 inside a 40dp bar) so the pointer
 * targets feel native even though the drawing is ours.
 */
@Composable
private fun CaptionButton(
    onClick: () -> Unit,
    hoverColor: Color,
    contentColor: Color,
    hoverContentColor: Color? = null,
    content: @Composable (Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered) hoverColor else Color.Transparent,
        label = "captionHover"
    )
    val tint = if (hovered && hoverContentColor != null) hoverContentColor else contentColor

    Box(
        modifier = Modifier
            .width(CAPTION_BUTTON_WIDTH)
            .fillMaxHeight()
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        content(tint)
    }
}

@Composable
private fun DrawMinimize(color: Color) {
    Canvas(Modifier.size(GLYPH)) {
        val y = size.height / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = STROKE_PX)
    }
}

@Composable
private fun DrawMaximize(color: Color) {
    Canvas(Modifier.size(GLYPH)) {
        drawRect(color = color, style = Stroke(width = STROKE_PX))
    }
}

@Composable
private fun DrawRestore(color: Color) {
    Canvas(Modifier.size(GLYPH)) {
        val inset = size.width * 0.25f
        // Front pane.
        drawRect(
            color = color,
            topLeft = Offset(0f, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset, size.height - inset),
            style = Stroke(width = STROKE_PX)
        )
        // The back pane, drawn as the two edges that would peek out from behind the front one.
        drawLine(color, Offset(inset, inset), Offset(inset, 0f), STROKE_PX)
        drawLine(color, Offset(inset, 0f), Offset(size.width, 0f), STROKE_PX)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height - inset), STROKE_PX)
        drawLine(
            color,
            Offset(size.width, size.height - inset),
            Offset(size.width - inset, size.height - inset),
            STROKE_PX
        )
    }
}

@Composable
private fun DrawClose(color: Color) {
    Canvas(Modifier.size(GLYPH)) {
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), STROKE_PX)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), STROKE_PX)
    }
}

@Composable
private fun DrawOverflow(color: Color) {
    Canvas(Modifier.size(GLYPH)) {
        val r = STROKE_PX
        val cy = size.height / 2
        listOf(0f, size.width / 2, size.width).forEach { cx ->
            drawCircle(color, radius = r, center = Offset(cx, cy))
        }
    }
}

/** Windows 11's close-button red. */
private val CLOSE_HOVER = Color(0xFFC42B1C)
private val TITLE_BAR_HEIGHT = 40.dp
private val CAPTION_BUTTON_WIDTH = 46.dp
private val GLYPH = 10.dp
private const val STROKE_PX = 1.4f

/** Placement helper so callers do not repeat the maximize/restore toggle. */
fun WindowPlacement.toggledMaximize(): WindowPlacement =
    if (this == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
