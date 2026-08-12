package com.aus.notelikeus.ui.theme

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

// Nav accents (match web SideDrawer: sky / amber / rose / violet / teal)
// Used by the collapsed rail for per-destination icon accents.
val Sky = Color(0xFF38BDF8)
val Amber = Color(0xFFFBBF24)
val Rose = Color(0xFFFB7185)
val Violet = Color(0xFFA78BFA)
val Teal = Color(0xFF2DD4BF)
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

/**
 * Dynamic Text Contrast Utility
 * Light backgrounds -> Dark Gray (#121212)
 * Dark backgrounds -> Pure White (#FFFFFF)
 */
fun Color.getContentColor(fallback: Color = Color.White): Color {
    if (this == Color.Transparent) return fallback
    return if (this.luminance() > 0.45f) Color(0xFF121212) else Color.White
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

fun noteColorsForTheme(isDarkTheme: Boolean): List<Color> =
    NOTE_COLOR_OPTIONS.map { if (isDarkTheme) it.dark else it.light }

/** Display palette: map stored ARGB to the light/dark pair for the active theme. */
fun noteColorForTheme(argb: Int, isDarkTheme: Boolean): Int {
    if (argb == 0) return 0
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
