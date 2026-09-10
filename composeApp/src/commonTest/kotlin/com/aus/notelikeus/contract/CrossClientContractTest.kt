package com.aus.notelikeus.contract

import com.aus.notelikeus.data.backup.BackupBundleManifest
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.sync.ChecklistItemData
import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds this client to the cross-client wire formats in `contracts/`.
 *
 * The web client is a separate implementation of the same backup file and the same Supabase
 * payloads, so the two can drift silently — a renamed field, a different default, a type only one
 * side coerces. These fixtures are the shared statement of what both must produce and accept; the
 * matching TypeScript assertions live in `web/src/lib/contract/crossClientContract.test.ts`.
 */
class CrossClientContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The client-neutral normal form from `contracts/README.md`. Local row ids are excluded. */
    private data class NormalizedNote(
        val title: String,
        val content: String,
        val timestamp: Long,
        val color: Int,
        val isPinned: Boolean,
        val isArchived: Boolean,
        val isTrashed: Boolean,
        val reminderTimestamp: Long?,
        val labels: List<String>,
        val checklist: List<ChecklistItemData>,
    )

    private fun Note.normalized() = NormalizedNote(
        title = title,
        content = content,
        timestamp = timestamp,
        color = color,
        isPinned = isPinned,
        isArchived = isArchived,
        isTrashed = isTrashed,
        reminderTimestamp = reminderTimestamp,
        labels = labels.map { it.name }.sorted(),
        checklist = checklist.sortedBy { it.position }
            .map { ChecklistItemData(it.text, it.isChecked, it.position) },
    )

    private fun JsonObject.normalizedNote() = NormalizedNote(
        title = getValue("title").jsonPrimitive.content,
        content = getValue("content").jsonPrimitive.content,
        timestamp = getValue("timestamp").jsonPrimitive.long,
        color = getValue("color").jsonPrimitive.int,
        isPinned = getValue("isPinned").jsonPrimitive.boolean,
        isArchived = getValue("isArchived").jsonPrimitive.boolean,
        isTrashed = getValue("isTrashed").jsonPrimitive.boolean,
        reminderTimestamp = getValue("reminderTimestamp").jsonPrimitive.longOrNull,
        labels = getValue("labels").jsonArray.map { it.jsonPrimitive.content }.sorted(),
        checklist = getValue("checklist").jsonArray.map { it.jsonObject.checklistItem(0) }
            .sortedBy { it.position },
    )

    private fun JsonObject.checklistItem(fallbackPosition: Int) = ChecklistItemData(
        text = this["text"]?.jsonPrimitive?.content.orEmpty(),
        isChecked = this["isChecked"]?.jsonPrimitive?.booleanOrNull == true,
        position = this["position"]?.jsonPrimitive?.int ?: fallbackPosition,
    )

    private fun fixture(name: String): JsonObject =
        json.parseToJsonElement(ContractFixtures.read(name)).jsonObject

    private fun expectedBackupNotes(): List<NormalizedNote> =
        fixture("backup/v3-expected-notes.json").getValue("notes").jsonArray
            .map { it.jsonObject.normalizedNote() }

    private suspend fun importFixture(name: String): Pair<FakeNoteRepository, BackupImportResult> {
        val repository = FakeNoteRepository()
        val result = NoteBackupImporter(repository).importFromJson(ContractFixtures.read(name))
        return repository to result
    }

    // ---- backup v3 ----

    @Test
    fun importsItsOwnExportIntoTheSharedNormalForm() = runTest {
        val (repository, result) = importFixture("backup/v3-kotlin-export.json")
        assertEquals(
            BackupImportResult.Success(notesImported = 3, labelsCreated = 2),
            result,
            "Kotlin-exported v3 backup must import cleanly",
        )
        assertEquals(expectedBackupNotes(), repository.insertedNotes.map { it.normalized() })
    }

    @Test
    fun importsAWebExportedBackupIntoTheSharedNormalForm() = runTest {
        val (repository, result) = importFixture("backup/v3-web-export.json")
        assertEquals(
            BackupImportResult.Success(notesImported = 3, labelsCreated = 2),
            result,
            "A backup written by the web client must import on Android and Windows",
        )
        assertEquals(expectedBackupNotes(), repository.insertedNotes.map { it.normalized() })
    }

    @Test
    fun exportsTheShapeTheWebImporterParses() = runTest {
        val canonical = fixture("backup/v3-kotlin-export.json")
        val repository = FakeNoteRepository()
        canonical.getValue("notes").jsonArray.forEach { element ->
            val row = element.jsonObject
            repository.insertNoteWithResult(
                Note(
                    id = row.getValue("id").jsonPrimitive.long,
                    title = row.getValue("title").jsonPrimitive.content,
                    content = row.getValue("content").jsonPrimitive.content,
                    timestamp = row.getValue("timestamp").jsonPrimitive.long,
                    color = row.getValue("color").jsonPrimitive.int,
                    isPinned = row.getValue("isPinned").jsonPrimitive.boolean,
                    isArchived = row.getValue("isArchived").jsonPrimitive.boolean,
                    isTrashed = row.getValue("isTrashed").jsonPrimitive.boolean,
                    position = row.getValue("position").jsonPrimitive.int,
                    reminderTimestamp = row.getValue("reminderTimestamp").jsonPrimitive.longOrNull,
                    labels = row.getValue("labels").jsonArray.mapIndexed { index, name ->
                        Label(id = index.toLong(), name = name.jsonPrimitive.content)
                    },
                    checklist = row.getValue("checklist").jsonArray.mapIndexed { index, item ->
                        val parsed = item.jsonObject.checklistItem(index)
                        ChecklistItem(
                            text = parsed.text,
                            isChecked = parsed.isChecked,
                            position = parsed.position,
                        )
                    },
                )
            )
        }

        val exported = json.parseToJsonElement(
            NoteBackupExporter(repository, "Notelikeus", "1.0.3").createJson(),
        ).jsonObject

        assertEquals(canonical.getValue("version"), exported.getValue("version"))
        assertEquals(canonical.getValue("app"), exported.getValue("app"))
        assertEquals(
            canonical.getValue("notes"),
            exported.getValue("notes"),
            "Exported note objects are the cross-client wire format",
        )
    }

    // ---- backup v4 bundle ----

    /**
     * The bundle's one structural promise: `manifest.backup` is a v3 document, verbatim.
     *
     * If it ever stops being one, a `.nlkbak` written by the web client becomes unreadable here —
     * this platform has no archive reader yet and recovers notes from the manifest alone.
     */
    @Test
    fun importsTheV3DocumentEmbeddedInABundleManifest() = runTest {
        val manifest = fixture("backup/v4-bundle-manifest.json").getValue("manifest").jsonObject
        val repository = FakeNoteRepository()

        val result = NoteBackupImporter(repository)
            .importFromJson(manifest.toString()) as BackupImportResult.Success

        assertEquals(1, result.notesImported, "the wrapped v3 document must import")
        assertEquals(1, result.labelsCreated)
        assertEquals(
            manifest.getValue("attachments").jsonArray.size,
            result.attachmentsSkipped,
            "images the bundle carries must be reported, not silently dropped",
        )
        val imported = repository.insertedNotes.single()
        assertEquals("Trip", imported.title)
        assertEquals(listOf("Travel"), imported.labels.map { it.name })
        assertEquals(4102444800000L, imported.reminderTimestamp)
    }

    @Test
    fun refusesABundleFromANewerFormat() = runTest {
        val manifest = fixture("backup/v4-bundle-manifest.json").getValue("manifest").jsonObject
        val newer = JsonObject(
            manifest.toMutableMap().apply {
                put("formatVersion", JsonPrimitive(BackupBundleManifest.BUNDLE_FORMAT_VERSION + 1))
            }
        )

        val result = NoteBackupImporter(FakeNoteRepository()).importFromJson(newer.toString())

        assertTrue(
            result is BackupImportResult.InvalidFormat &&
                result.message.contains("Unsupported backup version"),
            "a newer bundle must be refused by number, not parsed hopefully: got $result",
        )
    }

    @Test
    fun aPlainV3BackupIsStillImportedUnwrapped() = runTest {
        // The unwrap step must be invisible to the format that has no wrapper.
        val (repository, result) = importFixture("backup/v3-kotlin-export.json")
        assertEquals(
            BackupImportResult.Success(notesImported = 3, labelsCreated = 2, attachmentsSkipped = 0),
            result,
        )
        assertEquals(3, repository.insertedNotes.size)
    }

    // ---- cloud payloads ----

    /**
     * Mirrors `SupabaseNoteTransport.toCloudNoteRecord`. That function is private to the transport
     * and only reachable through an HTTP client, so the parse is restated here against the same
     * row the server sends — a change to either has to be reflected in the other.
     */
    private fun JsonObject.toRecordUnderTest(): CloudNoteRecord = CloudNoteRecord(
        noteId = getValue("note_id").jsonPrimitive.content.toLong(),
        serverUpdatedAt = this["server_updated_at"]?.jsonPrimitive?.longOrNull,
        clientTimestamp = this["client_timestamp"]?.jsonPrimitive?.longOrNull,
        title = this["title"]?.jsonPrimitive?.content.orEmpty(),
        content = this["content"]?.jsonPrimitive?.content.orEmpty(),
        timestamp = this["client_timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
        color = this["color"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
        isPinned = this["is_pinned"]?.jsonPrimitive?.booleanOrNull == true,
        isArchived = this["is_archived"]?.jsonPrimitive?.booleanOrNull == true,
        isTrashed = this["is_trashed"]?.jsonPrimitive?.booleanOrNull == true,
        position = this["position"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
        reminderTimestamp = this["reminder_timestamp"]?.jsonPrimitive?.longOrNull,
        labels = this["labels"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
        },
        checklistItems = this["checklist"]?.jsonArray.orEmpty().mapIndexed { index, element ->
            element.jsonObject.checklistItem(index)
        },
    )

    private fun assertCloudRow(name: String) {
        val row = fixture(name)
        val record = row.getValue("row").jsonObject.toRecordUnderTest()
        val expected = row.getValue("expected").jsonObject

        assertEquals(expected.getValue("localId").jsonPrimitive.long, record.noteId)
        assertEquals(expected.getValue("title").jsonPrimitive.content, record.title)
        assertEquals(expected.getValue("content").jsonPrimitive.content, record.content)
        assertEquals(expected.getValue("timestamp").jsonPrimitive.long, record.timestamp)
        assertEquals(expected.getValue("color").jsonPrimitive.int, record.color)
        assertEquals(expected.getValue("isPinned").jsonPrimitive.boolean, record.isPinned)
        assertEquals(expected.getValue("isArchived").jsonPrimitive.boolean, record.isArchived)
        assertEquals(expected.getValue("isTrashed").jsonPrimitive.boolean, record.isTrashed)
        assertEquals(expected.getValue("position").jsonPrimitive.int, record.position)
        assertEquals(
            expected.getValue("reminderTimestamp").jsonPrimitive.longOrNull,
            record.reminderTimestamp,
        )
        assertEquals(
            expected.getValue("serverUpdatedAt").jsonPrimitive.longOrNull,
            record.serverUpdatedAt,
        )
        assertEquals(
            expected.getValue("labels").jsonArray.map { it.jsonPrimitive.content },
            record.labels,
        )
        assertEquals(
            expected.getValue("checklist").jsonArray.mapIndexed { index, item ->
                item.jsonObject.checklistItem(index)
            },
            record.checklistItems,
        )
    }

    @Test
    fun parsesAFullCloudNoteRow() = assertCloudRow("cloud/note-row.json")

    @Test
    fun parsesASparseCloudNoteRow() = assertCloudRow("cloud/note-row-sparse.json")

    @Test
    fun derivesTheTombstoneMapFromTombstoneRows() {
        val tombstones = fixture("cloud/tombstone-row.json")
        val parsed = tombstones.getValue("rows").jsonArray.associate { element ->
            val row = element.jsonObject
            row.getValue("note_id").jsonPrimitive.content.toLong() to
                row.getValue("deleted_at").jsonPrimitive.long
        }
        val expected = tombstones.getValue("expected").jsonObject
            .mapKeys { it.key.toLong() }
            .mapValues { it.value.jsonPrimitive.long }
        assertEquals(expected, parsed)
    }

    @Test
    fun buildsTheApplyNoteChangeArgumentObject() {
        val args = fixture("cloud/note-rpc-args.json").getValue("args").jsonObject
        val record = fixture("cloud/note-row.json").getValue("row").jsonObject.toRecordUnderTest()

        // Restates SupabaseNoteTransport.toRpcArgs, which is private to the transport.
        assertEquals(args.getValue("p_note_id").jsonPrimitive.content, record.noteId.toString())
        assertEquals(args.getValue("p_local_id").jsonPrimitive.long, record.noteId)
        assertEquals(args.getValue("p_title").jsonPrimitive.content, record.title)
        assertEquals(args.getValue("p_content").jsonPrimitive.content, record.content)
        assertEquals(args.getValue("p_client_timestamp").jsonPrimitive.long, record.timestamp)
        assertEquals(args.getValue("p_color").jsonPrimitive.int, record.color)
        assertEquals(args.getValue("p_is_pinned").jsonPrimitive.boolean, record.isPinned)
        assertEquals(args.getValue("p_is_archived").jsonPrimitive.boolean, record.isArchived)
        assertEquals(args.getValue("p_is_trashed").jsonPrimitive.boolean, record.isTrashed)
        assertEquals(args.getValue("p_position").jsonPrimitive.int, record.position)
        assertEquals(
            args.getValue("p_reminder_timestamp").jsonPrimitive.longOrNull,
            record.reminderTimestamp,
        )
        assertEquals(
            args.getValue("p_labels").jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content },
            record.labels,
        )
        assertEquals(
            args.getValue("p_checklist").jsonArray.mapIndexed { index, item ->
                item.jsonObject.checklistItem(index)
            },
            record.checklistItems,
        )
    }

    @Test
    fun importSoftCapsMatchTheSharedFixture() {
        val limits = fixture("backup/import-limits.json")
        assertEquals(limits.getValue("maxJsonDepth").jsonPrimitive.int, NoteBackupImporter.MAX_JSON_DEPTH)
        assertEquals(limits.getValue("maxBackupNotes").jsonPrimitive.int, NoteBackupImporter.MAX_BACKUP_NOTES)
        assertEquals(limits.getValue("maxBackupLabels").jsonPrimitive.int, NoteBackupImporter.MAX_BACKUP_LABELS)
        assertEquals(limits.getValue("maxNoteTitleChars").jsonPrimitive.int, NoteBackupImporter.MAX_FIELD_CHARS)
        assertEquals(limits.getValue("maxNoteContentChars").jsonPrimitive.int, NoteBackupImporter.MAX_CONTENT_CHARS)
        assertEquals(limits.getValue("maxNoteChecklistItems").jsonPrimitive.int, NoteBackupImporter.MAX_NOTE_CHECKLIST)
        assertEquals(limits.getValue("maxNoteLabels").jsonPrimitive.int, NoteBackupImporter.MAX_NOTE_LABELS)
    }
}
