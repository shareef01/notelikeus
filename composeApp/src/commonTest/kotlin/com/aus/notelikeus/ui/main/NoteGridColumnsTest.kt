package com.aus.notelikeus.ui.main

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteGridColumnsTest {
    private val gap = 16.dp

    // A 360dp phone with 12dp content padding each side.
    private val smallPhone = 336.dp

    // A 412dp phone (Pixel 7 class) with the same padding.
    private val typicalPhone = 388.dp

    @Test
    fun twoColumnsStayOnEverydayPhones() {
        assertEquals(2, adaptivePhoneGridColumns(2, smallPhone, gap, 1f))
        assertEquals(2, adaptivePhoneGridColumns(2, typicalPhone, gap, 1f))
    }

    @Test
    fun wideGridModesAreCappedToReadableCards() {
        // Grid 3 on 360dp made 101dp cards; Grid 5 made ~54dp ones.
        assertEquals(2, adaptivePhoneGridColumns(3, smallPhone, gap, 1f))
        assertEquals(2, adaptivePhoneGridColumns(5, smallPhone, gap, 1f))
        assertEquals(2, adaptivePhoneGridColumns(5, typicalPhone, gap, 1f))
    }

    @Test
    fun landscapeAndTabletsKeepTheChosenCount() {
        assertEquals(5, adaptivePhoneGridColumns(5, 780.dp, gap, 1f))
        assertEquals(4, adaptivePhoneGridColumns(4, 1000.dp, gap, 1f))
    }

    @Test
    fun largeFontsWidenTheCardsInsteadOfSqueezingTheText() {
        assertEquals(2, adaptivePhoneGridColumns(2, smallPhone, gap, 1.3f))
        assertEquals(1, adaptivePhoneGridColumns(2, smallPhone, gap, 1.5f))
        assertEquals(1, adaptivePhoneGridColumns(2, smallPhone, gap, 2f))
        assertEquals(2, adaptivePhoneGridColumns(2, typicalPhone, gap, 1.5f))
    }

    @Test
    fun aSmallerFontScaleNeverAddsColumnsAndListModesPassThrough() {
        assertEquals(2, adaptivePhoneGridColumns(2, typicalPhone, gap, 0.85f))
        assertEquals(1, adaptivePhoneGridColumns(1, typicalPhone, gap, 1f))
        assertEquals(1, adaptivePhoneGridColumns(3, 40.dp, gap, 1f))
    }
}
