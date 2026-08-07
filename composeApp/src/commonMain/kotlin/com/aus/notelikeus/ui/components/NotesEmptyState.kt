package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import com.aus.notelikeus.ui.main.components.PrecisionFilterChip
import com.aus.notelikeus.ui.theme.BrandMarkIcon

private val EmptyStateIconSize = 72.dp
private const val EmptyStateIconAlpha = 0.28f

@Composable
fun NotesEmptyState(
    message: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    showCreateButton: Boolean = false,
    createButtonLabel: String? = null,
    showClearFilters: Boolean = false,
    recentSearches: List<String> = emptyList(),
    onRecentSearchClick: (String) -> Unit = {},
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
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                ),
                color = mutedTextColor.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    ),
                    color = mutedTextColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            if (showCreateButton) {
                val buttonLabel = createButtonLabel ?: stringResource(Res.string.add_note)
                Spacer(modifier = Modifier.height(28.dp))
                FilledTonalButton(
                    onClick = onCreateClick,
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = buttonLabel,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(buttonLabel)
                    }
                }
            }
            if (showClearFilters) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onClearFilters,
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FilterAltOff,
                            contentDescription = stringResource(Res.string.clear_filters),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.clear_filters))
                    }
                }
            }

            if (recentSearches.isNotEmpty() && !showCreateButton) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    stringResource(Res.string.recent_searches),
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedTextColor.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    recentSearches.forEach { query ->
                        PrecisionFilterChip(
                            selected = false,
                            onClick = { onRecentSearchClick(query) },
                            label = query,
                            compact = true,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
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
