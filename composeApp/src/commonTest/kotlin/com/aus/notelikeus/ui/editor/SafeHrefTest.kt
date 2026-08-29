package com.aus.notelikeus.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafeHrefTest {

    @Test
    fun normalize_passesThroughHttpHttpsAndMailto() {
        assertEquals("https://example.com", SafeHref.normalize("https://example.com"))
        assertEquals("http://example.com", SafeHref.normalize("  http://example.com  "))
        assertEquals("MAILTO:a@b.com", SafeHref.normalize("MAILTO:a@b.com"))
    }

    @Test
    fun normalize_assumesHttpsForBareDomains() {
        assertEquals("https://example.com/notes", SafeHref.normalize("example.com/notes"))
    }

    @Test
    fun normalize_rejectsOtherSchemes() {
        assertNull(SafeHref.normalize("javascript:alert(1)"))
        assertNull(SafeHref.normalize("data:text/html,<script>"))
        assertNull(SafeHref.normalize("vbscript:msgbox(1)"))
    }

    @Test
    fun normalize_rejectsBlank() {
        assertNull(SafeHref.normalize("   "))
        assertNull(SafeHref.normalize(""))
    }
}
