package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier

/** No wheel-to-horizontal mapping on Android — touch drag is the primary input. */
actual fun Modifier.wheelHorizontalScroll(state: ScrollState): Modifier = this
