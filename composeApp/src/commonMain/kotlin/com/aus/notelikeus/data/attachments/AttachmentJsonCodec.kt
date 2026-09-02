package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.domain.model.Attachment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val attachmentJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeAttachments(attachments: List<Attachment>): String? {
    if (attachments.isEmpty()) return null
    return attachmentJson.encodeToString(ListSerializer(Attachment.serializer()), attachments)
}

fun decodeAttachments(raw: String?): List<Attachment> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        attachmentJson.decodeFromString(ListSerializer(Attachment.serializer()), raw)
    }.getOrDefault(emptyList())
}
