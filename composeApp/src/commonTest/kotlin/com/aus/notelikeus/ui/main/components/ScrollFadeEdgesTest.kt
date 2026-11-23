package com.aus.notelikeus.ui.main.components

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollFadeEdgesTest {
    @Test
    fun aRowThatFitsHasNoFade() {
        assertEquals(false to false, scrollFadeEdges(false, false, LayoutDirection.Ltr))
        assertEquals(false to false, scrollFadeEdges(false, false, LayoutDirection.Rtl))
    }

    @Test
    fun theEdgeWithMoreBehindItFades() {
        // At rest on a narrow phone: more to the right.
        assertEquals(false to true, scrollFadeEdges(canScrollBackward = false, canScrollForward = true, LayoutDirection.Ltr))
        // Scrolled to the end: more to the left.
        assertEquals(true to false, scrollFadeEdges(canScrollBackward = true, canScrollForward = false, LayoutDirection.Ltr))
        // In the middle: both.
        assertEquals(true to true, scrollFadeEdges(canScrollBackward = true, canScrollForward = true, LayoutDirection.Ltr))
    }

    @Test
    fun rightToLeftSwapsTheSides() {
        assertEquals(true to false, scrollFadeEdges(canScrollBackward = false, canScrollForward = true, LayoutDirection.Rtl))
        assertEquals(false to true, scrollFadeEdges(canScrollBackward = true, canScrollForward = false, LayoutDirection.Rtl))
    }
}
