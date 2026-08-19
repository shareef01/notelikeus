package com.aus.notelikeus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.aus.notelikeus.domain.model.AppTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every readable thing the app draws on a note, on every container, in every theme, held to WCAG.
 *
 * This is the test the brief asked for, and it found a real failure rather than confirming a
 * healthy palette. The containers were fine — at full opacity the worst pairing is 6.63:1. What
 * was failing is everything drawn at reduced opacity on top of them: at the 0.55 alpha the card
 * used for timestamps and chip labels, 16 of the 21 containers fell below 4.5:1, the worst at
 * 3.24:1. [NoteEmphasis] exists to fix that, and this test is what stops it regressing.
 *
 * The sweep is over *composited* colour, not the token's nominal colour. Asserting that
 * `onContainer` alone clears AA would pass while the UI failed, because nothing except a title is
 * actually drawn at full opacity.
 */
class NotePaletteContrastTest {

    private companion object {
        /** WCAG AA for body text. */
        const val AA_TEXT = 4.5f

        /** WCAG AA for large text and meaningful non-text graphics. */
        const val AA_GRAPHIC = 3.0f
    }

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** What the user actually sees when [this] is drawn at [alpha] over [container]. */
    private fun Color.at(alpha: Float, container: Color): Color =
        copy(alpha = alpha).compositeOver(container)

    /**
     * Every surface a note can be painted: the sixteen palette containers, plus the plain surface
     * of each theme for a note with no colour of its own.
     *
     * The theme surfaces come from [colorSchemeFor] rather than from a list maintained here, so a
     * new theme is covered the moment it exists instead of when someone remembers to add it.
     */
    private val containers: List<Pair<String, NoteColorRole>> = buildList {
        NOTE_COLOR_OPTIONS.forEachIndexed { index, option ->
            if (option.light != Color.Transparent) {
                add("palette[$index].light" to noteColorRole(option.light))
                add("palette[$index].dark" to noteColorRole(option.dark))
            }
        }
        AppTheme.entries.forEach { theme ->
            listOf(true, false).forEach { systemDark ->
                val scheme = colorSchemeFor(theme, systemDark)
                val label = if (theme == AppTheme.AUTO) "$theme(systemDark=$systemDark)" else "$theme"
                add(
                    "no-colour on $label" to
                        NoteColorRole(container = scheme.surface, onContainer = scheme.onSurface)
                )
            }
        }
    }

    @Test
    fun `full-opacity content clears AA on every container`() {
        containers.forEach { (name, role) ->
            val ratio = contrast(role.onContainer.at(NoteEmphasis.Full, role.container), role.container)
            assertTrue(ratio >= AA_TEXT, "$name: title text at ${ratio}:1 is below $AA_TEXT:1")
        }
    }

    @Test
    fun `secondary text clears AA on every container`() {
        containers.forEach { (name, role) ->
            val ratio = contrast(role.onContainerSecondary.compositeOver(role.container), role.container)
            assertTrue(
                ratio >= AA_TEXT,
                "$name: secondary text at ${ratio}:1 is below $AA_TEXT:1 — " +
                    "NoteEmphasis.Secondary is too transparent for this container"
            )
        }
    }

    @Test
    fun `meaningful icons clear the 3 to 1 graphics threshold on every container`() {
        containers.forEach { (name, role) ->
            val ratio = contrast(role.onContainerIcon.compositeOver(role.container), role.container)
            assertTrue(
                ratio >= AA_GRAPHIC,
                "$name: icon at ${ratio}:1 is below $AA_GRAPHIC:1"
            )
        }
    }

    /**
     * Pins the reason a third text tier does not exist.
     *
     * If someone later adds `Tertiary = 0.6f` because the design wants a quieter timestamp, the
     * failure should say why it cannot work rather than showing up as an accessibility bug
     * months later. 0.75 is the solved minimum across the palette; anything below it fails on
     * the lightest dark container.
     */
    @Test
    fun `no text tier sits below the solved minimum opacity`() {
        val solvedMinimum = 0.75f
        listOf("Full" to NoteEmphasis.Full, "Secondary" to NoteEmphasis.Secondary)
            .forEach { (name, alpha) ->
                assertTrue(
                    alpha >= solvedMinimum,
                    "NoteEmphasis.$name is $alpha, below the solved minimum $solvedMinimum " +
                        "needed for 4.5:1 on the lightest dark container"
                )
            }
    }

    /**
     * The decorative tier is not a text tier, and the gap between them is what makes that true.
     * Kept as an assertion so nobody quietly raises it until it looks legible and starts getting
     * used for text.
     */
    @Test
    fun `the decorative tier stays far below anything readable`() {
        assertTrue(
            NoteEmphasis.Decorative < 0.3f,
            "NoteEmphasis.Decorative is ${NoteEmphasis.Decorative}; at that opacity it reads as " +
                "text and will be used as text"
        )
    }
}
