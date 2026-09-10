package com.aus.notelikeus.data.attachments

interface AttachmentLocalStorage {
    fun persistImageBytes(bytes: ByteArray, extension: String = "jpg"): String?

    fun readBytes(storagePath: String): ByteArray?

    /**
     * Whether the local file behind [storagePath] exists — `null` when that cannot be determined.
     *
     * [readBytes] returns null both for a file that is gone and for one it failed to read, which
     * is fine for display and not fine for deciding to discard the user's picture. Defaulted to
     * `null` so an implementation that has not considered the question cannot cause a drop.
     */
    fun exists(storagePath: String): Boolean? = null

    fun deleteIfLocal(storagePath: String)
}
