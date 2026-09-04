package com.aus.notelikeus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.aus.notelikeus.data.attachments.firstImageAttachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.editor.RichTextParser
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorForTheme
import com.aus.notelikeus.util.DateUtils
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.theme.Radius
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Spacing

private val NoteCardContentPadding = Spacing.xl

/** Compact uppercase label pill, matching the web card's chip typography. */
private val NoteCardLabelChipStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.5.sp
)

/**
 * Relative time label matching web's formatListTimestamp:
 * today shows the clock time, yesterday shows "Yesterday", anything older shows "MMM d".
 */
@Composable
private fun noteTimestampLabel(timestamp: Long): String {
    val yesterdayLabel = stringResource(Res.string.section_yesterday)
    return when {
        DateUtils.isToday(timestamp) -> DateUtils.formatTime(timestamp)
        DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> yesterdayLabel
        else -> DateUtils.formatDateTime(timestamp, showYear = false)
    }
}

/** "3/5" style progress line under a card's preview text. */
@Composable
private fun ChecklistProgressText(note: Note, contentColor: Color) {
    Text(
        text = stringResource(
            Res.string.checklist_progress,
            note.checklist.count { it.isChecked },
            note.checklist.size
        ),
        style = MaterialTheme.typography.labelSmall,
        color = contentColor.copy(alpha = NoteEmphasis.Secondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Row of up to [maxVisible] uppercase label pills plus an "+N" overflow count, matching the web
 * card's chips. The list and grid layouts differ only in how many chips fit and how tight the
 * pills are, hence [maxVisible], [chipStyle] and the padding parameters.
 */
@Composable
private fun NoteCardLabelChips(
    note: Note,
    maxVisible: Int,
    contentColor: Color,
    chipStyle: TextStyle,
    chipHorizontalPadding: Dp,
    chipVerticalPadding: Dp,
    onLabelClick: ((Long) -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        note.labels.take(maxVisible).forEach { label ->
            val labelId = label.id
            val clickable = labelId != null && onLabelClick != null
            Text(
                text = label.name.uppercase(),
                style = chipStyle,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = NoteEmphasis.Decorative))
                    .then(
                        if (clickable) {
                            Modifier.clickable { onLabelClick.invoke(labelId) }
                        } else Modifier
                    )
                    .padding(horizontal = chipHorizontalPadding, vertical = chipVerticalPadding)
            )
        }
        val overflowCount = note.labels.size - maxVisible
        if (overflowCount > 0) {
            Text(
                text = stringResource(Res.string.labels_more, overflowCount),
                style = chipStyle,
                color = contentColor.copy(alpha = NoteEmphasis.Secondary)
            )
        }
    }
}

/**
 * Right-hand column of a card: the selection tick or the pin/reminder status icons, above the
 * relative timestamp. Shared by the list and grid layouts, which size the icons and timestamp
 * differently.
 */
@Composable
private fun NoteCardStatusColumn(
    note: Note,
    isSelected: Boolean,
    contentColor: Color,
    selectedLabel: String,
    statusIconSize: Dp,
    timestampFontSize: TextUnit,
    timestampAlpha: Float,
    verticalSpacing: Dp
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        if (isSelected) {
            Surface(
                modifier = Modifier.size(Size.iconLarge),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = Spacing.none
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = selectedLabel,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(Size.iconTiny)
                    )
                }
            }
        } else {
            if (note.isPinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = NoteEmphasis.Icon),
                    modifier = Modifier.size(statusIconSize)
                )
            }
            if (note.reminderTimestamp != null) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = NoteEmphasis.Icon),
                    modifier = Modifier.size(statusIconSize)
                )
            }
        }
        Text(
            text = noteTimestampLabel(note.timestamp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
                fontSize = timestampFontSize
            ),
            color = contentColor.copy(alpha = timestampAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    isSelected: Boolean,
    searchQuery: String = "",
    compact: Boolean = false,
    listStyle: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLabelClick: ((Long) -> Unit)? = null,
    showReorderHandle: Boolean = false,
    reorderDragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    /*
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    */
    val isDarkPalette = isNoteColorDarkTheme()
    val displayColorArgb = noteColorForTheme(note.color, isDarkPalette)

    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            displayColorArgb == 0 -> MaterialTheme.colorScheme.surface
            else -> Color(displayColorArgb.toLong() and 0xffffffffL)
        },
        label = "color"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val elevation by animateDpAsState(
        targetValue = when {
            isSelected -> Spacing.xxs
            isHovered -> 6.dp
            else -> Spacing.none
        },
        label = "elevation"
    )

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.98f
            isHovered -> 1.01f
            isSelected -> 0.995f
            else -> 1f
        },
        label = "press_scale"
    )

    val hairlineBorder = BorderStroke(
        Spacing.hairline,
        // Web default-color cards use border-brand-outline/40.
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )

    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        displayColorArgb == 0 -> MaterialTheme.colorScheme.onSurface
        else -> Color(displayColorArgb).getContentColor(fallback = MaterialTheme.colorScheme.onSurface)
    }

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val selectedLabel = stringResource(Res.string.cd_selected)
    val pinnedLabel = stringResource(Res.string.pinned_short)
    val reminderLabel = stringResource(Res.string.cd_reminder_set)
    val hasImageLabel = stringResource(Res.string.cd_has_image)
    val untitledLabel = stringResource(Res.string.untitled)
    val thumbnailAttachment = firstImageAttachment(note.attachments)
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
        if (thumbnailAttachment != null) {
            append(", ")
            append(hasImageLabel)
        }
        if (isSelected) {
            append(", ")
            append(selectedLabel)
        }
    }
    val reorderLabel = stringResource(Res.string.cd_reorder)
    val contentStartPadding = if (showReorderHandle) {
        48.dp
    } else {
        NoteCardContentPadding
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            /*
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
            */
            .clip(RoundedCornerShape(Radius.lg)) // Web cards use rounded-note = 18px corners
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .hoverable(interactionSource),
        shape = RoundedCornerShape(Radius.lg), // Web cards use rounded-note = 18px corners
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = when {
            isSelected -> BorderStroke(Spacing.xxs, MaterialTheme.colorScheme.primary)
            displayColorArgb == 0 -> hairlineBorder
            else -> null
        }
    ) {
        Box {
            if (showReorderHandle) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(Size.touchTarget)
                        .semantics { contentDescription = reorderLabel }
                        .then(reorderDragModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = NoteEmphasis.Icon),
                        modifier = Modifier.size(Size.icon)
                    )
                }
            }

            if (listStyle) {
                // Horizontal one-column row, matching the web List view: accent strip,
                // 2-line title, 3-line preview, checklist count, up to 3 label chips, and a
                // right-aligned status/timestamp column.
                Row(
                    modifier = Modifier
                        .semantics(mergeDescendants = true) {
                            contentDescription = accessibilityDescription
                            selected = isSelected
                        }
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(
                            start = contentStartPadding,
                            top = 14.dp,
                            end = 14.dp,
                            bottom = 14.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // No accent strip. It existed only in this List branch, and it was drawn
                    // from contentColor/outline rather than the note's colour -- so it never
                    // rendered the colour it appeared to stand for, and Grid and List disagreed
                    // about what it meant. The tinted container carries the colour in every
                    // layout now; see docs/DECISIONS.md D1.
                    if (thumbnailAttachment != null) {
                        NoteCardThumbnail(
                            attachment = thumbnailAttachment,
                            listStyle = true,
                            compact = compact,
                        )
                        Spacer(modifier = Modifier.width(Spacing.md))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (note.title.isNotEmpty()) {
                            Text(
                                text = buildHighlightedString(note.title, searchQuery, contentColor, highlightColor),
                                style = AppType.noteCardTitle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (note.content.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = RichTextParser.parse(
                                    text = note.content,
                                    contentColor = contentColor.copy(alpha = NoteEmphasis.Secondary),
                                    highlightColor = highlightColor,
                                    searchQuery = searchQuery,
                                    linkColor = MaterialTheme.colorScheme.primary,
                                    linksClickable = false
                                ),
                                style = AppType.noteCardBody,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (note.checklist.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            ChecklistProgressText(note = note, contentColor = contentColor)
                        }
                        if (note.labels.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            NoteCardLabelChips(
                                note = note,
                                maxVisible = 3,
                                contentColor = contentColor,
                                chipStyle = NoteCardLabelChipStyle.copy(fontSize = 9.sp),
                                chipHorizontalPadding = 6.dp,
                                chipVerticalPadding = Spacing.xxs,
                                onLabelClick = onLabelClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(Spacing.md))
                    NoteCardStatusColumn(
                        note = note,
                        isSelected = isSelected,
                        contentColor = contentColor,
                        selectedLabel = selectedLabel,
                        statusIconSize = 15.dp,
                        timestampFontSize = 11.sp,
                        timestampAlpha = 0.55f,
                        verticalSpacing = 6.dp
                    )
                }
            } else {
            Column(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = accessibilityDescription
                        selected = isSelected
                    }
                    .padding(
                    start = contentStartPadding,
                    top = if (compact) Spacing.lg else NoteCardContentPadding,
                    end = if (compact) Spacing.lg else NoteCardContentPadding,
                    bottom = if (compact) Spacing.lg else NoteCardContentPadding
                )
            ) {
                if (thumbnailAttachment != null) {
                    NoteCardThumbnail(
                        attachment = thumbnailAttachment,
                        listStyle = false,
                        compact = compact,
                    )
                    Spacer(modifier = Modifier.height(if (compact) Spacing.sm else Spacing.md))
                }
                // Title row: title on the left, status/selection + timestamp on the right,
                // matching the web card's header block.
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // A note with no title gets no title line, rather than the word "Untitled"
                    // in the most prominent position on the card. The placeholder took the line
                    // the user's own words should lead with and pushed the content down to say
                    // nothing -- and the card's accessibility description had already decided
                    // otherwise, falling through to the first line of content. The eye and the
                    // screen reader were describing the same note two different ways, and the
                    // screen reader had the better of it.
                    //
                    // The row itself stays: it carries the timestamp and status icons.
                    if (note.title.isNotEmpty()) {
                        Text(
                            text = buildHighlightedString(
                                note.title,
                                searchQuery,
                                contentColor,
                                highlightColor
                            ),
                            style = if (compact) {
                                AppType.noteCardTitle.copy(fontSize = 15.sp, lineHeight = 20.sp)
                            } else {
                                AppType.noteCardTitle
                            },
                            maxLines = if (compact) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    NoteCardStatusColumn(
                        note = note,
                        isSelected = isSelected,
                        contentColor = contentColor,
                        selectedLabel = selectedLabel,
                        statusIconSize = if (compact) 13.dp else 14.dp,
                        timestampFontSize = if (compact) 10.sp else 11.sp,
                        timestampAlpha = 0.6f,
                        verticalSpacing = Spacing.xs
                    )
                }
                if (note.content.isNotEmpty()) {
                    // Only a real title needs separating from the body. With none, the gap would
                    // be space under nothing.
                    if (note.title.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(if (compact) Spacing.sm else Spacing.md))
                    }
                    Text(
                        text = RichTextParser.parse(
                            text = note.content,
                            contentColor = contentColor.copy(alpha = NoteEmphasis.Secondary),
                            highlightColor = highlightColor,
                            searchQuery = searchQuery,
                            linkColor = MaterialTheme.colorScheme.primary,
                            linksClickable = false
                        ),
                        style = AppType.noteCardBody,
                        // Web grid preview clamps at 7 lines; the ellipsis says there is more.
                        maxLines = if (compact) 5 else 7,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (compact && note.checklist.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ChecklistProgressText(note = note, contentColor = contentColor)
                }

                if (!compact && note.checklist.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        note.checklist.take(3).forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (item.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = stringResource(
                                        if (item.isChecked) Res.string.cd_checked else Res.string.cd_unchecked
                                    ),
                                    modifier = Modifier.size(Size.iconTiny),
                                    tint = contentColor.copy(alpha = NoteEmphasis.Icon)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = RichTextParser.parse(
                                        text = item.text,
                                        contentColor = contentColor.copy(alpha = NoteEmphasis.Secondary),
                                        highlightColor = highlightColor,
                                        searchQuery = searchQuery,
                                        linksClickable = false
                                    ),
                                    style = AppType.noteCardBody.copy(
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                        lineHeight = MaterialTheme.typography.labelSmall.lineHeight
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = contentColor.copy(alpha = NoteEmphasis.Secondary)
                                )
                            }
                        }
                    }
                    val remainingChecklistCount = note.checklist.size - 3
                    if (remainingChecklistCount > 0) {
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(Res.string.labels_more, remainingChecklistCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = NoteEmphasis.Secondary)
                        )
                    }
                }

                if (!compact && note.labels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    NoteCardLabelChips(
                        note = note,
                        maxVisible = 2,
                        contentColor = contentColor,
                        chipStyle = NoteCardLabelChipStyle,
                        chipHorizontalPadding = Spacing.sm,
                        chipVerticalPadding = 3.dp,
                        onLabelClick = onLabelClick
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
