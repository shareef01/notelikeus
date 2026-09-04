package com.aus.notelikeus.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.aus.notelikeus.domain.model.Attachment

val LocalAttachmentPreviewLoader = staticCompositionLocalOf<suspend (Attachment) -> ByteArray?> {
    { null }
}
