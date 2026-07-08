package com.aus.notelikeus.ui.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aus.notelikeus.MainActivity

private val NoteIdKey = ActionParameters.Key<Long>("noteId")

class NoteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notes = runCatching { WidgetNoteLoader.loadNotes(context) }.getOrDefault(emptyList())

        provideContent {
            WidgetContent(notes = notes)
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetContent(notes: List<WidgetNote>) {
        val surface = ColorProvider(Color(0xFFFDF8F2))
        val onSurface = ColorProvider(Color(0xFF1C1B1F))
        val onSurfaceVariant = ColorProvider(Color(0xFF49454F))
        val primary = ColorProvider(Color(0xFF6750A4))
        val primaryContainer = ColorProvider(Color(0xFFEADDFF))
        val surfaceVariant = ColorProvider(Color(0xFFE7E0EC))

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(surface)
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notelikeus",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = onSurface
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                Text(
                    text = "+ New",
                    style = TextStyle(
                        color = primary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    ),
                    modifier = GlanceModifier
                        .cornerRadius(12.dp)
                        .background(primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable(actionStartActivity<MainActivity>())
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            if (notes.isEmpty()) {
                Text(
                    text = "No notes yet",
                    style = TextStyle(color = onSurfaceVariant)
                )
            } else {
                notes.forEach { note ->
                    WidgetNoteRow(
                        note = note,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        surfaceVariant = surfaceVariant
                    )
                    Spacer(modifier = GlanceModifier.height(6.dp))
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetNoteRow(
        note: WidgetNote,
        onSurface: ColorProvider,
        onSurfaceVariant: ColorProvider,
        surfaceVariant: ColorProvider
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(12.dp)
                .background(surfaceVariant)
                .padding(10.dp)
                .clickable(
                    if (note.isLocked) {
                        actionStartActivity<MainActivity>()
                    } else {
                        actionStartActivity<MainActivity>(
                            actionParametersOf(NoteIdKey to note.id)
                        )
                    }
                )
        ) {
            Text(
                text = when {
                    note.isLocked -> "Locked note"
                    note.title.isNotBlank() -> note.title
                    else -> "Untitled"
                },
                style = TextStyle(
                    fontWeight = FontWeight.Medium,
                    color = onSurface,
                    fontSize = 14.sp
                ),
                maxLines = 1
            )
            if (!note.isLocked && note.preview.isNotBlank()) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = note.preview,
                    style = TextStyle(
                        color = onSurfaceVariant,
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
