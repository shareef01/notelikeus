package com.aus.notelikeus.util

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.aus.notelikeus.ui.widget.NoteWidget

object WidgetUpdater {
    suspend fun refresh(context: Context) {
        NoteWidget().updateAll(context)
    }
}
