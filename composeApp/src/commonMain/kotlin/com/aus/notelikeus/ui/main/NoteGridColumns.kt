package com.aus.notelikeus.ui.main

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The narrowest a grid card may get on a phone at 100% font scale.
 *
 * A card spends 40dp on padding, so at 120dp its text column is 80dp: one ordinary word at the
 * 18sp title size. The phone grid used the column count of the chosen view mode as is, so Grid 3
 * on a 360dp phone made 104dp cards and Grid 5 in portrait made ~56dp ones, where titles broke
 * mid-word and a 7-line preview held a word or two per line.
 */
internal val PhoneMinGridCardWidth: Dp = 120.dp

/**
 * How many columns a phone grid shows: the view mode's count, reduced until every card is at
 * least [PhoneMinGridCardWidth] wide, scaled by the system font scale — a reader who enlarges text
 * needs the card width to grow with it, or the larger text fits fewer words per line than before.
 *
 * Never more columns than asked for, never fewer than one. The single-column modes pass through.
 *
 * @param availableWidth the grid's width inside its content padding.
 * @param gap the spacing between columns.
 */
fun adaptivePhoneGridColumns(
    requested: Int,
    availableWidth: Dp,
    gap: Dp,
    fontScale: Float,
): Int {
    if (requested <= 1) return 1
    val minCard = PhoneMinGridCardWidth * fontScale.coerceAtLeast(1f)
    // n cards and n - 1 gaps fit when n * minCard + (n - 1) * gap <= width.
    val fit = ((availableWidth + gap) / (minCard + gap)).toInt()
    return fit.coerceIn(1, requested)
}
