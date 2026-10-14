package com.aus.notelikeus.ui.main

import com.aus.notelikeus.data.sync.ConflictResolution
import com.aus.notelikeus.domain.model.Note

/** What the user chose in the conflict review. Every choice is recoverable; see [ConflictReview]. */
enum class ConflictChoice {
    /** Both stay; the copy loses its suffix and becomes an ordinary note. */
    KEEP_BOTH,

    /** The note the copy was split from stays; the copy goes to Trash. */
    KEEP_CURRENT,

    /** The copy takes the original's title; the original goes to Trash. */
    KEEP_COPY,
}

/** A conflicted copy under review, and the note it was most likely split from (null if unclear). */
data class ConflictReviewState(val copy: Note, val original: Note?)

data class ReviewLine(val text: String, val changed: Boolean)

/**
 * Reviewing a conflicted copy against its original (AU-08) — the Kotlin twin of the web client's
 * `conflictReview.ts`, with the same rules.
 *
 * A copy carries no link to its original (it is an ordinary note so every client can sync it), but
 * its title is the original's plus [ConflictResolution.COPY_SUFFIX], so the original is found by
 * title. That is a best guess: when no single live note matches, there is no pairing, and the
 * review offers the copy on its own rather than pairing it with the wrong note.
 */
object ConflictReview {

    /** The title the copy's original had, or null for an untitled copy (nothing to match on). */
    fun originalTitleOf(copyTitle: String): String? =
        if (copyTitle.endsWith(ConflictResolution.COPY_SUFFIX)) {
            copyTitle.removeSuffix(ConflictResolution.COPY_SUFFIX)
        } else {
            null
        }

    /** The live note the copy was most likely split from, or null when there is no unambiguous match. */
    fun findOriginal(copy: Note, notes: List<Note>): Note? {
        val title = originalTitleOf(copy.title)?.takeIf { it.isNotBlank() } ?: return null
        val candidates = notes.filter { note ->
            note.id != copy.id &&
                !note.isTrashed &&
                !ConflictResolution.isConflictedCopyTitle(note.title) &&
                note.title == title
        }
        return candidates.singleOrNull()
    }

    private fun lines(note: Note): List<String> {
        val body = if (note.content.isEmpty()) emptyList() else note.content.split('\n')
        val items = note.checklist.sortedBy { it.position }
            .map { "${if (it.isChecked) "☑" else "☐"} ${it.text}" }
        return body + items
    }

    private fun markAgainst(lines: List<String>, other: List<String>): List<ReviewLine> {
        val left = other.groupingBy { it }.eachCount().toMutableMap()
        return lines.map { text ->
            val n = left[text] ?: 0
            if (n > 0) {
                left[text] = n - 1
                ReviewLine(text, changed = false)
            } else {
                ReviewLine(text, changed = true)
            }
        }
    }

    /** Each version's lines, marked where the other version has no such line. */
    fun compareVersions(a: Note, b: Note): Pair<List<ReviewLine>, List<ReviewLine>> {
        val linesA = lines(a)
        val linesB = lines(b)
        return markAgainst(linesA, linesB) to markAgainst(linesB, linesA)
    }
}
