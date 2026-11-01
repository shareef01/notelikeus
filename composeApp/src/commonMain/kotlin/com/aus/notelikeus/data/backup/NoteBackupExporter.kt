package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.DateUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class NoteBackupExporter(
    private val repository: NoteRepository,
    private val appName: String,
    private val appVersion: String
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun createJson(): String {
        val notes = repository.getAllNotesForBackup()
        check(notes.size <= NoteBackupImporter.MAX_BACKUP_NOTES) {
            "Cannot export backup: library has ${notes.size} notes (maximum supported is ${NoteBackupImporter.MAX_BACKUP_NOTES})"
        }
        val labels = repository.getAllLabelsSnapshot()
        check(labels.size <= NoteBackupImporter.MAX_BACKUP_LABELS) {
            "Cannot export backup: library has ${labels.size} labels (maximum supported is ${NoteBackupImporter.MAX_BACKUP_LABELS})"
        }

        for (note in notes) {
            check(note.title.length <= NoteBackupImporter.MAX_FIELD_CHARS) {
                "Cannot export backup: note '${note.id}' title exceeds maximum supported length (${note.title.length} chars > ${NoteBackupImporter.MAX_FIELD_CHARS})"
            }
            check(note.content.length <= NoteBackupImporter.MAX_CONTENT_CHARS) {
                "Cannot export backup: note '${note.id}' content exceeds maximum supported length (${note.content.length} chars > ${NoteBackupImporter.MAX_CONTENT_CHARS})"
            }
            check(note.labels.size <= NoteBackupImporter.MAX_NOTE_LABELS) {
                "Cannot export backup: note '${note.id}' has too many labels (${note.labels.size} > ${NoteBackupImporter.MAX_NOTE_LABELS})"
            }
            check(note.checklist.size <= NoteBackupImporter.MAX_NOTE_CHECKLIST) {
                "Cannot export backup: note '${note.id}' has too many checklist items (${note.checklist.size} > ${NoteBackupImporter.MAX_NOTE_CHECKLIST})"
            }
            for (item in note.checklist) {
                check(item.text.length <= NoteBackupImporter.MAX_FIELD_CHARS) {
                    "Cannot export backup: checklist item in note '${note.id}' exceeds maximum length (${item.text.length} chars > ${NoteBackupImporter.MAX_FIELD_CHARS})"
                }
            }
        }

        val backupData = BackupData(
            version = BACKUP_VERSION,
            exportedAt = DateUtils.currentTimeMillis(),
            app = appName,
            appVersion = appVersion,
            labels = labels.map { label ->
                LabelBackupDto(id = label.id?.let(::JsonPrimitive), name = label.name)
            },
            notes = notes.map { it.toDto() }
        )

        val jsonString = json.encodeToString(backupData)
        val utf8Bytes = jsonString.encodeToByteArray().size
        check(utf8Bytes <= NoteBackupImporter.MAX_BACKUP_CHARS) {
            "Cannot export backup: serialized file size ($utf8Bytes bytes) exceeds maximum supported limit (${NoteBackupImporter.MAX_BACKUP_CHARS} bytes)"
        }
        return jsonString
    }

    private fun Note.toDto() = NoteBackupDto(
        id = id,
        title = title,
        content = content,
        timestamp = timestamp,
        color = color,
        isPinned = isPinned,
        isArchived = isArchived,
        isTrashed = isTrashed,
        position = position,
        reminderTimestamp = reminderTimestamp,
        labels = labels.map { it.name },
        checklist = checklist.map { ChecklistItemBackupDto(it.text, it.isChecked, it.position) }
    )

    companion object {
        const val BACKUP_VERSION = 3
        const val BACKUP_MIME_TYPE = "application/json"
        const val BACKUP_FILE_PREFIX = "notelikeus_backup"
    }
}
