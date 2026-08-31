package com.aus.notelikeus.util

import androidx.compose.runtime.Composable

expect class PlatformShare {
    fun shareText(title: String?, text: String)
}

@Composable
expect fun rememberPlatformShare(): PlatformShare