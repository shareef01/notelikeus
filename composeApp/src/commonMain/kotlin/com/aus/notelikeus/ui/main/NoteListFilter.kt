package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.ui.theme.noteColorsMatch

/**
 * Pure list filtering and sorting for the main note grid. Extracted from MainViewModel so it
 * can be reasoned about (and later tested) without the state-machine around it.
 */
internal fun filterAndSortNotes(s: MainState, hiddenIds: Set<Long>): List<Note> {
    val filtered = s.notes.filter { note ->
        val noteId = note.id
        if (noteId != null && noteId in hiddenIds) return@filter false

        val matchesSearch = s.searchQuery.isEmpty() ||
            note.title.contains(s.searchQuery, ignoreCase = true) ||
            note.content.contains(s.searchQuery, ignoreCase = true) ||
            note.checklist.any { it.text.contains(s.searchQuery, ignoreCase = true) } ||
            note.labels.any { it.name.contains(s.searchQuery, ignoreCase = true) }

        val matchesColor = s.selectedColor == null || noteColorsMatch(note.color, s.selectedColor)

        val matchesLabel = s.selectedLabelId == null ||
            note.labels.any { it.id == s.selectedLabelId }

        matchesSearch && matchesColor && matchesLabel
    }
    return when (s.sortOrder) {
        NoteSortOrder.MANUAL -> {
            filtered.filter { it.isPinned } + filtered.filter { !it.isPinned }
        }
        NoteSortOrder.NEWEST -> {
            filtered.filter { it.isPinned }.sortedByDescending { it.timestamp } +
                filtered.filter { !it.isPinned }.sortedByDescending { it.timestamp }
        }
        NoteSortOrder.OLDEST -> {
            filtered.filter { it.isPinned }.sortedBy { it.timestamp } +
                filtered.filter { !it.isPinned }.sortedBy { it.timestamp }
        }
    }
}
