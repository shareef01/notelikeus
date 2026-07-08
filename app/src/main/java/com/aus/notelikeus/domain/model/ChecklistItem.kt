package com.aus.notelikeus.domain.model

data class ChecklistItem(
    val id: Long? = null,
    val text: String,
    val isChecked: Boolean = false,
    val position: Int
)
