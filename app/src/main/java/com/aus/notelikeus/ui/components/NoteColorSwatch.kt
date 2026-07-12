package com.aus.notelikeus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.ui.theme.getContentColor

@Composable
fun NoteColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    touchSize: Dp = 48.dp,
    swatchSize: Dp = 32.dp,
    contentDescription: String? = null
) {
    val description = contentDescription ?: if (isSelected) {
        stringResource(R.string.selected_color)
    } else {
        stringResource(R.string.cd_color_swatch)
    }
    val isDefault = color == Color.Transparent

    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .semantics { this.contentDescription = description }
            .clickableWithFeedback(onClick = onClick, rippleRadius = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(swatchSize)
                .clip(CircleShape)
                .background(if (isDefault) Color.Transparent else color)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDefault) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).semantics { invisibleToUser() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { invisibleToUser() },
                    tint = if (isDefault) MaterialTheme.colorScheme.primary else color.getContentColor()
                )
            }
        }
    }
}
