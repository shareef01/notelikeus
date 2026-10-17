package com.aus.notelikeus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.main.NoteFilter
import com.aus.notelikeus.ui.main.components.MainTopAppBar
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Muted chrome text as actually drawn. `onSurfaceVariant` is tuned to clear 4.5:1 and little more,
 * so any extra alpha on it drops below AA: section headers were drawn at 75% (about 3:1) and the
 * search placeholder at 60% (about 2.5:1). Rendered at 3x so glyph cores are solid colour.
 */
@OptIn(ExperimentalTestApi::class)
class MutedTextContrastUiTest {

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** The strongest contrast any pixel of [text]'s node reaches against [background]. */
    private fun measure(dark: Boolean, text: String, content: @Composable () -> Unit): Float {
        var ratio = 0f
        runComposeUiTest {
            var background = Color.Unspecified
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(3f)) {
                    NotelikeusTheme(
                        preference = ThemePreference(base = if (dark) ThemeBase.DARK else ThemeBase.LIGHT),
                        darkTheme = dark
                    ) {
                        background = MaterialTheme.colorScheme.surface
                        Box(Modifier.background(background)) { content() }
                    }
                }
            }
            val image = onNodeWithText(text, useUnmergedTree = true).captureToImage().toAwtImage()
            for (x in 0 until image.width) for (y in 0 until image.height) {
                ratio = maxOf(ratio, contrast(Color(image.getRGB(x, y)), background))
            }
        }
        return ratio
    }

    @Test
    fun `section headers clear AA in light and dark`() {
        for (dark in listOf(false, true)) {
            val ratio = measure(dark, "TODAY") {
                NoteSectionHeader(title = "Today")
            }
            assertTrue(ratio >= 4.5f, "dark=$dark: section header at $ratio")
        }
    }

    @Test
    fun `the search placeholder clears AA in light and dark`() {
        for (dark in listOf(false, true)) {
            val ratio = measure(dark, "Search your notes") {
                MainTopAppBar(
                    searchQuery = "", onSearchQueryChange = {}, onViewModeChange = {}, selectedCount = 0,
                    onClearSelection = {}, onDeleteSelected = {}, onArchiveSelected = {}, onRestoreSelected = {},
                    currentFilter = NoteFilter.ACTIVE, onMenuClick = {}, onProfileClick = {},
                    query = NoteQuery(), onQueryChange = {}, onOpenFilters = {}, allLabels = emptyList(),
                )
            }
            assertTrue(ratio >= 4.5f, "dark=$dark: placeholder at $ratio")
        }
    }
}
