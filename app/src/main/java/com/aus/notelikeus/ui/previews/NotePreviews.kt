package com.aus.notelikeus.ui.previews

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.components.NoteStaggeredGrid
import com.aus.notelikeus.ui.theme.*

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun NoteGridPreviewLight() {
    NotelikeusTheme(darkTheme = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            NoteStaggeredGrid(
                notes = mockNotes(isDark = false),
                selectedNotes = emptySet(),
                onNoteClick = {},
                onNoteLongClick = {},
                onSwipeToArchive = {},
                onSwipeToTrash = {},
                onMoveNote = { _, _ -> },
                searchQuery = ""
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark Mode")
@Composable
fun NoteGridPreviewDark() {
    NotelikeusTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            NoteStaggeredGrid(
                notes = mockNotes(isDark = true),
                selectedNotes = emptySet(),
                onNoteClick = {},
                onNoteLongClick = {},
                onSwipeToArchive = {},
                onSwipeToTrash = {},
                onMoveNote = { _, _ -> },
                searchQuery = ""
            )
        }
    }
}

fun mockNotes(isDark: Boolean): List<Note> {
    return listOf(
        Note(
            id = 1,
            title = "Project Ideas",
            content = "1. Note taking app\n2. Habit tracker\n3. Budget manager",
            timestamp = System.currentTimeMillis(),
            color = if (isDark) NoteBlueDark.toArgb() else NoteBlueLight.toArgb()
        ),
        Note(
            id = 2,
            title = "Shopping List",
            content = "Milk, Eggs, Bread, Coffee, Fruits",
            timestamp = System.currentTimeMillis(),
            color = if (isDark) NoteGreenDark.toArgb() else NoteGreenLight.toArgb()
        ),
        Note(
            id = 3,
            title = "",
            content = "Minimalism is not a lack of something. It's simply the perfect amount of something.",
            timestamp = System.currentTimeMillis(),
            color = if (isDark) NoteRedDark.toArgb() else NoteRedLight.toArgb()
        ),
        Note(
            id = 4,
            title = "Meeting Notes",
            content = "Discuss architecture and UI design for the new app.",
            timestamp = System.currentTimeMillis(),
            color = if (isDark) NotePurpleDark.toArgb() else NotePurpleLight.toArgb()
        ),
        Note(
            id = 5,
            title = "Reminder",
            content = "Call Mom at 5 PM",
            timestamp = System.currentTimeMillis(),
            color = if (isDark) NoteYellowDark.toArgb() else NoteYellowLight.toArgb()
        )
    )
}
