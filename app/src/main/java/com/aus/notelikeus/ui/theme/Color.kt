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

// Vibrant Muted Note Palette (Modern & Visible)
// Refactored to provide better color identification while maintaining dark mode safety
val NoteRedDark = Color(0xFF421A1A)
val NoteOrangeDark = Color(0xFF422B18)
val NoteYellowDark = Color(0xFF423C18)
val NoteGreenDark = Color(0xFF1A421A)
val NoteTealDark = Color(0xFF18423F)
val NoteBlueDark = Color(0xFF182B42)
val NoteDarkBlueDark = Color(0xFF1E2242)
val NotePurpleDark = Color(0xFF2B1A42)
val NotePinkDark = Color(0xFF421A33)
val NoteBrownDark = Color(0xFF33261A)
val NoteGrayDark = Color(0xFF262626)

// Modern Light Palette (Clean & Saturated)
val NoteRedLight = Color(0xFFFFB2B2)
val NoteOrangeLight = Color(0xFFFFD580)
val NoteYellowLight = Color(0xFFFFF780)
val NoteGreenLight = Color(0xFFB2FFB2)
val NoteTealLight = Color(0xFFB2FFF0)
val NoteBlueLight = Color(0xFFB2D8FF)
val NoteDarkBlueLight = Color(0xFFB2BEFF)
val NotePurpleLight = Color(0xFFD8B2FF)
val NotePinkLight = Color(0xFFFFB2E0)
val NoteBrownLight = Color(0xFFE0C4A8)
val NoteGrayLight = Color(0xFFEBEBEB)

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
    NoteColorOption(NoteDarkBlueLight, NoteDarkBlueDark),
    NoteColorOption(NotePurpleLight, NotePurpleDark),
    NoteColorOption(NotePinkLight, NotePinkDark),
    NoteColorOption(NoteBrownLight, NoteBrownDark),
    NoteColorOption(NoteGrayLight, NoteGrayDark)
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
