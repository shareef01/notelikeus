package com.aus.notelikeus.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sidebar's collapse control points where the sidebar will go: left ("‹") to collapse it, right
 * ("›") to expand it. The rotation was inverted, so "Collapse" pointed right.
 */
@OptIn(ExperimentalTestApi::class)
class DrawerCollapseChevronTest {

    /**
     * "‹" or "›", read from the glyph's pixels: a chevron's point is one dot in its column, while
     * its open side holds both arms, so the open side's column spans more rows.
     */
    private fun BufferedImage.direction(): Char {
        // The icon's own corner is its backdrop; anything clearly off it is the glyph.
        val background = Color(getRGB(0, 0)).luminance()
        val ink = (0 until width).map { x ->
            (0 until height).filter { y ->
                kotlin.math.abs(Color(getRGB(x, y)).luminance() - background) > 0.005f
            }
        }
        val columns = ink.indices.filter { ink[it].isNotEmpty() }
        val span = { x: Int -> ink[x].max() - ink[x].min() }
        return if (span(columns.first()) < span(columns.last())) '‹' else '›'
    }

    private fun chevron(collapsed: Boolean): Char {
        var direction = ' '
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    Box(Modifier.width(if (collapsed) 80.dp else 300.dp)) {
                        MainDrawerContent(
                            state = MainState(isLoading = false),
                            collapsed = collapsed,
                            isExpanded = true,
                            settingsSelected = false,
                            onFilterSelect = {},
                            onSmartViewSelect = {},
                            onSavedFilterSelect = {},
                            onEditLabels = {},
                            onOpenSettings = {},
                            onCloudSignOut = {},
                            onSidebarCollapsedChange = {},
                        )
                    }
                }
            }
            val label = if (collapsed) "Expand sidebar" else "Collapse sidebar"
            direction = onNodeWithContentDescription(label, useUnmergedTree = true)
                .captureToImage().toAwtImage().direction()
        }
        return direction
    }

    @Test
    fun `the expanded sidebar's Collapse control points left`() = assertEquals('‹', chevron(collapsed = false))

    @Test
    fun `the collapsed rail's Expand control points right`() = assertEquals('›', chevron(collapsed = true))
}
