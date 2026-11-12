package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Label(
    val id: Long? = null,
    val name: String
)

/**
 * The form two label names are compared in: trimmed, inner whitespace collapsed, lower case.
 * "Work", " work " and "WORK" are the same label. Matches web `labelNameKey` (`web/src/types/label.ts`).
 */
fun labelNameKey(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase()

/** Whether [name] is already used by a label other than [exceptId]. */
fun List<Label>.hasLabelNamed(name: String, exceptId: Long? = null): Boolean {
    val key = labelNameKey(name)
    return any { it.id != exceptId && labelNameKey(it.name) == key }
}

/** How many of [notes] carry each label, by label id. Notes in the trash are the caller's to leave out. */
fun labelNoteCounts(notes: List<Note>): Map<Long, Int> =
    notes.flatMap { note -> note.labels.mapNotNull { it.id }.distinct() }
        .groupingBy { it }
        .eachCount()
