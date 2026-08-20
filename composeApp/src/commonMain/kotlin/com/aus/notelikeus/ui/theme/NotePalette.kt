package com.aus.notelikeus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Opacity tiers for content drawn on a note.
 *
 * These exist because the alphas they replace were failing WCAG AA, and not marginally. Measuring
 * every de-emphasised value that screen code applied to a note's content colour, against all
 * sixteen built-in containers plus the five no-colour surfaces:
 *
 * ```
 *   alpha 0.55  ->  worst 3.24:1   fails on 16 of 21 containers
 *   alpha 0.60  ->  worst 3.55:1   fails on 12
 *   alpha 0.70  ->  worst 4.20:1   fails on 1  (yellow, dark)
 *   alpha 0.80  ->  worst 4.91:1   passes everywhere
 * ```
 *
 * The containers themselves were never the problem — at full opacity the *worst* pairing in the
 * palette is 6.63:1 and most are above 8:1. What was failing is everything drawn at reduced
 * opacity on top: timestamps, body previews, label chips, checklist text. The solved minimum for
 * 4.5:1 across every container is **0.75**; [Secondary] takes 0.80 for headroom.
 *
 * The consequence worth stating plainly: **on a coloured note there is exactly one legible
 * de-emphasis step for text.** A third, lighter tier cannot exist without dropping below AA, so
 * hierarchy below [Secondary] has to come from size, weight or position instead of opacity.
 *
 * The worst container in every one of these calculations is yellow-on-dark (`NoteYellowDark`,
 * the lightest of the dark set) — it is the one to re-measure first if the palette changes.
 */
object NoteEmphasis {

    /** Titles and body text. */
    const val Full = 1f

    /**
     * Timestamps, previews, chip labels — anything that must still be *read*.
     * Solved minimum for 4.5:1 is 0.75; this carries headroom.
     */
    const val Secondary = 0.80f

    /**
     * Meaningful icons and other non-text graphics, which WCAG holds to 3:1 rather than 4.5:1.
     * Solved minimum is 0.51; this carries headroom. Worst case 3.55:1.
     */
    const val Icon = 0.60f

    /**
     * Chip fills, dividers, hairlines. **Never text, and never an icon that carries meaning** —
     * at this opacity nothing is legible and nothing is meant to be.
     */
    const val Decorative = 0.14f

    /**
     * A surface tint: a panel that should read as part of the note rather than floating over it.
     *
     * Half [Decorative], and separate from it for a reason found by looking at the running app.
     * The retokenisation pass mapped every low alpha to [Decorative] by value, which doubled the
     * editor's formatting toolbar from 0.07 to 0.14 — enough that on the light theme it stopped
     * being a tint and became a grey slab, which is exactly what its own comment said it must not
     * be. A stroke and a filled panel need different opacities to read as equally quiet.
     */
    const val Wash = 0.07f
}

/**
 * A note colour expressed as a tonal role pair, the way Material expresses container colours.
 *
 * [container] is what the card is painted; [onContainer] is the foreground that measurably reads
 * best on it. Everything else is a documented tier off [onContainer], so no screen has to pick an
 * alpha and hope.
 *
 * Deliberately *not* a replacement for [NOTE_COLOR_OPTIONS]. That list defines the ARGB values
 * that get persisted to the database and uploaded to the cloud document, and is the identity of a
 * note's colour across three clients — changing it is a data change. This is a presentation layer
 * over it, computed on the fly, and can be reshaped freely.
 */
@Immutable
data class NoteColorRole(
    val container: Color,
    val onContainer: Color
) {
    /** Text that must still be read, one step down. See [NoteEmphasis.Secondary]. */
    val onContainerSecondary: Color get() = onContainer.copy(alpha = NoteEmphasis.Secondary)

    /** Icons carrying meaning. See [NoteEmphasis.Icon]. */
    val onContainerIcon: Color get() = onContainer.copy(alpha = NoteEmphasis.Icon)

    /** Chip fills, dividers, hairlines. Never text. See [NoteEmphasis.Decorative]. */
    val onContainerDecorative: Color get() = onContainer.copy(alpha = NoteEmphasis.Decorative)
}

/**
 * The role pair for an arbitrary container colour.
 *
 * Pure, so the contrast test can enumerate the whole palette without a composition. The
 * foreground comes from [getContentColor], which measures both candidates rather than
 * thresholding luminance — which matters because `color` is a bare ARGB int in both the backup
 * format and the Firestore document, so a container here can be any value at all.
 */
fun noteColorRole(container: Color): NoteColorRole =
    NoteColorRole(container = container, onContainer = container.getContentColor())

/**
 * The role pair for a stored note colour, resolved against the active theme.
 *
 * Handles the two cases every caller was previously handling inline, identically, in
 * `NoteCard` and `EditorScreen`: [NO_NOTE_COLOR] falls back to the theme's own surface, and a
 * palette colour swaps to its light or dark variant for the current theme.
 */
@Composable
fun rememberNoteColorRole(noteColorArgb: Int): NoteColorRole {
    val isDarkPalette = isNoteColorDarkTheme()
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val displayArgb = noteColorForTheme(noteColorArgb, isDarkPalette)
    return remember(displayArgb, surface, onSurface) {
        if (displayArgb == NO_NOTE_COLOR) {
            NoteColorRole(container = surface, onContainer = onSurface)
        } else {
            noteColorRole(Color(displayArgb.toLong() and 0xffffffffL))
        }
    }
}
