package com.aus.notelikeus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * System Role & Objective: Elite UI/UX Architecture
 * Constraint: OLED Absolute Black Background for Dark Mode
 */

// Core Palette
val PrimaryLight = Color(0xFF000000)
val SecondaryLight = Color(0xFF5C5C5C)
val BackgroundLight = Color(0xFFF7F7F7)
val SurfaceLight = Color(0xFFFFFFFF)

val PrimaryDark = Color(0xFFFFFFFF)
val SecondaryDark = Color(0xFFA8A8A8)
val BackgroundDark = Color(0xFF121212) // Modern Dark (material default)
val SurfaceDark = Color(0xFF1E1E1E)

// New Theme Bases
val BackgroundMidnight = Color(0xFF080C14)
val SurfaceMidnight = Color(0xFF0D121D)
val PrimaryMidnight = Color(0xFFD4E4FF)

val BackgroundForest = Color(0xFF0A0F0A)
val SurfaceForest = Color(0xFF121812)
val PrimaryForest = Color(0xFFD4FFD4)

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
 * Enforces strict WCAG contrast readability across all note color variations.
 * Light backgrounds -> Dark Gray (#121212)
 * Dark backgrounds -> Pure White (#FFFFFF)
 */
fun Color.getContentColor(fallback: Color = Color.White): Color {
    if (this == Color.Transparent) return fallback
    return if (this.luminance() > 0.45f) Color(0xFF121212) else Color.White
}

data class NoteColorOption(val light: Color, val dark: Color)

val NOTE_COLOR_OPTIONS: List<NoteColorOption> = listOf(
    NoteColorOption(Color.Transparent, Color.Transparent), // Use theme default
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
