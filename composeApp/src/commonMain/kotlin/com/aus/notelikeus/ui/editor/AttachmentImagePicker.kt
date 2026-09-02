package com.aus.notelikeus.ui.editor

import androidx.compose.runtime.Composable

@Composable
expect fun rememberAttachmentImagePicker(
    enabled: Boolean,
    onPicked: (bytes: ByteArray, mimeType: String) -> Unit,
): () -> Unit
