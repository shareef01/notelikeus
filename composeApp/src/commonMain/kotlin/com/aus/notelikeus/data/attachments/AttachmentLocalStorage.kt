package com.aus.notelikeus.data.attachments

interface AttachmentLocalStorage {
    fun persistImageBytes(bytes: ByteArray, extension: String = "jpg"): String?

    fun readBytes(storagePath: String): ByteArray?

    fun deleteIfLocal(storagePath: String)
}
