package com.aus.notelikeus.data.sync

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

    /** True when writing [cloud] over [local] would destroy words only this device has. */
    fun mustPreserve(local: Note, cloud: Note, syncedTimestamp: Long?): Boolean =
        isLocallyEdited(local, syncedTimestamp) && !authoredContentEqual(local, cloud)

    /** A new, never-synced note carrying [local]'s words. Room assigns the id on insert. */
    fun conflictedCopyOf(local: Note): Note {
        val title = if (local.title.isBlank()) {
            BLANK_COPY_TITLE
        } else {
            local.title.take(MAX_TITLE_LENGTH - COPY_SUFFIX.length) + COPY_SUFFIX
        }
        return local.copy(
            id = null,
            title = title,
            serverUpdatedAt = null,
            // Attachment rows and R2 objects are keyed by the original note id and stay with it.
            attachments = emptyList(),
            checklist = local.checklist.map { it.copy(id = null) },
            searchText = null,
        )
    }
}
