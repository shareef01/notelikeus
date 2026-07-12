package com.aus.notelikeus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.editor.RichTextParser
import com.aus.notelikeus.ui.theme.NoteCardBodyStyle
import com.aus.notelikeus.ui.theme.NoteCardTitleStyle
import com.aus.notelikeus.ui.theme.getContentColor

private val NoteCardContentPadding = 16.dp
private val NoteCardInnerRadius = 14.dp
private const val MaxChecklistPreviewItems = 3
private const val MaxContentPreviewLines = 5

private fun relativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> "${diff / 604_800_000}w ago"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    isSelected: Boolean,
    searchQuery: String = "",
    compact: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLabelClick: ((Long) -> Unit)? = null,
    showReorderHandle: Boolean = false,
    reorderDragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            note.color == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color(note.color)
        },
        label = "color"
    )

    val elevation by animateDpAsState(
        targetValue = if (isSelected) 4.dp else 1.dp,
        label = "elevation"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "selection_scale"
    )

    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        note.color == 0 -> MaterialTheme.colorScheme.onSurface
        else -> Color(note.color).getContentColor(fallback = MaterialTheme.colorScheme.onSurface)
    }

    val statusIconTint = contentColor.copy(alpha = 0.55f)
    val hasAccent = note.color != 0 && !isSelected
    val accentColor = if (hasAccent) Color(note.color).copy(alpha = 0.85f) else Color.Transparent

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val lockedNoteLabel = stringResource(R.string.locked_note)
    val selectedLabel = stringResource(R.string.cd_selected)
    val pinnedLabel = stringResource(R.string.pinned_short)
    val reminderLabel = stringResource(R.string.cd_reminder_set)
    val untitledLabel = stringResource(R.string.untitled)
    val noteDescription = when {
        note.isLocked -> lockedNoteLabel
        note.title.isNotBlank() -> note.title
        note.content.isNotBlank() -> note.content.lineSequence().first()
        else -> untitledLabel
    }
    val showStatusRow = note.isPinned || note.reminderTimestamp != null || note.isLocked
    val accessibilityDescription = buildString {
        append(noteDescription)
        if (note.isPinned) {
            append(", ")
            append(pinnedLabel)
        }
        if (note.reminderTimestamp != null) {
            append(", ")
            append(reminderLabel)
        }
        if (note.isLocked) {
            append(", ")
            append(lockedNoteLabel)
        }
        if (isSelected) {
            append(", ")
            append(selectedLabel)
        }
    }
    val reorderLabel = stringResource(R.string.cd_reorder)
    val contentStartPadding = if (showReorderHandle) {
        48.dp
    } else {
        NoteCardContentPadding
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickableWithFeedback(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(NoteCardInnerRadius),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = when {
            isSelected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            note.color == MaterialTheme.colorScheme.background.toArgb() -> CardDefaults.outlinedCardBorder()
            else -> null
        }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (hasAccent) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = 64.dp)
                        .background(accentColor)
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (showReorderHandle) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(48.dp)
                            .semantics { contentDescription = reorderLabel }
                            .then(reorderDragModifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragIndicator,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.38f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = accessibilityDescription
                        selected = isSelected
                    }
                    .padding(
                    start = contentStartPadding,
                    top = NoteCardContentPadding,
                    end = NoteCardContentPadding,
                    bottom = NoteCardContentPadding
                )
            ) {
                if (note.isLocked) {
                    Text(
                        text = lockedNoteLabel,
                        style = NoteCardTitleStyle,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                } else {
                    if (note.title.isNotEmpty()) {
                        Text(
                            text = RichTextParser.parse(
                                text = note.title,
                                contentColor = contentColor,
                                highlightColor = highlightColor,
                                searchQuery = searchQuery
                            ),
                            style = NoteCardTitleStyle,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!compact || note.content.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (note.content.isNotEmpty()) {
                        Text(
                            text = RichTextParser.parse(
                                text = note.content,
                                contentColor = contentColor.copy(alpha = 0.8f),
                                highlightColor = highlightColor,
                                searchQuery = searchQuery,
                                linkColor = MaterialTheme.colorScheme.primary
                            ),
                            style = NoteCardBodyStyle,
                            maxLines = if (compact) 2 else MaxContentPreviewLines,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (compact && note.checklist.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.checklist_progress, note.checklist.count { it.isChecked }, note.checklist.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!compact && note.checklist.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val previewItems = note.checklist.take(MaxChecklistPreviewItems)
                        previewItems.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (item.isChecked) Icons.Default.CheckCircle
                                    else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = stringResource(
                                        if (item.isChecked) R.string.cd_checked else R.string.cd_unchecked
                                    ),
                                    modifier = Modifier.size(16.dp),
                                    tint = contentColor.copy(alpha = if (item.isChecked) 0.7f else 0.35f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = RichTextParser.parse(
                                        text = item.text,
                                        contentColor = contentColor.copy(alpha = 0.6f),
                                        highlightColor = highlightColor,
                                        searchQuery = searchQuery
                                    ),
                                    style = NoteCardBodyStyle.copy(
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                        lineHeight = MaterialTheme.typography.labelSmall.lineHeight,
                                        textDecoration = if (item.isChecked) {
                                            TextDecoration.LineThrough
                                        } else {
                                            TextDecoration.None
                                        },
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = contentColor.copy(alpha = 0.6f)
                                )
                            }
                        }
                        val overflowCount = note.checklist.size - previewItems.size
                        if (overflowCount > 0) {
                            Text(
                                text = stringResource(R.string.checklist_more, overflowCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    // Mini checklist progress bar (compact mode)
                    if (compact && note.checklist.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val progress = note.checklist.count { it.isChecked }.toFloat() / note.checklist.size
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = contentColor.copy(alpha = 0.5f),
                            trackColor = contentColor.copy(alpha = 0.12f),
                        )
                    }

                    // Timestamp
                    if (note.content.isNotEmpty() || note.checklist.isNotEmpty() || note.title.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = relativeTime(note.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.45f),
                            maxLines = 1
                        )
                    }

                    if (!compact && note.labels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            note.labels.take(2).forEach { label ->
                                val labelId = label.id
                                SuggestionChip(
                                    onClick = {
                                        labelId?.let { onLabelClick?.invoke(it) }
                                    },
                                    enabled = labelId != null && onLabelClick != null,
                                    label = {
                                        Text(
                                            text = label.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = contentColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = contentColor.copy(alpha = 0.12f)
                                    ),
                                    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.2f))
                                )
                            }
                            val overflowCount = note.labels.size - 2
                            if (overflowCount > 0) {
                                Text(
                                    text = stringResource(R.string.labels_more, overflowCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                }
            }

            if (isSelected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NoteCardContentPadding)
                        .size(24.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = selectedLabel,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NoteCardContentPadding),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (note.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.55f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (note.reminderTimestamp != null) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.55f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (note.isLocked) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            } // inner Box (weight 1f)
        } // Row (fillMaxWidth)
    } // Card
}

private fun buildHighlightedString(
    text: String,
    query: String,
    contentColor: Color,
    highlightColor: Color
): AnnotatedString {
    if (query.isEmpty() || !text.contains(query, ignoreCase = true)) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(query, start, ignoreCase = true)
            if (index == -1) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            ) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}
