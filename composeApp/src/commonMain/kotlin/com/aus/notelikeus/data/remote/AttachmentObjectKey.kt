package com.aus.notelikeus.data.remote

private val NOTE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
private val ATTACHMENT_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")

object AttachmentObjectKey {
    fun build(ownerId: String, noteId: String, attachmentId: String): String {
        val trimmedNoteId = noteId.trim()
        val trimmedAttachmentId = attachmentId.trim()
        require(NOTE_ID_PATTERN.matches(trimmedNoteId) && ATTACHMENT_ID_PATTERN.matches(trimmedAttachmentId)) {
            "invalid attachment path segment"
        }
        return "owners/$ownerId/notes/$trimmedNoteId/$trimmedAttachmentId"
    }

    fun workerPath(noteId: String, attachmentId: String): String =
        "/v1/attachments/${encode(noteId.trim())}/${encode(attachmentId.trim())}"

    fun isForOwner(objectKey: String, ownerId: String): Boolean {
        val prefix = "owners/$ownerId/notes/"
        if (!objectKey.startsWith(prefix)) return false
        val rest = objectKey.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.lastIndex) return false
        val noteId = rest.substring(0, slash)
        val attachmentId = rest.substring(slash + 1)
        return NOTE_ID_PATTERN.matches(noteId) && ATTACHMENT_ID_PATTERN.matches(attachmentId)
    }

    private fun encode(value: String): String = buildString(value.length) {
        for (char in value) {
            when {
                char.isLetterOrDigit() || char == '.' || char == '_' || char == '-' -> append(char)
                else -> {
                    val hex = char.code.toString(16).uppercase()
                    append('%')
                    append(hex.padStart(2, '0'))
                }
            }
        }
    }
}
