package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Label(
    val id: Long? = null,
    val name: String
)
