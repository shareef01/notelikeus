package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Label(
    val id: Long? = null,
    val name: String
)
