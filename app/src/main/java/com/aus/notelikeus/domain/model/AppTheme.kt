package com.aus.notelikeus.domain.model

import com.aus.notelikeus.R

enum class AppTheme {
    AUTO,
    LIGHT,
    DARK,
    TRUE_DARK,
    MIDNIGHT,
    FOREST;

    fun next(): AppTheme {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromName(name: String?): AppTheme {
            return entries.find { it.name == name } ?: AUTO
        }
    }
}

fun appThemeLabelRes(theme: AppTheme): Int = when (theme) {
    AppTheme.AUTO -> R.string.theme_auto
    AppTheme.LIGHT -> R.string.theme_light
    AppTheme.DARK -> R.string.theme_dark
    AppTheme.TRUE_DARK -> R.string.theme_true_dark
    AppTheme.MIDNIGHT -> R.string.theme_midnight
    AppTheme.FOREST -> R.string.theme_forest
}
