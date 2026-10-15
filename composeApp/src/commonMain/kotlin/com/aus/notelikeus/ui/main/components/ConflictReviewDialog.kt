package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.main.ConflictChoice
import com.aus.notelikeus.ui.main.ConflictReview
import com.aus.notelikeus.ui.main.ConflictReviewState
import com.aus.notelikeus.ui.main.ReviewLine
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.util.DateUtils
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The caution hue of the conflict chip (amber-500), at the tint the web review uses. */
private val ChangedLineTint = Color(0xFFF59E0B).copy(alpha = 0.22f)

/** Wide enough for two versions side by side; narrower windows stack them. */
private val SideBySideMinWidth = 560.dp

@Composable
private fun Version(heading: String, note: Note, lines: List<ReviewLine>, modifier: Modifier = Modifier) {
    val onlyHere = stringResource(Res.string.conflict_review_only_here)
    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(Spacing.md),
    ) {
        Text(
            heading.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.conflict_review_edited, DateUtils.formatDateTime(note.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note.title.isNotEmpty()) {
            Text(note.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Spacing.sm))
        }
        if (lines.isEmpty()) {
            Text(
                stringResource(Res.string.conflict_review_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lines.forEach { line ->
            val base = Modifier.fillMaxWidth().padding(top = 2.dp)
            Text(
                text = line.text.ifEmpty { " " },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (line.changed) FontWeight.Medium else FontWeight.Normal,
                modifier = if (line.changed) {
                    base
                        .background(ChangedLineTint, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp)
                        .semantics { contentDescription = "${line.text} ($onlyHere)" }
                } else {
                    base.padding(horizontal = 4.dp)
                },
            )
        }
    }
}

/**
 * Side-by-side review of a conflicted copy and the note it was split from (UI_UX_REDESIGN §7.2),
 * matching the web client's dialog. Keeping one version moves the other to Trash; nothing is deleted.
 */
@Composable
fun ConflictReviewDialog(
    review: ConflictReviewState,
    onChoice: (ConflictChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val original = review.original
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.conflict_review_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    stringResource(Res.string.conflict_review_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (original != null) {
                    val (current, copy) = ConflictReview.compareVersions(original, review.copy)
                    val currentHeading = stringResource(Res.string.conflict_review_current)
                    val copyHeading = stringResource(Res.string.conflict_review_copy)
                    BoxWithConstraints {
                        if (maxWidth >= SideBySideMinWidth) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                Version(currentHeading, original, current, Modifier.weight(1f))
                                Version(copyHeading, review.copy, copy, Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                Version(currentHeading, original, current, Modifier.fillMaxWidth())
                                Version(copyHeading, review.copy, copy, Modifier.fillMaxWidth())
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(Res.string.conflict_review_unpaired),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val (lines, _) = ConflictReview.compareVersions(review.copy, review.copy)
                    Version(
                        stringResource(Res.string.conflict_review_copy),
                        review.copy,
                        lines,
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Button(onClick = { onChoice(ConflictChoice.KEEP_BOTH) }) {
                    Text(stringResource(Res.string.conflict_review_keep_both))
                }
                if (original != null) {
                    OutlinedButton(onClick = { onChoice(ConflictChoice.KEEP_COPY) }) {
                        Text(stringResource(Res.string.conflict_review_keep_copy))
                    }
                    OutlinedButton(onClick = { onChoice(ConflictChoice.KEEP_CURRENT) }) {
                        Text(stringResource(Res.string.conflict_review_keep_current))
                    }
                } else {
                    OutlinedButton(onClick = { onChoice(ConflictChoice.KEEP_CURRENT) }) {
                        Text(stringResource(Res.string.conflict_review_trash_copy))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.conflict_review_later)) }
        },
    )
}
