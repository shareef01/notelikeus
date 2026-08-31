package com.aus.notelikeus.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual class PlatformShare {
    actual fun shareText(title: String?, text: String) {
        val stringSelection = StringSelection(text)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(stringSelection, null)
    }
}

@Composable
actual fun rememberPlatformShare(): PlatformShare {
    return remember { PlatformShare() }
}