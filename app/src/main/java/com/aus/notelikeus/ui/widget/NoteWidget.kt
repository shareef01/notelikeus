package com.aus.notelikeus.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.ui.navigation.markInternalNavigation
import com.aus.notelikeus.R

private data class WidgetStrings(
    val appName: String,
    val newNote: String,
    val empty: String,
    val lockedNote: String,
    val untitled: String,
    val pinned: String,
    val appLocked: String
)

class NoteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appLocked = runCatching { WidgetNoteLoader.isAppLockEnabled(context) }.getOrDefault(false)
        val notes = if (appLocked) {
            emptyList()
        } else {
            runCatching { WidgetNoteLoader.loadNotes(context) }.getOrDefault(emptyList())
        }
        val theme = runCatching { WidgetNoteLoader.loadTheme(context) }.getOrDefault(WidgetThemes.Light)
        val strings = WidgetStrings(
            appName = context.getString(R.string.app_name),
            newNote = context.getString(R.string.widget_new),
            empty = context.getString(R.string.widget_empty),
            lockedNote = context.getString(R.string.locked_note),
            untitled = context.getString(R.string.untitled),
            pinned = context.getString(R.string.pinned_short),
            appLocked = context.getString(R.string.widget_app_locked)
        )
        provideContent {
            WidgetContent(context, notes, theme, strings, appLocked)
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        notes: List<WidgetNote>,
        theme: WidgetThemeColors,
        strings: WidgetStrings,
        appLocked: Boolean
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(theme.surface)
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.appName,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = theme.onSurface
                    ),
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                )
                if (!appLocked) {
                    Text(
                        text = strings.newNote,
                        style = TextStyle(
                            color = theme.primary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        modifier = GlanceModifier
                            .cornerRadius(16.dp) // Strict 16.dp Geometry
                            .background(theme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable(
                                actionStartActivity(
                                    Intent(context, MainActivity::class.java).apply {
                                        markInternalNavigation()
                                        putExtra("createNote", true)
                                    }
                                )
                            )
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(12.dp))

            if (appLocked) {
                Text(
                    text = strings.appLocked,
                    style = TextStyle(
                        color = theme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = GlanceModifier.padding(top = 16.dp)
                )
            } else if (notes.isEmpty()) {
                Text(
                    text = strings.empty,
                    style = TextStyle(
                        color = theme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = GlanceModifier.padding(top = 16.dp)
                )
            } else {
                notes.forEach { note ->
                    WidgetNoteRow(
                        context = context,
                        note = note,
                        theme = theme,
                        strings = strings
                    )
                    Spacer(modifier = GlanceModifier.height(8.dp))
                }
            }
        }
    }

    @Composable
    private fun WidgetNoteRow(
        context: Context,
        note: WidgetNote,
        theme: WidgetThemeColors,
        strings: WidgetStrings
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(16.dp)
                .background(theme.surfaceVariant)
                .padding(12.dp)
                .clickable(
                    actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            markInternalNavigation()
                            putExtra("noteId", note.id)
                        }
                    )
                )
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (note.isPinned) {
                    Text(
                        text = strings.pinned.uppercase(),
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.onSurfaceVariant
                        ),
                        modifier = GlanceModifier.padding(end = 6.dp)
                    )
                }
                Text(
                    text = when {
                        note.isLocked -> strings.lockedNote
                        note.title.isNotBlank() -> note.title
                        else -> strings.untitled
                    },
                    style = TextStyle(
                        fontWeight = FontWeight.Medium,
                        color = theme.onSurface,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                )
            }
            if (!note.isLocked && note.preview.isNotBlank()) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = note.preview,
                    style = TextStyle(
                        color = theme.onSurfaceVariant,
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()
}
