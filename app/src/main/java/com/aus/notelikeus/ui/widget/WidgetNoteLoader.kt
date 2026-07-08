package com.aus.notelikeus.ui.widget

import android.content.Context
import com.aus.notelikeus.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

data class WidgetNote(
    val id: Long,
    val title: String,
    val preview: String,
    val isLocked: Boolean
)

object WidgetNoteLoader {
    suspend fun loadNotes(context: Context): List<WidgetNote> {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        return entryPoint.noteDao().getWidgetNotes().map { noteWithRelations ->
            val note = noteWithRelations.note
            WidgetNote(
                id = note.id,
                title = note.title,
                preview = note.content.lineSequence().firstOrNull().orEmpty(),
                isLocked = note.isLocked
            )
        }
    }
}

object WidgetUpdater {
    suspend fun refresh(context: Context) {
        NoteWidget().updateAll(context)
    }
}
