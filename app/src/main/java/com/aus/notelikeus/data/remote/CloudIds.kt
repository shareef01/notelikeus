package com.aus.notelikeus.data.remote

import java.util.UUID

object CloudIds {
    private val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    fun newId(): String = UUID.randomUUID().toString()

    fun ensure(cloudId: String?): String = cloudId?.takeIf { isValid(it) } ?: newId()

    fun isValid(value: String?): Boolean = !value.isNullOrBlank() && uuidRegex.matches(value)
}
