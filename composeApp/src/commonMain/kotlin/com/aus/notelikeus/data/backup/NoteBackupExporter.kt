package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.DateUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        val labels = repository.getAllLabelsSnapshot()

        val backupData = BackupData(
            version = BACKUP_VERSION,
            exportedAt = DateUtils.currentTimeMillis(),
            app = appName,
            appVersion = appVersion,
            labels = labels,
            notes = notes.map { it.toDto() }
        )

        return json.encodeToString(backupData)
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
