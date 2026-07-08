package com.aus.notelikeus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Premium Minimalist Palette
val PrimaryLight = Color(0xFF1A73E8) // Google Blue
val SecondaryLight = Color(0xFF5F6368)
val BackgroundLight = Color(0xFFFFFFFF)
val SurfaceLight = Color(0xFFF1F3F4)

val PrimaryDark = Color(0xFF8AB4F8)
val SecondaryDark = Color(0xFF9AA0A6)
val BackgroundDark = Color(0xFF202124)
val SurfaceDark = Color(0xFF2D2E31)

// Note Background Colors (Light Mode)
val NoteRedLight = Color(0xFFF28B82)
val NoteOrangeLight = Color(0xFFFBBC04)
val NoteYellowLight = Color(0xFFFFF475)
val NoteGreenLight = Color(0xFFCCFF90)
val NoteTealLight = Color(0xFFA7FFEB)
val NoteBlueLight = Color(0xFFCBF0F8)
val NoteDarkBlueLight = Color(0xFFAECBFA)
val NotePurpleLight = Color(0xFFD7AEFB)
val NotePinkLight = Color(0xFFFDCFE8)
val NoteBrownLight = Color(0xFFE6C9A8)
val NoteGrayLight = Color(0xFFE8EAED)

// Note Background Colors (Dark Mode - Desaturated for OLED contrast)
val NoteRedDark = Color(0xFF4A2B2B)
val NoteOrangeDark = Color(0xFF4B3621)
val NoteYellowDark = Color(0xFF4B451A)
val NoteGreenDark = Color(0xFF2E3D23)
val NoteTealDark = Color(0xFF233D3A)
val NoteBlueDark = Color(0xFF23353D)
val NoteDarkBlueDark = Color(0xFF2B2E4A)
val NotePurpleDark = Color(0xFF3B2B4A)
val NotePinkDark = Color(0xFF4A2B3E)
val NoteBrownDark = Color(0xFF3D2E23)
val NoteGrayDark = Color(0xFF2A2A2A)

fun Color.getContentColor(): Color {
    // Advanced contrast algorithm using luminance
    return if (this.luminance() > 0.45f) Color(0xFF1F1F1F) else Color.White
}
