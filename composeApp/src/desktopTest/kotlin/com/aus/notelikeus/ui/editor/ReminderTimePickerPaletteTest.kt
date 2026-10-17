package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reminder's time step draws only the theme's neutral palette. Its AM/PM selector used
 * Material's stock `tertiary` pink (light) and maroon (dark), because the app's themes leave those
 * roles undefined; with the default neutral accent nothing in the dialog should carry colour.
 */
@OptIn(ExperimentalTestApi::class)
class ReminderTimePickerPaletteTest {

    /** HSV-style saturation of the most colourful pixel, 0 for pure greys. */
    private fun maxSaturation(dark: Boolean): Float {
        var max = 0f
        runComposeUiTest {
            setContent {
                NotelikeusTheme(
                    preference = ThemePreference(base = if (dark) ThemeBase.DARK else ThemeBase.LIGHT),
                    darkTheme = dark
                ) {
                    ReminderDialog(
                        initialTimestamp = 1_791_990_000_000L,
                        onConfirm = {},
                        onRemove = {},
                        onDismiss = {},
                    )
                }
            }
            onNodeWithText("Choose date & time").performClick()
            onNodeWithText("Next").performClick()
            waitForIdle()
            val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
            val image = onAllNodes(isRoot())[roots - 1].captureToImage().toAwtImage()
            for (x in 0 until image.width) for (y in 0 until image.height) {
                val rgb = image.getRGB(x, y)
                val channels = listOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                val high = channels.max()
                if (high > 0) max = maxOf(max, (high - channels.min()) / high.toFloat())
            }
        }
        return max
    }

    @Test
    fun `the time step carries no off-palette colour in light`() {
        val saturation = maxSaturation(dark = false)
        assertTrue(saturation < 0.1f, "most saturated pixel: $saturation")
    }

    @Test
    fun `the time step carries no off-palette colour in dark`() {
        val saturation = maxSaturation(dark = true)
        assertTrue(saturation < 0.1f, "most saturated pixel: $saturation")
    }
}
