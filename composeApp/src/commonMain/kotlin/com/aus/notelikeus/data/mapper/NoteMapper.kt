package com.aus.notelikeus.data.mapper

import com.aus.notelikeus.data.attachments.decodeAttachments
import com.aus.notelikeus.data.attachments.encodeAttachments
import com.aus.notelikeus.data.local.entity.*
import com.aus.notelikeus.data.local.model.NoteWithLabels
import com.aus.notelikeus.domain.model.*
import com.aus.notelikeus.domain.query.buildSearchText

fun NoteEntity.toNote(
    labels: List<Label> = emptyList(),
    checklist: List<ChecklistItem> = emptyList(),
): Note {
    return Note(
        id = if (id == 0L) null else id,
        title = title,
        content = content,
        timestamp = timestamp,
        color = color,
        isPinned = isPinned,
        isArchived = isArchived,
        isTrashed = isTrashed,
        position = position,
        reminderTimestamp = reminderTimestamp,
        serverUpdatedAt = serverUpdatedAt,
        labels = labels,
        attachments = decodeAttachments(attachmentsJson),
        checklist = checklist,
        searchText = searchText,
    )
}

fun Note.toNoteEntity(): NoteEntity {
    return NoteEntity(
        id = id ?: 0L,
        title = title,
        content = content,
        timestamp = timestamp,
        color = color,
        isPinned = isPinned,
        isArchived = isArchived,
        isTrashed = isTrashed,
        position = position,
        reminderTimestamp = reminderTimestamp,
        serverUpdatedAt = serverUpdatedAt,
        searchText = buildSearchText(
            title = title,
            content = content,
            checklistTexts = checklist.map { it.text },
            labelNames = labels.map { it.name },
        ),
        attachmentsJson = encodeAttachments(attachments),
    )
}

fun LabelEntity.toLabel(): Label {
    return Label(
        id = if (id == 0L) null else id,
        name = name
    )
}

fun Label.toLabelEntity(): LabelEntity {
    return LabelEntity(
        id = id ?: 0L,
        name = name
    )
}

fun NoteWithLabels.toNote(): Note {
    return note.toNote(
        labels = labels.map { it.toLabel() },
        checklist = checklist.sortedBy { it.position }.map { it.toChecklistItem() }
    )
}
fun ChecklistItemEntity.toChecklistItem(): ChecklistItem {
    return ChecklistItem(
        id = if (id == 0L) null else id,
        text = text,
        isChecked = isChecked,
        position = position
    )
}

fun ChecklistItem.toChecklistItemEntity(noteId: Long): ChecklistItemEntity {
    return ChecklistItemEntity(
        id = id?.takeIf { it > 0 } ?: 0L,
        noteId = noteId,
        text = text,
        isChecked = isChecked,
        position = position
    )
}
