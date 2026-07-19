package com.aus.notelikeus.ui.widget

import android.content.Context
import android.content.res.Configuration
import androidx.glance.appwidget.updateAll
import com.aus.notelikeus.R
import com.aus.notelikeus.data.local.APP_LOCK_ENABLED_KEY
import com.aus.notelikeus.data.local.TRUE_DARK_MODE_KEY
import com.aus.notelikeus.data.local.USE_MONOCHROME_THEME_KEY
import com.aus.notelikeus.data.local.model.NoteWithLabelsAndAttachments
import com.aus.notelikeus.data.local.settingsDataStore
import com.aus.notelikeus.di.WidgetEntryPoint
import com.aus.notelikeus.domain.model.AppTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

data class WidgetNote(
    val id: Long,
    val title: String,
    val preview: String,
    val isLocked: Boolean,
    val isPinned: Boolean
)

object WidgetNoteLoader {
    suspend fun isAppLockEnabled(context: Context): Boolean {
        val preferences = context.settingsDataStore.data.first()
        return preferences[APP_LOCK_ENABLED_KEY] ?: false
    }

    suspend fun loadNotes(context: Context): List<WidgetNote> {
        if (isAppLockEnabled(context)) return emptyList()
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        return entryPoint.noteDao().getWidgetNotes().map { noteWithRelations ->
            val note = noteWithRelations.note
            WidgetNote(
                id = note.id,
                title = note.title,
                preview = buildPreview(context, noteWithRelations),
                isLocked = note.isLocked,
                isPinned = note.isPinned
            )
        }
    }

    private fun buildPreview(context: Context, item: NoteWithLabelsAndAttachments): String {
        val contentPreview = item.note.content.lineSequence().firstOrNull().orEmpty().trim()
        if (contentPreview.isNotEmpty()) return contentPreview
        if (item.checklist.isNotEmpty()) {
            val checked = item.checklist.count { it.isChecked }
            return context.getString(R.string.checklist_progress, checked, item.checklist.size)
        }
        return ""
    }

    suspend fun loadTheme(context: Context): WidgetThemeColors {
        val preferences = context.settingsDataStore.data.first()
        val appTheme = AppTheme.fromName(preferences[APP_THEME_KEY])
        val isSystemDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return when (appTheme) {
            AppTheme.TRUE_DARK -> WidgetThemes.TrueDark
            AppTheme.MIDNIGHT -> WidgetThemes.Midnight
            AppTheme.FOREST -> WidgetThemes.Forest
            AppTheme.LIGHT -> WidgetThemes.Light
            AppTheme.DARK -> WidgetThemes.Dark
            AppTheme.AUTO -> if (isSystemDark) WidgetThemes.Dark else WidgetThemes.Light
        }
    }
}

object WidgetUpdater {
    suspend fun refresh(context: Context) {
        NoteWidget().updateAll(context)
    }
}
