package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.aus.notelikeus.ui.theme.Elevation
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.theme.Spacing
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Says what the search silently did to the query, when it did anything.
 *
 * Two things can happen to a search without the user asking: an exact search that found nothing
 * falls back to near matches, and operator-shaped text the parser did not recognise is dropped.
 * Both change which notes are on screen, and neither is visible in the results themselves -- a
 * list of near matches looks exactly like a list of matches. Left unsaid, the first makes a typo
 * look like a successful search and the second makes an ignored filter look like an applied one.
 *
 * Renders nothing when neither happened, so it costs an empty [Column] on the common path.
 */
@Composable
fun SearchNoticeRow(
    searchText: String,
    isFuzzyResult: Boolean,
    unknownOperators: List<String>,
    modifier: Modifier = Modifier
) {
    if (!isFuzzyResult && unknownOperators.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (isFuzzyResult) {
            Notice(
                icon = Icons.Outlined.Spellcheck,
                text = stringResource(Res.string.search_did_you_mean, searchText)
            )
        }
        if (unknownOperators.isNotEmpty()) {
            Notice(
                icon = Icons.Outlined.Info,
                text = stringResource(
                    Res.string.search_unknown_operator,
                    unknownOperators.joinToString(", ")
                )
            )
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = NoteEmphasis.Wash),
        tonalElevation = Elevation.none
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                // Results change under the reader without any focus moving, so the explanation has
                // to announce itself; Polite waits for the current utterance rather than cutting
                // off whatever the list just said.
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                // The adjacent text is the whole message -- announcing the icon as well would
                // just repeat it.
                contentDescription = null,
                modifier = Modifier.size(Spacing.lg),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NoteEmphasis.Icon)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NoteEmphasis.Secondary),
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
    }
}
