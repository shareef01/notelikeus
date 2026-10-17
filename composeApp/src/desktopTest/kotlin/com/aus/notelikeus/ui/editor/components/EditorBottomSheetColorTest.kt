package com.aus.notelikeus.ui.editor.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.NoteYellowDark
import com.aus.notelikeus.ui.theme.NoteYellowLight
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test

/**
 * The options sheet marks the note's colour whichever half of the light/dark pair it stored. The
 * row shows the active theme's half, and the comparison used to be raw ARGB, so in dark themes a
 * note coloured in light mode (the usual case) showed no selected colour at all.
 */
@OptIn(ExperimentalTestApi::class)
class EditorBottomSheetColorTest {

    private fun showSheet(dark: Boolean, storedColor: Int) = runComposeUiTest {
        setContent {
            NotelikeusTheme(
                preference = ThemePreference(base = if (dark) ThemeBase.DARK else ThemeBase.LIGHT),
                darkTheme = dark
            ) {
                EditorBottomSheet(
                    selectedColor = storedColor,
                    onColorSelect = {},
                    allLabels = emptyList(),
                    selectedLabels = emptyList(),
                    onLabelToggle = {},
                    onCreateLabel = {},
                    onDeleteNote = {},
                    onShareNote = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithContentDescription("Yellow", useUnmergedTree = true).assertIsSelected()
        onNodeWithContentDescription("Blue", useUnmergedTree = true).assertIsNotSelected()
    }

    @Test
    fun `dark theme marks a colour stored as its light value`() =
        showSheet(dark = true, storedColor = NoteYellowLight.toArgb())

    @Test
    fun `light theme marks a colour stored as its dark value`() =
        showSheet(dark = false, storedColor = NoteYellowDark.toArgb())

    @Test
    fun `each theme marks a colour stored as its own value`() {
        showSheet(dark = false, storedColor = NoteYellowLight.toArgb())
        showSheet(dark = true, storedColor = NoteYellowDark.toArgb())
    }
}
