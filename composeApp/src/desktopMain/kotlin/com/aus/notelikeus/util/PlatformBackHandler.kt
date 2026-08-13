package com.aus.notelikeus.util

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop doesn't have a standard hardware back button.
    // Back handling is typically managed by UI components or keyboard shortcuts.
}
