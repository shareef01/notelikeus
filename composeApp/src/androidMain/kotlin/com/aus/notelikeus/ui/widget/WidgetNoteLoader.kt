package com.aus.notelikeus.ui.widget

import android.content.Context
import android.content.res.Configuration
import com.aus.notelikeus.data.local.APP_LOCK_ENABLED_KEY
import com.aus.notelikeus.data.local.APP_THEME_KEY
import com.aus.notelikeus.data.local.USE_MONOCHROME_THEME_KEY
import com.aus.notelikeus.data.local.model.NoteWithLabels
import com.aus.notelikeus.data.local.settingsDataStore
import com.aus.notelikeus.domain.model.AppTheme
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import com.aus.notelikeus.data.local.ACCENT_COLOR_KEY
import com.aus.notelikeus.data.local.TRUE_DARK_MODE_KEY
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.toThemePreference

data class WidgetNote(
    val id: Long,
    val title: String,
    val preview: String,
    val isPinned: Boolean,
    val color: Int
)

object WidgetNoteLoader {
    suspend fun isAppLockEnabled(context: Context): Boolean {
        val preferences = context.settingsDataStore.data.first()
        return preferences[APP_LOCK_ENABLED_KEY] ?: false
    }

    suspend fun loadNotes(context: Context): List<WidgetNote> {
        if (isAppLockEnabled(context)) return emptyList()
        val noteDao = GlobalContext.get().get<com.aus.notelikeus.data.local.dao.NoteDao>()
        return noteDao.getWidgetNotes().map { noteWithRelations ->
            val note = noteWithRelations.note
            WidgetNote(
                id = note.id,
                title = note.title,
                preview = buildPreview(context, noteWithRelations),
                isPinned = note.isPinned,
                color = note.color
            )
        }
    }

    private fun buildPreview(context: Context, item: NoteWithLabels): String {
        val contentPreview = item.note.content.lineSequence().firstOrNull().orEmpty().trim()
        if (contentPreview.isNotEmpty()) return contentPreview
        if (item.checklist.isNotEmpty()) {
            val checked = item.checklist.count { it.isChecked }
            // Using Res in androidMain might need access to shared resources or standard R
            // Since this is androidMain and it's a widget, using the app's R is safer.
            return context.getString(com.aus.notelikeus.shared.R.string.checklist_progress, checked, item.checklist.size)
        }
        return ""
    }

    suspend fun loadTheme(context: Context): WidgetThemeColors {
        val preferences = context.settingsDataStore.data.first()
        val isMonochrome = preferences[USE_MONOCHROME_THEME_KEY] ?: true
        val isSystemDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        // Resolved through the same mapping the app uses, not by comparing the stored name.
        //
        // This read `appTheme == AppTheme.TRUE_DARK` directly, which stopped being the whole
        // answer once AMOLED became a toggle independent of the base theme: turning Pure black on
        // writes APP_THEME_KEY=DARK and TRUE_DARK_MODE_KEY=true, so a name comparison sees "DARK"
        // and the widget stayed charcoal while the app went black. The legacy TRUE_DARK name still
        // resolves to amoled=true, so nobody who never touches the new toggle sees a change.
        val preference = AppTheme.fromName(preferences[APP_THEME_KEY]).toThemePreference(
            storedAmoled = preferences[TRUE_DARK_MODE_KEY] ?: false,
            storedAccent = AccentColor.fromName(preferences[ACCENT_COLOR_KEY])
        )
        // AMOLED is a black level for the dark schemes; on light it means nothing, which is the
        // same rule colorSchemeFor applies.
        val resolvedDark = when (preference.base) {
            ThemeBase.DARK -> true
            ThemeBase.LIGHT -> false
            ThemeBase.SYSTEM -> isSystemDark
        }

        return when {
            preference.amoled && resolvedDark -> WidgetThemes.TrueDark
            isMonochrome && isSystemDark -> WidgetThemes.MonochromeDark
            isMonochrome -> WidgetThemes.MonochromeLight
            isSystemDark -> WidgetThemes.Dark
            else -> WidgetThemes.Light
        }
    }
}
