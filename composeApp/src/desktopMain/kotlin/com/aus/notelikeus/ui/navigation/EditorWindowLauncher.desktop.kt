package com.aus.notelikeus.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.ui.editor.EditorScreen
import com.aus.notelikeus.ui.editor.EditorViewModel
import com.aus.notelikeus.ui.main.UndoAction
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorForTheme
import com.aus.notelikeus.ui.window.NativeCaptionDragSupport
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

private data class EditorWindowRequest(
    val key: Int,
    val noteId: Long?,
    val initialColor: Int?
)

private val NoteTitleBarHeight = 40.dp
private val NoteCaptionButtonWidth = 46.dp
private val NoteChromeInset = 16.dp
private val NoteCornerRadius = 18.dp
private val NoteShadowElevation = 24.dp
private val CloseHover = Color(0xFFE81123)
private val CaptionStrokePx = 1.1f

@Composable
actual fun rememberEditorWindowLauncher(
    onStageUndo: (note: Note, action: UndoAction, message: String) -> Unit
): EditorWindowLauncher {
    val requests = remember { mutableStateListOf<EditorWindowRequest>() }
    var nextKey by remember { mutableIntStateOf(0) }

    requests.forEach { request ->
        key(request.key) {
            EditorNoteWindow(
                noteId = request.noteId,
                initialColor = request.initialColor,
                onStageUndo = onStageUndo,
                onClosed = { requests.remove(request) }
            )
        }
    }

    return remember {
        object : EditorWindowLauncher {
            override fun launch(noteId: Long?, initialColor: Int?) {
                requests += EditorWindowRequest(nextKey++, noteId, initialColor)
            }
        }
    }
}

/**
 * One independent OS window hosting the note editor, like the web app's float layout. Undecorated
 * and transparent so the app can draw its own chrome: a rounded card with the note's surface
 * colour, a hairline border and a soft shadow, plus a caption bar that supports native docking
 * (Aero Snap) and minimize.
 */
@Composable
private fun EditorNoteWindow(
    noteId: Long?,
    initialColor: Int?,
    onStageUndo: (note: Note, action: UndoAction, message: String) -> Unit,
    onClosed: () -> Unit
) {
    var closeRequested by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(
        width = 920.dp,
        height = 760.dp,
        position = WindowPosition(Alignment.Center)
    )

    Window(
        onCloseRequest = { closeRequested = true },
        state = windowState,
        undecorated = true,
        transparent = true,
        resizable = true
    ) {
        val settingsRepository: SettingsRepository = koinInject()
        val appTheme by settingsRepository.appTheme.collectAsState(initial = AppTheme.TRUE_DARK)

        NotelikeusTheme(appTheme = appTheme) {
            // Plain factory injection: standalone windows have no SavedStateRegistryOwner, so
            // koinViewModel cannot build the editor's SavedStateHandle here. Each call creates
            // an independent EditorViewModel scoped to this window's composition.
            val viewModel: EditorViewModel = koinInject(named("windowEditor"))
            val editorState by viewModel.state.collectAsState()

            val isDarkPalette = isNoteColorDarkTheme()
            val displayArgb = noteColorForTheme(editorState.color, isDarkPalette)
            val cardColor = if (displayArgb == 0) {
                MaterialTheme.colorScheme.surface
            } else {
                Color(displayArgb.toLong() and 0xffffffffL)
            }
            val cardContent = if (displayArgb == 0) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color(displayArgb).getContentColor(fallback = MaterialTheme.colorScheme.onSurface)
            }
            val isMaximized = windowState.placement == WindowPlacement.Maximized
            // Maximized fills the screen edge-to-edge: no rounded corners and no shadow.
            val chromeInset = if (isMaximized) 0.dp else NoteChromeInset
            val cornerRadius = if (isMaximized) 0.dp else NoteCornerRadius
            val cardShape = RoundedCornerShape(cornerRadius)

            LaunchedEffect(noteId, initialColor) {
                viewModel.setRouteArgs(noteId, initialColor)
            }
            LaunchedEffect(closeRequested) {
                if (closeRequested) {
                    viewModel.saveNote()
                    onClosed()
                }
            }

            // The card is inset from the transparent window edges so the shadow has room
            // to render; everything inside is clipped to the rounded shape.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(chromeInset)
                    .shadow(
                        elevation = NoteShadowElevation,
                        shape = cardShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.30f),
                        spotColor = Color.Black.copy(alpha = 0.30f)
                    )
                    .background(cardColor, cardShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = cardShape
                    )
                    .clip(cardShape)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    NoteWindowTitleBar(
                        title = editorState.title.ifBlank { "Notelikeus" },
                        contentColor = cardContent,
                        cornerRadius = cornerRadius,
                        isMaximized = isMaximized,
                        onMinimize = { windowState.isMinimized = true },
                        onToggleMaximize = {
                            windowState.placement = if (isMaximized) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                        },
                        onClose = { closeRequested = true }
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        EditorScreen(
                            viewModel = viewModel,
                            onBack = { closeRequested = true },
                            onStageUndo = onStageUndo,
                            isExpanded = false
                        )
                    }
                }
            }
        }
    }
}

