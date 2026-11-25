package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Attachment metadata. Binary payload lives in local files or object storage
 * ([storagePath]: `pending:`, `r2:`, or `file:` prefix).
 */
@Immutable
@Serializable
data class Attachment(
    val id: String,
    val noteId: Long,
    val storagePath: String,
    val type: String = "image",
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val placement: AttachmentPlacement? = null,
)

/** Position in the exact UTF-8 note body, counted in Unicode scalar values. */
@Immutable
@Serializable
data class AttachmentPlacement(
    val bodySha256: String,
    val offset: Long,
    val ordinal: Long,
)
