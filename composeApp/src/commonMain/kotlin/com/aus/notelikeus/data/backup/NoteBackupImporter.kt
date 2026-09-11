package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.DateUtils
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

class NoteBackupImporter(
    private val repository: NoteRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun importFromJson(jsonStr: String): BackupImportResult {
        return try {
            if (jsonStr.length > MAX_BACKUP_CHARS) {
                return BackupImportResult.InvalidFormat("Backup file is too large")
            }

            // kotlinx.serialization parses recursively, so a crafted file of deeply nested
            // arrays/objects can exhaust the stack and throw StackOverflowError — an Error the
            // catch below cannot handle, which would take the whole app down with a file the user
            // merely picked. Reject excessive nesting up front instead.
            if (maxJsonNestingDepth(jsonStr) > MAX_JSON_DEPTH) {
                return BackupImportResult.InvalidFormat("Backup file is too deeply nested")
            }

            // A `.nlkbak` bundle's manifest.json wraps a v3 document rather than replacing it,
            // so an extracted manifest imports here with its notes intact. The archive's image
            // bytes are the part this platform cannot restore yet; they are counted and reported
            // rather than passed over in silence.
            val unwrapped = try {
                unwrapBundleManifest(jsonStr)
            } catch (e: SerializationException) {
                return BackupImportResult.InvalidFormat(
                    "Backup file could not be read: ${e.message ?: "unrecognised structure"}"
                )
            }
            unwrapped.rejection?.let { return it }
            val documentJson = unwrapped.document
            val attachmentsSkipped = unwrapped.attachmentsSkipped

            val backupData = try {
                json.decodeFromString<BackupData>(documentJson)
            } catch (e: SerializationException) {
                // A field of the wrong type fails the whole document, and the bare exception
                // surfaced as an unexplained "import failed". The decoder already knows which
                // path it choked on, so say so — that is the difference between a user who can
                // fix or report the file and one who cannot.
                return BackupImportResult.InvalidFormat(
                    "Backup file could not be read: ${e.message ?: "unrecognised structure"}"
                )
            }
            
            if (backupData.version > NoteBackupExporter.BACKUP_VERSION) {
                return BackupImportResult.InvalidFormat("Unsupported backup version: ${backupData.version}")
            }

            // Validate the whole payload up front. These checks used to run inside the insert
            // loop, so a backup that tripped a limit half-way through reported InvalidFormat
            // while leaving the notes and labels it had already written committed.
            validate(backupData)?.let { return it }

            val importedIds = mutableListOf<Long>()
            val newNoteIdByOldId = linkedMapOf<Long, Long>()
            var labelsCreated = 0
            var notesImported = 0

            repository.withWriteTransaction {
                val labelMap = repository.getAllLabelsSnapshot()
                    .associateBy { it.name.lowercase() }
                    .toMutableMap()

                suspend fun ensureLabel(name: String): Label {
                    val key = name.trim().lowercase()
                    labelMap[key]?.let { return it }
                    val id = repository.insertLabel(Label(name = name.trim()))
                    val label = Label(id = id, name = name.trim())
                    labelMap[key] = label
                    labelsCreated++
                    return label
                }

                backupData.labels.forEach { label ->
                    if (label.name.isNotBlank()) {
                        ensureLabel(label.name)
                    }
                }

                val basePosition = repository.getNextNotePosition()

                backupData.notes.forEach { noteDto ->
                    val resolvedLabels = noteDto.labels.map { name ->
                        ensureLabel(name)
                    }

                    val checklist = noteDto.checklist.map { item ->
                        ChecklistItem(
                            text = item.text.take(MAX_FIELD_CHARS),
                            isChecked = item.isChecked,
                            position = item.position
                        )
                    }

                    val reminderTimestamp = noteDto.reminderTimestamp
                        ?.takeIf { it > DateUtils.currentTimeMillis() }

                    val note = Note(
                        title = noteDto.title.take(MAX_FIELD_CHARS),
                        content = noteDto.content.take(MAX_CONTENT_CHARS),
                        timestamp = noteDto.timestamp,
                        color = noteDto.color,
                        isPinned = noteDto.isPinned,
                        isArchived = noteDto.isArchived,
                        isTrashed = noteDto.isTrashed,
                        position = basePosition + notesImported,
                        reminderTimestamp = reminderTimestamp,
                        labels = resolvedLabels,
                        attachments = emptyList(),
                        checklist = checklist
                    )

                    val newId = repository.insertNoteWithoutSync(note)
                    importedIds += newId
                    noteDto.id?.let { oldId ->
                        if (!newNoteIdByOldId.containsKey(oldId)) {
                            newNoteIdByOldId[oldId] = newId
                        }
                    }
                    notesImported++
                }
            }

            repository.finalizeImportedNotes(importedIds)
            BackupImportResult.Success(
                notesImported = notesImported,
                labelsCreated = labelsCreated,
                attachmentsSkipped = attachmentsSkipped,
                newNoteIdByOldId = newNoteIdByOldId,
            )
        } catch (e: Exception) {
            BackupImportResult.Error(e)
        }
    }

    private class Unwrapped(
        val document: String,
        val attachmentsSkipped: Int,
        val rejection: BackupImportResult.InvalidFormat? = null,
    )

    /**
     * Peels a bundle manifest down to the v3 document inside it, or passes a plain backup through.
     *
     * A manifest is recognised by `formatVersion`, which a v3 document does not have (it carries
     * `version`). Nothing else about the file is assumed: a manifest from a newer format is
     * refused by number rather than parsed hopefully.
     */
    private fun unwrapBundleManifest(jsonStr: String): Unwrapped {
        val root = runCatching { json.parseToJsonElement(jsonStr) as? JsonObject }.getOrNull()
            ?: return Unwrapped(jsonStr, 0)
        val formatVersion = root["formatVersion"]?.jsonPrimitive?.intOrNull
            ?: return Unwrapped(jsonStr, 0)
        if (formatVersion > BackupBundleManifest.BUNDLE_FORMAT_VERSION) {
            return Unwrapped(
                jsonStr,
                0,
                BackupImportResult.InvalidFormat("Unsupported backup version: $formatVersion"),
            )
        }
        val manifest = json.decodeFromString<BackupBundleManifest>(jsonStr)
        return Unwrapped(
            document = manifest.backup.toString(),
            attachmentsSkipped = manifest.attachments.size,
        )
    }

    /**
     * Returns the failure to report, or null when [backupData] is safe to import.
     * Purely a read of the payload — it must not touch the repository.
     */
    private fun validate(backupData: BackupData): BackupImportResult.InvalidFormat? {
        if (backupData.notes.size > MAX_BACKUP_NOTES) {
            return BackupImportResult.InvalidFormat("Backup has too many notes (max $MAX_BACKUP_NOTES)")
        }
        if (backupData.labels.size > MAX_BACKUP_LABELS) {
            return BackupImportResult.InvalidFormat("Backup has too many labels (max $MAX_BACKUP_LABELS)")
        }
        backupData.notes.forEach { noteDto ->
            if (noteDto.labels.size > MAX_NOTE_LABELS) {
                return BackupImportResult.InvalidFormat("Note has too many labels (max $MAX_NOTE_LABELS)")
            }
            if (noteDto.checklist.size > MAX_NOTE_CHECKLIST) {
                return BackupImportResult.InvalidFormat("Note has too many checklist items (max $MAX_NOTE_CHECKLIST)")
            }
        }
        return null
    }

    companion object {
        const val MAX_BACKUP_CHARS = 10 * 1024 * 1024
        const val MAX_BACKUP_NOTES = 5_000
        const val MAX_BACKUP_LABELS = 2_000
        const val MAX_NOTE_LABELS = 100
        const val MAX_NOTE_CHECKLIST = 500
        const val MAX_FIELD_CHARS = 2_000
        const val MAX_CONTENT_CHARS = 100_000
        const val MAX_JSON_DEPTH = 64
    }

    /**
     * Maximum nesting depth of `{`/`[` in [jsonStr], ignoring braces inside strings.
     *
     * Well above anything a real backup produces (a note nests about a dozen levels), and far
     * below the stack the serialization runtime needs to decode it.
     */
    private fun maxJsonNestingDepth(jsonStr: String): Int {
        var depth = 0
        var maxDepth = 0
        var inString = false
        var escaped = false
        for (ch in jsonStr) {
            when {
                inString -> when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                ch == '"' -> inString = true
                ch == '{' || ch == '[' -> {
                    depth++
                    if (depth > maxDepth) maxDepth = depth
                }
                ch == '}' || ch == ']' -> if (depth > 0) depth--
            }
        }
        return maxDepth
    }
}
