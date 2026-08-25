package com.aus.notelikeus.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Theme tokens aligned with web `globals.css` / ThemePicker.
 */

// Light
val PrimaryLight = Color(0xFF1A1A1A)
val SecondaryLight = Color(0xFF4A4A4A)
val MutedLight = Color(0xFF717171)
val BackgroundLight = Color(0xFFF0F0F0) // Slightly darker base
val SurfaceLight = Color(0xFFFFFFFF)    // Pure white for cards/editor
val SurfaceVariantLight = Color(0xFFE5E5E5)
val OutlineLight = Color(0xFFD1D1D1)

// Dark (Deep Charcoal)
val PrimaryDark = Color(0xFFE8E8E8)
val SecondaryDark = Color(0xFFB0B0B0)
val MutedDark = Color(0xFF888888)
val BackgroundDark = Color(0xFF0F0F0F) // Deep base
val SurfaceDark = Color(0xFF1A1A1A)    // Elevated surface
val SurfaceVariantDark = Color(0xFF262626)
val OutlineDark = Color(0xFF333333)

// True dark / OLED
val PrimaryTrueDark = Color(0xFFFFFFFF)
val SecondaryTrueDark = Color(0xFFA8A8A8)
val MutedTrueDark = Color(0xFF999999)
val BackgroundTrueDark = Color(0xFF000000)
val SurfaceTrueDark = Color(0xFF000000)
val SurfaceVariantTrueDark = Color(0xFF121212)
val OutlineTrueDark = Color(0xFF262626)

// Midnight
val PrimaryMidnight = Color(0xFFDCE8FF)
val SecondaryMidnight = Color(0xFF9AACCC)
val MutedMidnight = Color(0xFF8A9CB8)
val BackgroundMidnight = Color(0xFF070A12)
val SurfaceMidnight = Color(0xFF0C111C)
val SurfaceVariantMidnight = Color(0xFF141B2A)
val OutlineMidnight = Color(0xFF283448)

// Forest
val PrimaryForest = Color(0xFFD6F0D6)
val SecondaryForest = Color(0xFF9AB89E)
val MutedForest = Color(0xFF8AA48E)
val BackgroundForest = Color(0xFF090E0A)
val SurfaceForest = Color(0xFF0F1610)
val SurfaceVariantForest = Color(0xFF182119)
val OutlineForest = Color(0xFF2A382C)

/**
 * Accent primaries for the light theme.
 *
 * The dark themes already had their accents — Midnight's and Forest's tinted `primary` — but
 * light had only the neutral near-black, so "green accent on light" had no colour to use. Both
 * clear 4.5:1 against `SurfaceLight` (#FFFFFF), which is what `primary` is read against when it
 * tints text and icons: blue 6.7:1, green 6.4:1.
 */
val AccentBlueLight = Color(0xFF0B57D0)
val AccentGreenLight = Color(0xFF1B6B2E)

// The per-destination drawer accents (sky/amber/rose/violet/teal) are gone: five saturated hues
// in one column read as unrelated icon sets and left selection with no colour of its own.
// SideDrawerNavItem derives its tint from selection state against the theme's primary.
//
// This comment previously said the same thing while NavIdentity was still defined below it and
// still used by MainDrawerContent. It is true now.
val SignOutRose = Color(0xFFFB7185)
val SignOutRoseContainer = Color(0x26FB7185) // ~15%

// 8 solid Material-inspired note colors (light containers / rich dark surfaces)
val NoteRedDark = Color(0xFF6D2B2B)
val NoteOrangeDark = Color(0xFF6B4520)
val NoteYellowDark = Color(0xFF6B5C18)
val NoteGreenDark = Color(0xFF2E5A32)
val NoteTealDark = Color(0xFF1E5650)
val NoteBlueDark = Color(0xFF2A4A6E)
val NotePurpleDark = Color(0xFF4A2D62)
val NotePinkDark = Color(0xFF6B2D48)

val NoteRedLight = Color(0xFFFFCDD2)
val NoteOrangeLight = Color(0xFFFFE0B2)
val NoteYellowLight = Color(0xFFFFF59D)
val NoteGreenLight = Color(0xFFC8E6C9)
val NoteTealLight = Color(0xFFB2DFDB)
val NoteBlueLight = Color(0xFFBBDEFB)
val NotePurpleLight = Color(0xFFE1BEE7)
val NotePinkLight = Color(0xFFF8BBD0)

/** Near-black note foreground. Not pure black: softer against pastel note colours. */
val NoteContentDark = Color(0xFF121212)

/**
 * WCAG contrast ratio between two relative luminances.
 *
 * `Color.luminance()` already returns WCAG relative luminance (it applies the sRGB transfer
 * function), so these compose directly into the standard (L+0.05) ratio.
 */
