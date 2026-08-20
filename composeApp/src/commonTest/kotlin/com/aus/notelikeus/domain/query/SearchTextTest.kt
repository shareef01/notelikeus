package com.aus.notelikeus.domain.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTextTest {

    @Test
    fun `folds case`() {
        assertEquals("hello world", foldForSearch("Hello WORLD"))
    }

    @Test
    fun `strips diacritics so an accented note is findable by plain letters`() {
        // The motivating case: `contains(ignoreCase = true)` folds case and nothing else, so
        // searching "cafe" never found "café".
        assertEquals("cafe", foldForSearch("café"))
        assertEquals("uber", foldForSearch("Über"))
        assertEquals("naive", foldForSearch("naïve"))
        assertEquals("zurich", foldForSearch("Zürich"))
        assertEquals("garcon", foldForSearch("garçon"))
        assertEquals("smorgasbord", foldForSearch("smörgåsbord"))
    }

    @Test
    fun `expands the ligatures and eszett rather than dropping them`() {
        assertEquals("straße".let { foldForSearch(it) }, "strasse")
        assertEquals("aether", foldForSearch("æther"))
        assertEquals("oeuvre", foldForSearch("œuvre"))
    }

    @Test
    fun `covers Latin Extended-A`() {
        assertEquals("lodz", foldForSearch("Łódź"))
        assertEquals("cesky", foldForSearch("Český"))
        assertEquals("gdansk", foldForSearch("Gdańsk"))
        assertEquals("istanbul", foldForSearch("İstanbul"))
    }

    @Test
    fun `punctuation separates rather than glues`() {
        // "don't" -> don, t. Gluing them would invent "dont", a word the note does not contain.
        assertEquals("don t", foldForSearch("don't"))
        assertEquals("a b", foldForSearch("a---b"))
        assertEquals("hello world", foldForSearch("  hello,   world!  "))
    }

    @Test
    fun `keeps digits`() {
        assertEquals("meeting 2026 q3", foldForSearch("Meeting 2026 Q3"))
    }

    @Test
    fun `passes unfolded scripts through instead of deleting them`() {
        // No folding table for these, but they must stay searchable by their own characters.
        // Dropping them would make those notes unfindable, which is strictly worse.
        assertEquals("привет", foldForSearch("Привет"))
        assertEquals("日本語", foldForSearch("日本語"))
    }

    @Test
    fun `empty and separator-only input fold to empty`() {
        assertEquals("", foldForSearch(""))
        assertEquals("", foldForSearch("   "))
        assertEquals("", foldForSearch("--- ,,, ---"))
    }

    @Test
    fun `tokenises in order and keeps duplicates`() {
        assertEquals(listOf("the", "cat", "the", "hat"), searchTokens("The cat the HAT"))
        assertEquals(emptyList(), searchTokens("!!!"))
    }

    @Test
    fun `search text covers every field the old in-memory search looked at`() {
        val text = buildSearchText(
            title = "Réunion",
            content = "Discuss the björk release",
            checklistTexts = listOf("Buy tickets", "Café after"),
            labelNames = listOf("Musique")
        )
        listOf("reunion", "bjork", "tickets", "cafe", "musique").forEach {
            assertTrue(it in text, "'$it' missing from '$text'")
        }
    }

    @Test
    fun `search text skips blank parts without leaving double spaces`() {
        val text = buildSearchText("Title", "", emptyList(), listOf("", "Work"))
        assertEquals("title work", text)
    }

    @Test
    fun `folding is idempotent`() {
        // The column is rebuilt on every write; folding an already-folded value must not drift.
        val once = buildSearchText("Café Zürich", "Łódź", listOf("naïve"), listOf("Œuvre"))
        assertEquals(once, foldForSearch(once))
    }
}
