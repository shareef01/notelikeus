package com.aus.notelikeus.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.R

private data class WidgetStrings(
    val appName: String,
    val newNote: String,
    val empty: String,
    val lockedNote: String,
    val untitled: String,
    val pinned: String
)

/**
 * App Widget Overhaul
 * Geometric Discipline: Strict 16.dp corner radius and 16.dp grid padding.
 */
class NoteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notes = runCatching { WidgetNoteLoader.loadNotes(context) }.getOrDefault(emptyList())
        val theme = runCatching { WidgetNoteLoader.loadTheme(context) }.getOrDefault(WidgetThemes.Light)
        val strings = WidgetStrings(
            appName = context.getString(R.string.app_name),
            newNote = context.getString(R.string.widget_new),
            empty = context.getString(R.string.widget_empty),
            lockedNote = context.getString(R.string.locked_note),
            untitled = context.getString(R.string.untitled),
            pinned = context.getString(R.string.pinned_short)
        )

        provideContent {
            WidgetContent(notes = notes, theme = theme, strings = strings, context = context)
        }
    }

    @Composable
    private fun WidgetContent(
        notes: List<WidgetNote>,
        theme: WidgetThemeColors,
        strings: WidgetStrings,
        context: Context
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(theme.surface)
                .padding(16.dp), // Disciplined 16.dp Grid
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
                    modifier = GlanceModifier.fillMaxWidth().then(GlanceModifier.defaultWeight())
                )
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
                                    putExtra("createNote", true)
                                }
                            )
                        )
                )
            }

            Spacer(modifier = GlanceModifier.height(12.dp))

            if (notes.isEmpty()) {
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
                    Spacer(modifier = GlanceModifier.height(8.dp)) // Disciplined 8.dp Grid
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
                .cornerRadius(16.dp) // Strict 16.dp Geometry
                .background(theme.surfaceVariant)
                .padding(12.dp)
                .clickable(
                    actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
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
                    modifier = GlanceModifier.defaultWeight()
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
