package com.aus.notelikeus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.editor.RichTextParser
import com.aus.notelikeus.ui.navigation.LocalAnimatedVisibilityScope
import com.aus.notelikeus.ui.navigation.LocalSharedTransitionScope
import com.aus.notelikeus.ui.theme.getContentColor

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NoteCard(
    note: Note,
    isSelected: Boolean,
    searchQuery: String = "",
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color(note.color),
        label = "color"
    )
    
    val elevation by animateDpAsState(
        targetValue = if (isSelected) 8.dp else 1.dp,
        label = "elevation"
    )

    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        Color(note.color).getContentColor()
    }

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    Card(
        modifier = modifier
            .fillMaxWidth()
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
            .padding(4.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (!isSelected && note.color == MaterialTheme.colorScheme.background.toArgb()) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Box {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                if (note.isLocked) {
                    Text(
                        text = "Locked note",
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                } else {
                    if (note.attachments.isNotEmpty()) {
                        AsyncImage(
                            model = note.attachments.first().uri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (note.title.isNotEmpty()) {
                        Text(
                            text = buildHighlightedString(note.title, searchQuery, contentColor, highlightColor),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (note.content.isNotEmpty()) {
                        Text(
                            text = RichTextParser.parse(
                                text = note.content,
                                contentColor = contentColor.copy(alpha = 0.8f),
                                highlightColor = highlightColor,
                                searchQuery = searchQuery
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (note.checklist.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        note.checklist.take(3).forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = contentColor.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = RichTextParser.parse(
                                        text = item.text,
                                        contentColor = contentColor.copy(alpha = 0.6f),
                                        highlightColor = highlightColor,
                                        searchQuery = searchQuery
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = contentColor.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    if (note.labels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            note.labels.take(2).forEach { label ->
                                SuggestionChip(
                                    onClick = { },
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
                        }
                    }
                }
            }
            
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(20.dp)
                )
            } else if (note.isLocked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = contentColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(16.dp)
                )
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
