package com.aus.notelikeus.data.mapper

import com.aus.notelikeus.data.local.entity.*
import com.aus.notelikeus.data.local.model.NoteWithLabelsAndAttachments
import com.aus.notelikeus.domain.model.*

fun NoteEntity.toNote(
    labels: List<Label> = emptyList(),
    attachments: List<Attachment> = emptyList(),
    checklist: List<ChecklistItem> = emptyList()
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
        isLocked = isLocked,
        reminderTimestamp = reminderTimestamp,
        labels = labels,
        attachments = attachments,
        checklist = checklist
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
        isLocked = isLocked,
        reminderTimestamp = reminderTimestamp
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

fun AttachmentEntity.toAttachment(): Attachment {
    return Attachment(
        id = if (id == 0L) null else id,
        noteId = noteId,
        uri = uri,
        type = type
    )
}

fun Attachment.toAttachmentEntity(noteId: Long = this.noteId): AttachmentEntity {
    return AttachmentEntity(
        id = id ?: 0L,
        noteId = noteId,
        uri = uri,
        type = type
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

fun NoteWithLabelsAndAttachments.toNote(): Note {
    return note.toNote(
        labels = labels.map { it.toLabel() },
        attachments = attachments.map { it.toAttachment() },
        checklist = checklist.sortedBy { it.position }.map { it.toChecklistItem() }
    )
}
