package com.aus.notelikeus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Masterpiece UI/UX Architecture
 * Constraint: OLED Absolute Black Background for Dark Mode
 */

// Core Palette
val PrimaryLight = Color(0xFF000000)
val SecondaryLight = Color(0xFF5C5C5C)
val BackgroundLight = Color(0xFFF7F7F7)
val SurfaceLight = Color(0xFFFFFFFF)

val PrimaryDark = Color(0xFFFFFFFF)
val SecondaryDark = Color(0xFFA8A8A8)
val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)

// Theme Bases
val BackgroundMidnight = Color(0xFF000000) // OLED True Black
val SurfaceMidnight = Color(0xFF121212)
val PrimaryMidnight = Color(0xFFD0BCFF) // Elite Purple Accent

val BackgroundForest = Color(0xFF0A0F0A)
val SurfaceForest = Color(0xFF121812)
val PrimaryForest = Color(0xFFD4FFD4)

// Vibrant Muted Note Palette (Dark Mode - balanced for OLED visibility)
val NoteRedDark = Color(0xFF5A3333)
val NoteOrangeDark = Color(0xFF5A4030)
val NoteYellowDark = Color(0xFF555022)
val NoteGreenDark = Color(0xFF364A2A)
val NoteTealDark = Color(0xFF2A4845)
val NoteBlueDark = Color(0xFF2A3E4A)
val NoteDarkBlueDark = Color(0xFF333650)
val NotePurpleDark = Color(0xFF453355)
val NotePinkDark = Color(0xFF553342)
val NoteBrownDark = Color(0xFF4A3830)
val NoteGrayDark = Color(0xFF323232)

// Modern Light Palette — soft pastels with excellent text contrast
val NoteRedLight = Color(0xFFFFD6D2)
val NoteOrangeLight = Color(0xFFFFE0B2)
val NoteYellowLight = Color(0xFFFFF9C4)
val NoteGreenLight = Color(0xFFDCEDC8)
val NoteTealLight = Color(0xFFB2DFDB)
val NoteBlueLight = Color(0xFFBBDEFB)
val NoteDarkBlueLight = Color(0xFFC5CAE9)
val NotePurpleLight = Color(0xFFE1BEE7)
val NotePinkLight = Color(0xFFF8BBD0)
val NoteBrownLight = Color(0xFFD7CCC8)
val NoteGrayLight = Color(0xFFE0E0E0)

/**
 * Dynamic Text Contrast — ensures WCAG AA readability on any note color.
 * Light backgrounds (> 0.5 luminance) → near-black text (#141414)
 * Dark backgrounds (≤ 0.5 luminance) → off-white text (#F0F0F0)
 * Transparent → uses fallback
 */
fun Color.getContentColor(fallback: Color = Color.White): Color {
    if (this == Color.Transparent || this.alpha == 0f) return fallback
    return if (this.luminance() > 0.5f) Color(0xFF141414) else Color(0xFFF0F0F0)
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

val SwipeArchiveLight = Color(0xFFE2E2E2)
val SwipeArchiveDark = Color(0xFF2A2A2A)
val SwipeDeleteLight = Color(0xFFF28B82)
val SwipeDeleteDark = Color(0xFF4A2B2B)
