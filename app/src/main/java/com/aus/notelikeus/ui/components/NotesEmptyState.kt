package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.ui.theme.BrandMarkIcon

private val EmptyStateIconSize = 72.dp
private const val EmptyStateIconAlpha = 0.2f

@Composable
fun NotesEmptyState(
    message: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    showCreateButton: Boolean = false,
    showClearFilters: Boolean = false,
    onCreateClick: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            EmptyStateVisual(
                icon = icon,
                contentDescription = message
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium, // Medium weight for visibility
                    textAlign = TextAlign.Center
                ),
                color = mutedTextColor.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium, // Medium weight for visibility
                        textAlign = TextAlign.Center
                    ),
                    color = mutedTextColor.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )
            }
            if (showCreateButton) {
                Spacer(modifier = Modifier.height(28.dp))
                FilledTonalButton(onClick = onCreateClick) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_note),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_note))
                    }
                }
            }
            if (showClearFilters) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onClearFilters) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FilterAltOff,
                            contentDescription = stringResource(R.string.clear_filters),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.clear_filters))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateVisual(
    icon: ImageVector?,
    contentDescription: String
) {
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(EmptyStateIconSize)
                .semantics { this.contentDescription = contentDescription },
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = EmptyStateIconAlpha)
        )
    } else {
        BrandMarkIcon(
            size = EmptyStateIconSize,
            backgroundColor = MaterialTheme.colorScheme.onSurface,
            stripeColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.alpha(EmptyStateIconAlpha)
        )
    }
}
