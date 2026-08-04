package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ChecklistItem(
    val id: Long? = null,
    val text: String,
    val isChecked: Boolean = false,
    val position: Int
)
