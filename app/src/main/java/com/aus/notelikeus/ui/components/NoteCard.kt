package com.aus.notelikeus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.editor.RichTextParser
import com.aus.notelikeus.ui.navigation.LocalAnimatedVisibilityScope
import com.aus.notelikeus.ui.navigation.LocalSharedTransitionScope
import com.aus.notelikeus.ui.theme.NoteCardBodyStyle
import com.aus.notelikeus.ui.theme.NoteCardTitleStyle
import com.aus.notelikeus.ui.theme.getContentColor

private val NoteCardContentPadding = 16.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
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
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

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
        targetValue = if (isSelected) 0.985f else 1f,
        label = "selection_scale"
    )

    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        note.color == 0 -> MaterialTheme.colorScheme.onSurface
        else -> Color(note.color).getContentColor(fallback = MaterialTheme.colorScheme.onSurface)
    }

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
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = "note-${note.id}"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            )
            .clip(MaterialTheme.shapes.large) // Enforcing 16.dp corner radius
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.large, // Enforcing 16.dp corner radius
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
        Box {
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
                            text = buildHighlightedString(note.title, searchQuery, contentColor, highlightColor),
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
                            maxLines = if (compact) 2 else 10,
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
                        note.checklist.take(3).forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(
                                        if (item.isChecked) R.string.cd_checked else R.string.cd_unchecked
                                    ),
                                    modifier = Modifier.size(16.dp),
                                    tint = contentColor.copy(alpha = 0.6f)
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
                                        lineHeight = MaterialTheme.typography.labelSmall.lineHeight
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = contentColor.copy(alpha = 0.6f)
                                )
                            }
                        }
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
                                            color = contentColor
                                        )
                                    },
                                    shape = CircleShape,
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = contentColor.copy(alpha = 0.1f)
                                    ),
                                    border = null
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
        }
    }
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
