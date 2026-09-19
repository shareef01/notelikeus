package com.aus.notelikeus.ui.navigation

/**
 * Opaque token stamped into our own PendingIntents (widget, reminders).
 * Android persists it so a widget tap still matches after a process death.
 */
expect object InternalNavigationToken {
    fun init(context: Any)
    fun current(): String
    fun matches(intent: Any?): Boolean
}

/** Opaque token extra — do not treat a public boolean as proof of same-app origin. */
const val EXTRA_INTERNAL_NAV_TOKEN = "com.aus.notelikeus.INTERNAL_NAV_TOKEN"

/** @deprecated Prefer [EXTRA_INTERNAL_NAV_TOKEN]; kept only so old code paths compile. */
@Deprecated("Use EXTRA_INTERNAL_NAV_TOKEN")
const val EXTRA_INTERNAL_NAV = "com.aus.notelikeus.INTERNAL_NAV"

expect fun extractEditorNoteId(intent: Any?): Long?

expect fun intentRequestsNewNote(intent: Any?): Boolean

expect fun extractSharedText(intent: Any?): Pair<String?, String?>?

/**
 * Normalized representation of an image shared into the app from an external source,
 * bound to the originating owner/session.
 */
data class SharedImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
    val title: String? = null,
    val content: String? = null,
    val originatingOwnerId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as SharedImagePayload
        return bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType &&
            title == other.title &&
            content == other.content &&
            originatingOwnerId == other.originatingOwnerId
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (originatingOwnerId?.hashCode() ?: 0)
        return result
    }
}
