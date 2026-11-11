package com.aus.notelikeus.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.cd_color_swatch
import notelikeus.composeapp.generated.resources.color_name_blue
import notelikeus.composeapp.generated.resources.color_name_green
import notelikeus.composeapp.generated.resources.color_name_orange
import notelikeus.composeapp.generated.resources.color_name_pink
import notelikeus.composeapp.generated.resources.color_name_purple
import notelikeus.composeapp.generated.resources.color_name_red
import notelikeus.composeapp.generated.resources.color_name_teal
import notelikeus.composeapp.generated.resources.color_name_yellow
import notelikeus.composeapp.generated.resources.no_color
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A spoken name for a note colour.
 *
 * The colour swatches are the one control in the app that carries its entire meaning in its
 * appearance, so without this every one of them announced the same generic "Note color" and a
 * screen-reader user had eight identical, indistinguishable buttons. Sighted keyboard users hit
 * the same wall in the focus order.
 *
 * Falls back to the generic label for anything outside the built-in palette — an imported note can
 * carry any ARGB value, and inventing a name for an arbitrary colour would be worse than not
 * naming it.
 */
@Composable
fun noteColorName(color: Color): String =
    stringResource(NOTE_COLOR_NAME_RESOURCES.getOrNull(noteColorPaletteIndex(color)) ?: Res.string.cd_color_swatch)

/**
 * The name of each palette entry, in [NOTE_COLOR_OPTIONS] order. Web's `NOTE_COLOR_NAMES` holds the
 * same English words; both are pinned by `contracts/design/note-colors.json`.
 */
val NOTE_COLOR_NAME_RESOURCES: List<StringResource> = listOf(
    Res.string.no_color,
    Res.string.color_name_red,
    Res.string.color_name_orange,
    Res.string.color_name_yellow,
    Res.string.color_name_green,
    Res.string.color_name_teal,
    Res.string.color_name_blue,
    Res.string.color_name_purple,
    Res.string.color_name_pink,
)
