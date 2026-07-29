package com.aus.notelikeus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.editor.RichTextParser
import com.aus.notelikeus.ui.navigation.LocalAnimatedVisibilityScope
import com.aus.notelikeus.ui.navigation.LocalSharedTransitionScope
import com.aus.notelikeus.ui.theme.NoteCardBodyStyle
import com.aus.notelikeus.ui.theme.NoteCardTitleStyle
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorForTheme

private val NoteCardContentPadding = 18.dp

/** Compact uppercase label pill, matching the web card's chip typography. */
private val NoteCardLabelChipStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.4.sp
)

/**
 * Relative time label mirroring web's formatListTimestamp: today shows the clock time,
 * yesterday shows "Yesterday", anything older shows "MMM d". The locale is read from
 * LocalConfiguration so the label recomposes if the device locale changes.
 */
@Composable
private fun noteTimestampLabel(timestamp: Long): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    val yesterdayLabel = stringResource(R.string.section_yesterday)
    return remember(timestamp, locale, is24Hour, yesterdayLabel) {
        formatNoteTimestamp(timestamp, locale, is24Hour, yesterdayLabel)
    }
}

private fun formatNoteTimestamp(
    timestamp: Long,
    locale: Locale,
    is24Hour: Boolean,
    yesterdayLabel: String
): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    if (sameDay(now, then)) {
        val pattern = if (is24Hour) "H:mm" else "h:mm a"
        return SimpleDateFormat(pattern, locale).format(Date(timestamp))
    }
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, then)) return yesterdayLabel
    return SimpleDateFormat("MMM d", locale).format(Date(timestamp))
}

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
    val isDarkPalette = isNoteColorDarkTheme()
    val displayColorArgb = noteColorForTheme(note.color, isDarkPalette)

    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            displayColorArgb == 0 -> MaterialTheme.colorScheme.surface
            else -> Color(displayColorArgb)
        },
        label = "color"
    )

    val elevation by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        label = "elevation"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.995f else 1f,
        label = "selection_scale"
    )

    val hairlineBorder = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.CardHairline)
    )

    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        displayColorArgb == 0 -> MaterialTheme.colorScheme.onSurface
        else -> Color(displayColorArgb).getContentColor(fallback = MaterialTheme.colorScheme.onSurface)
    }

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val selectedLabel = stringResource(R.string.cd_selected)
    val pinnedLabel = stringResource(R.string.pinned_short)
    val reminderLabel = stringResource(R.string.cd_reminder_set)
    val untitledLabel = stringResource(R.string.untitled)
    val noteDescription = when {
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
            isSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            displayColorArgb == 0 -> hairlineBorder
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
                if (note.title.isNotEmpty()) {
                    val trailingChrome = when {
                        isSelected -> 28.dp
                        note.isPinned || note.reminderTimestamp != null -> 20.dp
                        else -> 0.dp
                    }
                    Text(
                        text = buildHighlightedString(note.title, searchQuery, contentColor, highlightColor),
                        style = NoteCardTitleStyle,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = trailingChrome)
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
                            linkColor = MaterialTheme.colorScheme.primary,
                            linksClickable = false
                        ),
                        style = NoteCardBodyStyle,
                        maxLines = if (compact) 5 else 12,
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
                                if (item.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
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
                                    searchQuery = searchQuery,
                                    linksClickable = false
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
                    val remainingChecklistCount = note.checklist.size - 3
                    if (remainingChecklistCount > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.labels_more, remainingChecklistCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.55f)
                        )
                    }
                }

                if (!compact && note.labels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        note.labels.take(2).forEach { label ->
                            val labelId = label.id
                            // Compact uppercase pill, matching the web note card's label chips
                            // (rounded-full, uppercase, tracking-wider, colour @ 10% surface).
                            val clickable = labelId != null && onLabelClick != null
                            Text(
                                text = label.name.uppercase(),
                                style = NoteCardLabelChipStyle,
                                color = contentColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(contentColor.copy(alpha = 0.1f))
                                    .then(
                                        if (clickable) {
                                            Modifier.clickable { onLabelClick.invoke(labelId) }
                                        } else Modifier
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        val overflowCount = note.labels.size - 2
                        if (overflowCount > 0) {
                            Text(
                                text = stringResource(R.string.labels_more, overflowCount),
                                style = NoteCardLabelChipStyle,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Meta footer: full cards keep a divider; compact stays a quiet timestamp only.
                if (compact) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = noteTimestampLabel(note.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            letterSpacing = 0.2.sp,
                            fontSize = 11.sp
                        ),
                        color = contentColor.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = contentColor.copy(alpha = 0.1f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = noteTimestampLabel(note.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            letterSpacing = 0.2.sp,
                            fontSize = 12.sp
                        ),
                        color = contentColor.copy(alpha = 0.6f)
                    )
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
                    shadowElevation = 0.dp
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