/** Drawn caption bar matching the main window's chrome, with native drag/dock support. */
@Composable
private fun FrameWindowScope.NoteWindowTitleBar(
    title: String,
    contentColor: Color,
    cornerRadius: Dp,
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NoteTitleBarHeight)
            .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
    ) {
        // Native caption drag: the OS owns the drag so the window can be docked against
        // screen edges/corners. The caption buttons on the right are excluded.
        NativeCaptionDragSupport(
            zoneHeight = NoteTitleBarHeight,
            excludedEndWidth = NoteCaptionButtonWidth * 3, // minimize, maximize, close
            onToggleMaximize = onToggleMaximize
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMarkIcon(
                size = 14.dp,
                backgroundColor = contentColor.copy(alpha = 0.16f),
                stripeColor = contentColor,
                modifier = Modifier.alpha(0.9f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NoteCaptionButton(
                onClick = onMinimize,
                hoverColor = contentColor.copy(alpha = 0.08f),
                contentColor = contentColor
            ) { color -> DrawMinimizeGlyph(color) }

            NoteCaptionButton(
                onClick = onToggleMaximize,
                hoverColor = contentColor.copy(alpha = 0.08f),
                contentColor = contentColor
            ) { color -> if (isMaximized) DrawRestoreGlyph(color) else DrawMaximizeGlyph(color) }

            NoteCaptionButton(
                onClick = onClose,
                hoverColor = CloseHover,
                contentColor = contentColor,
                hoverContentColor = Color.White
            ) { color -> DrawCloseGlyph(color) }
        }

        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            color = contentColor.copy(alpha = 0.14f)
        )
    }
}

@Composable
private fun NoteCaptionButton(
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
        label = "noteCaptionHover"
    )
    val tint = if (hovered && hoverContentColor != null) hoverContentColor else contentColor

    Box(
        modifier = Modifier
            .width(NoteCaptionButtonWidth)
            .fillMaxHeight()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        content(tint)
    }
}

@Composable
private fun DrawMinimizeGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = CaptionStrokePx)
    }
}

@Composable
private fun DrawMaximizeGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawRect(color = color, style = Stroke(width = CaptionStrokePx))
    }
}

@Composable
private fun DrawRestoreGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val inset = size.width * 0.25f
        drawRect(
            color = color,
            topLeft = Offset(0f, inset),
            size = Size(size.width - inset, size.height - inset),
            style = Stroke(width = CaptionStrokePx)
        )
        drawLine(color, Offset(inset, inset), Offset(inset, 0f), CaptionStrokePx)
        drawLine(color, Offset(inset, 0f), Offset(size.width, 0f), CaptionStrokePx)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height - inset), CaptionStrokePx)
        drawLine(
            color,
            Offset(size.width, size.height - inset),
            Offset(size.width - inset, size.height - inset),
            CaptionStrokePx
        )
    }
}

@Composable
private fun DrawCloseGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawLine(color, Offset.Zero, Offset(size.width, size.height), strokeWidth = CaptionStrokePx)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = CaptionStrokePx)
    }
}
