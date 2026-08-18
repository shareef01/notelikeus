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
fun noteColorName(color: Color): String = when (noteColorPaletteIndex(color)) {
    0 -> stringResource(Res.string.no_color)
    1 -> stringResource(Res.string.color_name_red)
    2 -> stringResource(Res.string.color_name_orange)
    3 -> stringResource(Res.string.color_name_yellow)
    4 -> stringResource(Res.string.color_name_green)
    5 -> stringResource(Res.string.color_name_teal)
    6 -> stringResource(Res.string.color_name_blue)
    7 -> stringResource(Res.string.color_name_purple)
    8 -> stringResource(Res.string.color_name_pink)
    else -> stringResource(Res.string.cd_color_swatch)
}
