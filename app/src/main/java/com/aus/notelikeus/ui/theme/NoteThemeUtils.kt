package com.aus.notelikeus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance

@Composable
fun isNoteColorDarkTheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.2f