private fun contrastRatio(a: Float, b: Float): Float {
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Picks whichever foreground actually reads better on this background, by measuring both.
 *
 * This used to be `luminance() > 0.45f`, which is not where the two candidates cross over: white
 * and [NoteContentDark] tie at a luminance of about **0.19**, so every background between 0.19 and
 * 0.45 was given white text when near-black was the more legible choice — and the gap is not
 * cosmetic. A mid-tone background at 0.40 reads at about 2.3:1 in white (below WCAG AA's 4.5:1)
 * against roughly 8:1 in near-black.
 *
 * The nine built-in note colours are polarised — the dark set sits at or below 0.11, the light set
 * at or above 0.58 — so none of them fell in that band and none of them change appearance here.
 * What reaches it is arbitrary colour: `color` is a plain ARGB int in the backup format and in the
 * Firestore document, so an imported note, or one written by a future client with a different
 * palette, can carry any value at all. Measuring rather than thresholding means those get a
 * readable foreground instead of an accidental one.
 */
fun Color.getContentColor(fallback: Color = Color.White): Color {
    if (this == Color.Transparent) return fallback
    val background = luminance()
    val whiteContrast = contrastRatio(background, 1f)
    val darkContrast = contrastRatio(background, NoteContentDark.luminance())
    return if (whiteContrast >= darkContrast) Color.White else NoteContentDark
}

data class NoteColorOption(val light: Color, val dark: Color)

val NOTE_COLOR_OPTIONS: List<NoteColorOption> = listOf(
    NoteColorOption(Color.Transparent, Color.Transparent),
    NoteColorOption(NoteRedLight, NoteRedDark),
    NoteColorOption(NoteOrangeLight, NoteOrangeDark),
    NoteColorOption(NoteYellowLight, NoteYellowDark),
    NoteColorOption(NoteGreenLight, NoteGreenDark),
    NoteColorOption(NoteTealLight, NoteTealDark),
    NoteColorOption(NoteBlueLight, NoteBlueDark),
    NoteColorOption(NotePurpleLight, NotePurpleDark),
    NoteColorOption(NotePinkLight, NotePinkDark),
)

/**
 * Keywords for the `color:` search operator, in [NOTE_COLOR_OPTIONS] order.
 *
 * Deliberately not the localised names in `NoteColorNames.kt`. Those are what a screen reader
 * speaks; these are what someone types, and a query language whose keywords move when the device
 * language changes is a query language nobody can write down or share.
 */
val NOTE_COLOR_KEYWORDS: List<String> = listOf(
    "none", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink"
)

/** The stored ARGB for a `color:` keyword on the given theme, or null if it is not a colour. */
fun noteColorForKeyword(keyword: String, isDarkTheme: Boolean): Int? {
    val index = NOTE_COLOR_KEYWORDS.indexOf(keyword.lowercase())
    if (index < 0) return null
    val option = NOTE_COLOR_OPTIONS[index]
    return (if (isDarkTheme) option.dark else option.light).toArgb()
}

/**
 * The palette index a colour occupies, or -1 when it is not one of the built-in colours.
 * Matches on either the light or dark variant, since which one is on screen depends on the theme.
 */
fun noteColorPaletteIndex(color: Color): Int =
    NOTE_COLOR_OPTIONS.indexOfFirst { it.light == color || it.dark == color }

fun noteColorsForTheme(isDarkTheme: Boolean): List<Color> =
    NOTE_COLOR_OPTIONS.map { if (isDarkTheme) it.dark else it.light }

/** Display palette: map stored ARGB to the light/dark pair for the active theme. */
/** A note with no colour of its own; renders as the active theme's surface. */
const val NO_NOTE_COLOR: Int = 0

/**
 * Colours that older builds wrote as a note's "default", by storing whatever the active theme's
 * background happened to be. Neither is in [NOTE_COLOR_OPTIONS], so neither can be a colour the
 * user actually picked — the palette offers "no colour" plus eight pastels and nothing else.
 *
 * They are mapped back to [NO_NOTE_COLOR] on read rather than migrated in the database: the notes
 * are already synced, so a migration would have to run on every client and race the others, while
 * this fixes them everywhere at once and leaves the stored data untouched.
 */
private val LEGACY_THEME_DEFAULT_COLORS = setOf(
    0xFFF0F0F0.toInt(), // BackgroundLight, written on every theme except OLED
    0xFF000000.toInt(), // black, written on OLED
)

fun noteColorForTheme(argb: Int, isDarkTheme: Boolean): Int {
    if (argb == NO_NOTE_COLOR) return NO_NOTE_COLOR
    if (argb in LEGACY_THEME_DEFAULT_COLORS) return NO_NOTE_COLOR
    NOTE_COLOR_OPTIONS.forEach { option ->
        val lightArgb = option.light.toArgb()
        val darkArgb = option.dark.toArgb()
        if (argb == lightArgb || argb == darkArgb) {
            return if (isDarkTheme) darkArgb else lightArgb
        }
    }
    return argb
}

fun noteColorCounterpart(argb: Int): Int? {
    if (argb == 0) return 0
    NOTE_COLOR_OPTIONS.forEach { option ->
        val lightArgb = option.light.toArgb()
        val darkArgb = option.dark.toArgb()
        if (argb == lightArgb) return darkArgb
        if (argb == darkArgb) return lightArgb
    }
    return null
}

fun noteColorsMatch(noteArgb: Int, filterArgb: Int): Boolean =
    noteArgb == filterArgb || noteColorCounterpart(noteArgb) == filterArgb

val SwipeArchiveLight = Color(0xFF2A2A2A)
val SwipeArchiveDark = Color(0xFFE8E8E8)
val SwipeDeleteLight = Color(0xFF5C2B2B)
val SwipeDeleteDark = Color(0xFF8B4545)
