package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Attachment(
    val id: Long? = null,
    val noteId: Long,
    val uri: String,
    val type: String
)
