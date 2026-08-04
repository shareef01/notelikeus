package com.aus.notelikeus.domain.model

import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

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

fun appThemeLabelRes(theme: AppTheme): StringResource = when (theme) {
    AppTheme.AUTO -> Res.string.theme_auto
    AppTheme.LIGHT -> Res.string.theme_light
    AppTheme.DARK -> Res.string.theme_dark
    AppTheme.TRUE_DARK -> Res.string.theme_true_dark
    AppTheme.MIDNIGHT -> Res.string.theme_midnight
    AppTheme.FOREST -> Res.string.theme_forest
}
