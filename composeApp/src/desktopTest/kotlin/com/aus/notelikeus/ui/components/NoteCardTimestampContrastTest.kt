package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.NoteYellowDark
import com.aus.notelikeus.ui.theme.NoteYellowLight
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A card's timestamp is text on the note's colour, so it takes the 0.80 text tier (NoteEmphasis),
 * as web's cards do. It was drawn at 0.55 (list) and 0.60 (grid): below AA on the lighter dark
 * containers. Measured as drawn, at 3x so glyph cores are solid.
 */
@OptIn(ExperimentalTestApi::class)
class NoteCardTimestampContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun timestampContrast(listStyle: Boolean): Float {
        var ratio = 0f
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(3f)) {
                    NotelikeusTheme(preference = ThemePreference(base = ThemeBase.DARK), darkTheme = true) {
                        NoteCard(
                            note = Note(
                                id = 1L,
                                title = "Groceries",
                                content = "",
                                timestamp = 1_791_990_000_000L,
                                color = NoteYellowLight.toArgb(),
                            ),
                            isSelected = false,
                            listStyle = listStyle,
                            onClick = {},
                            onLongClick = {},
                            modifier = Modifier.width(if (listStyle) 360.dp else 168.dp),
                        )
                    }
                }
            }
            // The card's only text other than its title is the timestamp.
            val timestamp = onAllNodes(
                SemanticsMatcher("timestamp text") { node ->
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("")
                    text != null && text != "Groceries"
                },
                useUnmergedTree = true,
            )[0].captureToImage().toAwtImage()
            for (x in 0 until timestamp.width) for (y in 0 until timestamp.height) {
                ratio = maxOf(ratio, contrast(Color(timestamp.getRGB(x, y)), NoteYellowDark))
            }
        }
        return ratio
    }

    @Test
    fun `list card timestamp clears AA on a dark amber note`() {
        val ratio = timestampContrast(listStyle = true)
        assertTrue(ratio >= 4.5f, "list timestamp at $ratio")
    }

    @Test
    fun `grid card timestamp clears AA on a dark amber note`() {
        val ratio = timestampContrast(listStyle = false)
        assertTrue(ratio >= 4.5f, "grid timestamp at $ratio")
    }
}
