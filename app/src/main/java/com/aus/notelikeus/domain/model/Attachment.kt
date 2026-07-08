package com.aus.notelikeus.domain.model

data class Attachment(
    val id: Long? = null,
    val noteId: Long,
    val uri: String,
    val type: String
)
