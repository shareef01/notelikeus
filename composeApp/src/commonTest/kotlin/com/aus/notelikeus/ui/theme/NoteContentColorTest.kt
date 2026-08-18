package com.aus.notelikeus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [getContentColor] has to stay a *measurement*, not a threshold. A luminance cutoff looks right
 * on the built-in palette (which is polarised, so almost any cutoff works) and quietly fails on
 * the arbitrary colours that reach the app through backup import and cloud documents.
 */
class NoteContentColorTest {

    private fun contrast(background: Color, foreground: Color): Float {
        val lighter = maxOf(background.luminance(), foreground.luminance())
        val darker = minOf(background.luminance(), foreground.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private val builtInNoteColors = NOTE_COLOR_OPTIONS
        .flatMap { listOf(it.light, it.dark) }
        .filter { it != Color.Transparent }

    @Test
    fun `every built-in note colour clears WCAG AA`() {
        builtInNoteColors.forEach { background ->
            val ratio = contrast(background, background.getContentColor())
            assertTrue(
                ratio >= 4.5f,
                "contrast ${ratio} on $background is below AA"
            )
        }
    }

    @Test
    fun `the chosen foreground is always the better of the two candidates`() {
        // Sweep the whole luminance range rather than only the palette: this is the property the
        // old 0.45 threshold broke, and only away from the palette does it show.
        for (step in 0..255) {
            val grey = Color(red = step / 255f, green = step / 255f, blue = step / 255f)
            val chosen = grey.getContentColor()
            val rejected = if (chosen == Color.White) NoteContentDark else Color.White
            assertTrue(
                contrast(grey, chosen) >= contrast(grey, rejected),
                "grey $step chose the worse foreground"
            )
        }
    }

    @Test
    fun `a mid-tone background gets near-black, which the old threshold got wrong`() {
        // Luminance lands around 0.40 — inside the 0.19..0.45 band the old rule mishandled.
        val midTone = Color(0xFFAFAFAF)
        assertEquals(NoteContentDark, midTone.getContentColor())
        assertTrue(contrast(midTone, midTone.getContentColor()) >= 4.5f)
    }

    /**
     * `noteColorName` maps a palette *index* to a spoken name, so reordering NOTE_COLOR_OPTIONS
     * would silently rename every colour for screen-reader users without breaking a build. This
     * pins the order that mapping assumes.
     */
    @Test
    fun `palette order matches the names accessibility reads out`() {
        val expected = listOf(
            0 to Color.Transparent,
            1 to NoteRedLight,
            2 to NoteOrangeLight,
            3 to NoteYellowLight,
            4 to NoteGreenLight,
            5 to NoteTealLight,
            6 to NoteBlueLight,
            7 to NotePurpleLight,
            8 to NotePinkLight,
        )
        assertEquals(expected.size, NOTE_COLOR_OPTIONS.size)
        expected.forEach { (index, color) ->
            assertEquals(index, noteColorPaletteIndex(color), "light variant at index $index")
        }
        // The dark variants have to resolve to the same index: which one is on screen depends on
        // the active theme, and the name must not change with it.
        listOf(
            1 to NoteRedDark, 2 to NoteOrangeDark, 3 to NoteYellowDark, 4 to NoteGreenDark,
            5 to NoteTealDark, 6 to NoteBlueDark, 7 to NotePurpleDark, 8 to NotePinkDark,
        ).forEach { (index, color) ->
            assertEquals(index, noteColorPaletteIndex(color), "dark variant at index $index")
        }
    }

    @Test
    fun `a colour outside the palette has no index`() {
        // Imported notes carry arbitrary ARGB; naming one would be guesswork.
        assertEquals(-1, noteColorPaletteIndex(Color(0xFF123456)))
    }

    @Test
    fun `transparent falls back to the caller's colour`() {
        assertEquals(Color.Cyan, Color.Transparent.getContentColor(fallback = Color.Cyan))
    }
}
