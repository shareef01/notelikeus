package com.aus.notelikeus.data.backup

import com.aus.notelikeus.data.remote.ReminderScheduler
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteBackupImporter @Inject constructor(
    private val repository: NoteRepository,
    private val reminderScheduler: ReminderScheduler
) {
    data class ImportResult(
        val notesImported: Int,
        val labelsCreated: Int
    )

    suspend fun importFromJson(json: String): ImportResult {
        if (json.length > MAX_BACKUP_CHARS) {
            throw IllegalArgumentException("Backup file is too large")
        }
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        if (version > NoteBackupExporter.BACKUP_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }

        val labelMap = repository.getAllLabelsSnapshot()
            .associateBy { it.name.lowercase() }
            .toMutableMap()
        var labelsCreated = 0

        suspend fun ensureLabel(name: String): Label {
            val key = name.trim().lowercase()
            labelMap[key]?.let { return it }
            val id = repository.insertLabel(Label(name = name.trim()))
            val label = Label(id = id, name = name.trim())
            labelMap[key] = label
            labelsCreated++
            return label
        }

        if (root.has("labels")) {
            val labelsArray = root.getJSONArray("labels")
            if (labelsArray.length() > MAX_BACKUP_LABELS) {
                throw IllegalArgumentException("Backup has too many labels (max $MAX_BACKUP_LABELS)")
            }
            for (i in 0 until labelsArray.length()) {
                val labelJson = labelsArray.getJSONObject(i)
                val name = labelJson.optString("name", "").trim()
                if (name.isNotEmpty()) ensureLabel(name)
            }
        }

        val notesArray = root.optJSONArray("notes") ?: JSONArray()
        if (notesArray.length() > MAX_BACKUP_NOTES) {
            throw IllegalArgumentException("Backup has too many notes (max $MAX_BACKUP_NOTES)")
        }
        val basePosition = repository.getNextNotePosition()
        var notesImported = 0

        for (i in 0 until notesArray.length()) {
            val noteJson = notesArray.getJSONObject(i)
            val labelNames = noteJson.optJSONArray("labels") ?: JSONArray()
            val resolvedLabels = buildList {
                for (j in 0 until labelNames.length()) {
                    val name = labelNames.getString(j)
                    if (name.isNotBlank()) add(ensureLabel(name))
                }
            }

            val checklist = noteJson.optJSONArray("checklist")?.let { array ->
                buildList {
                    for (j in 0 until array.length()) {
                        val item = array.getJSONObject(j)
                        add(
                            ChecklistItem(
                                text = item.optString("text", "").take(MAX_FIELD_CHARS),
                                isChecked = item.optBoolean("isChecked", false),
                                position = item.optInt("position", size)
                            )
                        )
                    }
                }
            } ?: emptyList()

            // A past-due reminder from the backup would never actually fire (scheduleReminder
            // silently no-ops for past timestamps) yet would still show as "active" on the note
            // forever, since nothing else clears it — drop it instead of carrying it over.
            val reminderTimestamp = noteJson.optLong("reminderTimestamp")
                .takeIf { noteJson.has("reminderTimestamp") && it > System.currentTimeMillis() }
            val isTrashed = noteJson.optBoolean("isTrashed", false)

            val note = Note(
                title = noteJson.optString("title", "").take(MAX_FIELD_CHARS),
                content = noteJson.optString("content", "").take(MAX_CONTENT_CHARS),
                timestamp = noteJson.optLong("timestamp", System.currentTimeMillis()),
                color = noteJson.optInt("color", 0xFFFFFFFF.toInt()),
                isPinned = noteJson.optBoolean("isPinned", false),
                isArchived = noteJson.optBoolean("isArchived", false),
                isTrashed = isTrashed,
                position = basePosition + notesImported,
                isLocked = noteJson.optBoolean("isLocked", false),
                reminderTimestamp = reminderTimestamp,
                labels = resolvedLabels,
                attachments = emptyList(),
                checklist = checklist
            )

            repository.insertNoteWithResult(note)
            notesImported++
        }

        return ImportResult(notesImported = notesImported, labelsCreated = labelsCreated)
    }

    companion object {
        const val MAX_BACKUP_CHARS = 10 * 1024 * 1024
        const val MAX_BACKUP_NOTES = 5_000
        const val MAX_BACKUP_LABELS = 2_000
        const val MAX_FIELD_CHARS = 2_000
        const val MAX_CONTENT_CHARS = 100_000
    }
}
