package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Elevation

@Composable
fun RichTextToolbar(
    onBoldClick: () -> Unit,
    onItalicClick: () -> Unit,
    onListClick: () -> Unit,
    onChecklistClick: () -> Unit,
    onLinkClick: () -> Unit,
    onAddImageClick: (() -> Unit)? = null,
    contentColor: Color,
    surfaceColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    /** The note body. Bold, Italic and List hand focus back to it, so typing carries on. */
    returnFocusTo: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    // The surface is used exactly as given. It used to be `surfaceColor.copy(alpha = 0.95f)`,
    // which quietly turned the editor's Color.Transparent — RGB(0,0,0) with alpha 0 — into 95%
    // opaque black, so a slab sat over every coloured note instead of the intended tint.
    //
    // Elevation is only for an *opaque* fill, and the guard used to be `alpha == 0f`. A shadow
    // under a translucent surface is visible through it, so the editor's 7%-alpha wash was
    // rendering as a mid-grey slab: measured 189 on white, where the tint alone is about 239.
    // That is the same "panel floating over the note" the wash exists to avoid, arriving by a
    // different route than the alpha did.
    //
    // The buttons are in the focus order. They were taken out of it (`canFocus = false`), which
    // left a keyboard user no way to reach Link, List, Checklist or Image at all. That had been
    // done because on desktop a mouse click focuses the button and takes the caret out of the
    // body; the edits that stay in the body now return focus to it instead
    // (RichTextToolbarKeyboardTest). The selection is held in state, so it survives the round trip.
    val isTranslucent = surfaceColor.alpha < 1f
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = surfaceColor,
        tonalElevation = if (isTranslucent) Elevation.none else Elevation.raised,
        shadowElevation = if (isTranslucent) Elevation.none else Elevation.card,
        border = androidx.compose.foundation.BorderStroke(
            Spacing.hairline,
            contentColor.copy(alpha = NoteEmphasis.Decorative)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            IconButton(onClick = { onBoldClick(); returnFocusTo?.requestFocus() }) {
                Icon(Icons.Default.FormatBold, contentDescription = stringResource(Res.string.format_bold), tint = contentColor)
            }
            IconButton(onClick = { onItalicClick(); returnFocusTo?.requestFocus() }) {
                Icon(Icons.Default.FormatItalic, contentDescription = stringResource(Res.string.format_italic), tint = contentColor)
            }
            IconButton(onClick = onLinkClick) {
                Icon(Icons.Default.Link, contentDescription = stringResource(Res.string.format_link), tint = contentColor)
            }
            VerticalDivider(
                modifier = Modifier.height(Size.iconLarge).padding(horizontal = Spacing.xs),
                color = contentColor.copy(alpha = NoteEmphasis.Decorative)
            )
            IconButton(onClick = { onListClick(); returnFocusTo?.requestFocus() }) {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(Res.string.format_list), tint = contentColor)
            }
            IconButton(onClick = onChecklistClick) {
                Icon(Icons.Default.Checklist, contentDescription = stringResource(Res.string.format_checklist), tint = contentColor)
            }
            if (onAddImageClick != null) {
                IconButton(onClick = onAddImageClick) {
                    Icon(Icons.Default.Image, contentDescription = stringResource(Res.string.format_image), tint = contentColor)
                }
            }
        }
    }
}
