package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ChecklistItem(
    val id: Long? = null,
    val text: String,
    val isChecked: Boolean = false,
    val position: Int
)
