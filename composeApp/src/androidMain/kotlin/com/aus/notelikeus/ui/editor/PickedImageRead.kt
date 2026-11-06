package com.aus.notelikeus.ui.editor

import android.content.ContentResolver
import android.net.Uri
import com.aus.notelikeus.data.attachments.readBoundedAttachment

/** A picker grant can expire or its provider disappear before the callback reads the image. */
internal fun readPickedImage(resolver: ContentResolver, uri: Uri): Pair<ByteArray, String>? {
    return try {
        val mimeType = resolver.getType(uri) ?: "image/jpeg"
        if (!mimeType.startsWith("image/")) return null
        val bytes = resolver.openInputStream(uri)?.use { readBoundedAttachment(it) } ?: return null
        bytes to mimeType
    } catch (_: Exception) {
        null
    }
}
