package com.aus.notelikeus.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class BackupData(
    val version: Int,
    val exportedAt: Long = 0L,
    val app: String = "Notelikeus",
    val appVersion: String = "1.0.0",
    val labels: List<LabelBackupDto> = emptyList(),
    val notes: List<NoteBackupDto>
)

/**
 * A label as it appears in the backup file's root `labels` array.
 *
 * [id] is deliberately a raw [JsonElement] rather than a `Long?`. The two clients put different
 * things there — Kotlin writes the Room row id (`4`), the web client writes a slug
 * (`"label-travel"`) — and this field was previously typed as the domain `Label`, so decoding a
 * web-exported backup threw at `$.labels[0].id` and failed the whole import. Neither importer
 * has ever read it: labels are matched by name. It is carried rather than dropped so a file
 * written by this build still round-trips through the older one.
 */
@Serializable
data class LabelBackupDto(
    val id: JsonElement? = null,
    val name: String
)

@Serializable
data class NoteBackupDto(
    val id: Long? = null,
    val title: String,
    val content: String,
    val timestamp: Long,
    val color: Int,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val position: Int = 0,
    val reminderTimestamp: Long? = null,
    val labels: List<String> = emptyList(),
    val checklist: List<ChecklistItemBackupDto> = emptyList()
)

@Serializable
data class ChecklistItemBackupDto(
    val text: String,
    val isChecked: Boolean,
    val position: Int
)

/**
 * The `manifest.json` inside a `.nlkbak` bundle.
 *
 * The bundle wraps a v3 backup document **verbatim** rather than replacing it, which is what lets
 * this client read one without an archive reader: the notes are recoverable from the manifest
 * alone, and [NoteBackupImporter] hands [backup] straight to the unchanged v3 path. Attachment
 * bytes live in the archive's `media/` entries. JVM targets can build/parse those archives via
 * [com.aus.notelikeus.data.backup.bundle.BackupBundleCodec]; UI wiring for export/import is separate.
 * Until that ships, [NoteBackupImporter] still peels the embedded v3 document from a bare
 * `manifest.json` and reports how many images were left behind.
 *
 * The format itself is pinned by `contracts/backup/v4-bundle-manifest.json`.
 */
@Serializable
data class BackupBundleManifest(
    val formatVersion: Int,
    val app: String = "Notelikeus",
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    val backup: JsonObject,
    val attachments: List<BundleAttachmentDto> = emptyList(),
) {
    companion object {
        /** The newest bundle format this build understands. */
        const val BUNDLE_FORMAT_VERSION = 4
    }
}

@Serializable
data class BundleAttachmentDto(
    val noteId: Long? = null,
    val attachmentId: String = "",
    val path: String = "",
    val type: String = "image",
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)
