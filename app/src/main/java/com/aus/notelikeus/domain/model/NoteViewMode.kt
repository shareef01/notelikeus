package com.aus.notelikeus.domain.model

enum class NoteViewMode(val columns: Int, val compact: Boolean) {
    GRID_2(columns = 2, compact = false),
    GRID_3(columns = 3, compact = false),
    LIST(columns = 1, compact = false),
    COMPACT(columns = 1, compact = true);

    fun next(): NoteViewMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromName(name: String?): NoteViewMode =
            entries.find { it.name == name } ?: GRID_2
    }
}

enum class NoteSortOrder {
    MANUAL,
    NEWEST,
    OLDEST;

    fun next(): NoteSortOrder {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromName(name: String?): NoteSortOrder =
            entries.find { it.name == name } ?: MANUAL
    }
}
