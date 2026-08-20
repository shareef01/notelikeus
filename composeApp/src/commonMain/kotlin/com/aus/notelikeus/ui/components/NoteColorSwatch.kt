package com.aus.notelikeus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size

@Composable
fun NoteColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    touchSize: Dp = Size.touchTarget,
    swatchSize: Dp = Spacing.xxxl,
    contentDescription: String? = null
) {
    val description = contentDescription ?: if (isSelected) {
        stringResource(Res.string.selected_color)
    } else {
        stringResource(Res.string.cd_color_swatch)
    }
    val isDefault = color == Color.Transparent

    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .semantics { this.contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(swatchSize)
                .clip(CircleShape)
                .background(if (isDefault) MaterialTheme.colorScheme.surfaceVariant else color)
                .border(
                    width = if (isSelected) Spacing.xxs else Spacing.hairline,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.ChipBorder)
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDefault) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    modifier = Modifier.size(Size.iconSmall).semantics { hideFromAccessibility() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(Size.iconSmall)
                        .semantics { hideFromAccessibility() },
                    tint = if (isDefault) MaterialTheme.colorScheme.primary else color.getContentColor()
                )
            }
        }
    }
}
