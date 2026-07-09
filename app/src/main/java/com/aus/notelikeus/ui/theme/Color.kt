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
val BackgroundDark = Color(0xFF000000) // OLED Absolute Black
val SurfaceDark = Color(0xFF121212)

// Premium Desaturated Dark-Mode Palette
// Muted tones designed for dark mode to prevent eye strain
val NoteRedDark = Color(0xFF2D1616)
val NoteOrangeDark = Color(0xFF2D2014)
val NoteYellowDark = Color(0xFF2D2B14)
val NoteGreenDark = Color(0xFF162D16)
val NoteTealDark = Color(0xFF142D2B)
val NoteBlueDark = Color(0xFF141F2D)
val NoteDarkBlueDark = Color(0xFF181C2D)
val NotePurpleDark = Color(0xFF20162D)
val NotePinkDark = Color(0xFF2D1624)
val NoteBrownDark = Color(0xFF211B14)
val NoteGrayDark = Color(0xFF1A1A1A)

// Muted Light-Mode Palette
val NoteRedLight = Color(0xFFFFDADA)
val NoteOrangeLight = Color(0xFFFFE5C0)
val NoteYellowLight = Color(0xFFFFF9C0)
val NoteGreenLight = Color(0xFFD4FFD4)
val NoteTealLight = Color(0xFFD4FFF9)
val NoteBlueLight = Color(0xFFD4E8FF)
val NoteDarkBlueLight = Color(0xFFD4DCFF)
val NotePurpleLight = Color(0xFFE8D4FF)
val NotePinkLight = Color(0xFFFFD4EC)
val NoteBrownLight = Color(0xFFE8DAC0)
val NoteGrayLight = Color(0xFFEEEEEE)

/**
 * Dynamic Text Contrast Utility
 * Enforces strict WCAG contrast readability across all note color variations.
 * Light backgrounds -> Dark Gray (#121212)
 * Dark backgrounds -> Pure White (#FFFFFF)
 */
fun Color.getContentColor(): Color {
    return if (this.luminance() > 0.45f) Color(0xFF121212) else Color.White
}

data class NoteColorOption(val light: Color, val dark: Color)

val NOTE_COLOR_OPTIONS: List<NoteColorOption> = listOf(
    NoteColorOption(BackgroundLight, Color(0xFF121212)),
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
