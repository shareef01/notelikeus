package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note

/**
 * Concurrent-edit preservation — the Kotlin twin of the web client's `conflictResolution.ts`.
 *
 * The server's revision check stops a stale upload from overwriting a newer cloud copy, but it does
 * not say what happens to the stale side. [NoteSyncEngine.downloadAllNotes] used to answer "the cloud
 * wins" and write the cloud copy over the local row even when that row held an edit this device had
 * never uploaded, so two devices editing the same note offline lost whichever edit synced second.
 *
 * The rule now: when the cloud moved on (or deleted the note) *and* this device holds an unsynced
 * authored edit that differs, the cloud copy keeps the original id and the local edit is re-homed
 * under a new id as a "conflicted copy", which uploads like any new note. Nothing is merged and
 * nothing is discarded.
 *
 * "Unsynced edit" is measured against [NoteSyncStateStore.syncedTimestampById]: the client
 * timestamp of the version last known identical to the cloud's. Every local edit re-stamps the row,
 * so a different timestamp means an edit. Equality, not ordering: a skewed clock cannot fake one.
 */
internal object ConflictResolution {
    const val COPY_SUFFIX = " (conflicted copy)"
    const val BLANK_COPY_TITLE = "Conflicted copy"

    /** `validate_note_payload` rejects longer titles; a copy that can never upload is not preserved. */
    private const val MAX_TITLE_LENGTH = 2000

    /**
     * Exact with a baseline. Without one (state from before baselines existed) it falls back to
     * "edited after the last server stamp the row saw", which trusts the clock — one redundant copy is
     * the cost of a mistake there, lost text the cost of the opposite one. A row never stamped and
     * without a baseline has nothing to measure against and is not treated as edited: it is either
     * local-only, or an upload whose result never landed, which [authoredContentEqual] covers.
     */
    fun isLocallyEdited(local: Note, syncedTimestamp: Long?): Boolean {
        if (syncedTimestamp != null) return local.timestamp != syncedTimestamp
        val stamp = local.serverUpdatedAt ?: return false
        return local.timestamp > stamp
    }

    /**
     * Same words: title, body, labels and checklist. Pin, colour, order, archive state and reminders
     * are not worth a duplicate note; attachments are not carried by a cloud record at all.
     */
    fun authoredContentEqual(a: Note, b: Note): Boolean =
        a.title == b.title &&
            a.content == b.content &&
            a.labels.map { it.name }.sorted() == b.labels.map { it.name }.sorted() &&
            a.checklist.sortedBy { it.position }.map { it.isChecked to it.text } ==
            b.checklist.sortedBy { it.position }.map { it.isChecked to it.text }

    /** What an inbound cloud version may do to the local row. Pinned by `contracts/sync/inbound-resolution.json`. */
    enum class InboundAction {
        /** Write the cloud version (a no-op when identical). */
        ACCEPT,

        /** Keep the local row; it is newer or a pending edit on the current revision, and is pushed. */
        KEEP_LOCAL,

        /** Re-home the local words as a conflicted copy, then write the cloud version. */
        FORK,
    }

    /**
     * The download's decision for one cloud record, shared by [NoteSyncEngine] and the contract test.
     *
     * @param cloudWinsByStamp [NoteSyncEngine.cloudWinsConflict] for this pair: whether the server
     *   stamps (and, on a tie, the client clocks) favour the cloud copy.
     */
    fun decide(local: Note?, syncedTimestamp: Long?, cloud: Note, cloudWinsByStamp: Boolean): InboundAction {
        if (local == null) return InboundAction.ACCEPT
        val edited = isLocallyEdited(local, syncedTimestamp)
        // The edit sits on exactly the revision the cloud still holds: pending, not conflicting,
        // whatever the two client clocks say about each other.
        if (edited && cloud.serverUpdatedAt != null && cloud.serverUpdatedAt == local.serverUpdatedAt) {
            return InboundAction.KEEP_LOCAL
        }
        if (!cloudWinsByStamp) return InboundAction.KEEP_LOCAL
        return if (edited && !authoredContentEqual(local, cloud)) InboundAction.FORK else InboundAction.ACCEPT
    }

    /**
     * A short fingerprint of a note's words (the fields [authoredContentEqual] compares), recorded
     * when a note is deleted so a later download can tell whether the cloud copy still says exactly
     * what the user deleted (AU-07). The same 53-bit hash (cyrb53) and key as the web client's
     * `authoredContentDigest`, so the two agree on any note; not a secret and not reversible.
     */
    fun authoredContentDigest(note: Note): String = authoredContentDigest(
        title = note.title,
        content = note.content,
        checklist = note.checklist.map { Triple(it.position, it.isChecked, it.text) },
        labels = note.labels.map { it.name },
    )

    /** [authoredContentDigest] from raw fields, for a cloud record whose labels are not resolved. */
    fun authoredContentDigest(
        title: String,
        content: String,
        checklist: List<Triple<Int, Boolean, String>>,
        labels: List<String>,
    ): String {
        val checklistKey = checklist.sortedBy { it.first }
            .joinToString("\u001e") { "${if (it.second) 1 else 0}:${it.third}" }
        val key = listOf(title, content, checklistKey, labels.sorted().joinToString("\u001e"))
            .joinToString("\u001f")
        // Int arithmetic wraps exactly like JavaScript's Math.imul; ushr is >>>.
        var h1 = 0xdeadbeef.toInt()
        var h2 = 0x41c6ce57
        for (ch in key) {
            val c = ch.code
            h1 = (h1 xor c) * -1640531535 // 2654435761
            h2 = (h2 xor c) * 1597334677
        }
        h1 = ((h1 xor (h1 ushr 16)) * -2048144789) xor ((h2 xor (h2 ushr 13)) * -1028477387)
        h2 = ((h2 xor (h2 ushr 16)) * -2048144789) xor ((h1 xor (h1 ushr 13)) * -1028477387)
        val value = 4294967296L * (2097151L and h2.toLong()) + (h1.toLong() and 0xffffffffL)
        return value.toString(36)
    }

    /** Whether [title] is one a conflict resolution wrote (every client writes the same suffix). */
    fun isConflictedCopyTitle(title: String): Boolean =
        title == BLANK_COPY_TITLE || title.endsWith(COPY_SUFFIX)

    /**
     * A new, never-synced note carrying [local]'s words. Room assigns the id on insert.
     *
     * [attachments] are the copy's own: attachment rows and R2 objects are keyed by the original
     * note id and stay with it, so only images re-staged for the copy under new ids
     * ([com.aus.notelikeus.data.attachments.AttachmentSyncService.stageForCopy]) may come along.
     */
    fun conflictedCopyOf(local: Note, attachments: List<Attachment> = emptyList()): Note {
        val title = if (local.title.isBlank()) {
            BLANK_COPY_TITLE
        } else {
            local.title.take(MAX_TITLE_LENGTH - COPY_SUFFIX.length) + COPY_SUFFIX
        }
        return local.copy(
            id = null,
            title = title,
            serverUpdatedAt = null,
            attachments = attachments,
            checklist = local.checklist.map { it.copy(id = null) },
            searchText = null,
        )
    }
}
