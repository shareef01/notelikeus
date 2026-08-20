package com.aus.notelikeus.domain.query

/**
 * Normalisation for search: case folded, diacritics stripped, punctuation reduced to spaces.
 *
 * Search used to be `String.contains(query, ignoreCase = true)` over every note in memory. That
 * matches mid-word, which sounds generous and mostly is not — searching `ote` found "notes" while
 * searching `cafe` did not find "café", because case folding is the only normalisation `contains`
 * does. Anyone typing an accented language got the worse half of both behaviours.
 *
 * The folding table is written out rather than delegated to `java.text.Normalizer`, which is the
 * obvious tool and unavailable here: this is `commonMain`, and while both current targets happen
 * to be JVM there is no shared `jvmMain` source set to hang an `actual` on. Adding one is a build
 * change, and `expect`/`actual` would mean the identical implementation written twice, in two
 * source sets, free to drift. Sixty lines of table has no such failure mode and is directly
 * testable.
 *
 * Covers Latin-1 Supplement and Latin Extended-A, which is every accented form the Latin-script
 * languages this app is likely to hold actually use. Anything outside that range passes through
 * unchanged: a Greek or Cyrillic note is still searchable by its own characters, it simply gets no
 * folding. That is the correct failure — dropping unknown characters would make those notes
 * unfindable rather than merely unfolded.
 */
private fun foldChar(c: Char): String = when (c.code) {
    in 0xC0..0xC5, in 0xE0..0xE5 -> "a"
    0xC6, 0xE6 -> "ae"
    0xC7, 0xE7 -> "c"
    in 0xC8..0xCB, in 0xE8..0xEB -> "e"
    in 0xCC..0xCF, in 0xEC..0xEF -> "i"
    0xD0, 0xF0 -> "d"
    0xD1, 0xF1 -> "n"
    in 0xD2..0xD6, in 0xF2..0xF6, 0xD8, 0xF8 -> "o"
    in 0xD9..0xDC, in 0xF9..0xFC -> "u"
    0xDD, 0xFD, 0xFF -> "y"
    0xDE, 0xFE -> "th"
    0xDF -> "ss"

    // Latin Extended-A
    in 0x100..0x105 -> "a"
    in 0x106..0x10D -> "c"
    in 0x10E..0x111 -> "d"
    in 0x112..0x11B -> "e"
    in 0x11C..0x123 -> "g"
    in 0x124..0x127 -> "h"
    in 0x128..0x131 -> "i"
    0x132, 0x133 -> "ij"
    0x134, 0x135 -> "j"
    in 0x136..0x138 -> "k"
    in 0x139..0x142 -> "l"
    in 0x143..0x14B -> "n"
    in 0x14C..0x151 -> "o"
    0x152, 0x153 -> "oe"
    in 0x154..0x159 -> "r"
    in 0x15A..0x161 -> "s"
    in 0x162..0x167 -> "t"
    in 0x168..0x173 -> "u"
    0x174, 0x175 -> "w"
    in 0x176..0x178 -> "y"
    in 0x179..0x17E -> "z"
    0x17F -> "s"

    else -> c.toString()
}

/**
 * The searchable form of a string: lower case, folded, with every non-alphanumeric run collapsed
 * to a single space and the ends trimmed.
 *
 * Punctuation becomes a separator rather than being deleted, so `don't` tokenises as `don` + `t`
 * rather than `dont`. That is deliberate: it means a search for `don` finds it, and the
 * alternative — gluing the halves — invents a word the note does not contain.
 */
fun foldForSearch(input: String): String {
    val out = StringBuilder(input.length)
    var pendingSpace = false
    for (raw in input) {
        val lowered = raw.lowercaseChar()
        val folded = foldChar(lowered)
        val isWord = folded.all { it.isLetterOrDigit() }
        if (folded.isEmpty() || !isWord) {
            // Collapse separators, and never emit a leading one.
            if (out.isNotEmpty()) pendingSpace = true
            continue
        }
        if (pendingSpace) {
            out.append(' ')
            pendingSpace = false
        }
        out.append(folded)
    }
    return out.toString()
}

/** The folded tokens of [input], in order, with duplicates preserved. */
fun searchTokens(input: String): List<String> =
    foldForSearch(input).split(' ').filter { it.isNotEmpty() }

/**
 * The value stored in `notes.searchText`.
 *
 * Everything the old in-memory search looked at — title, body, checklist item text and label
 * names — flattened into one folded string, so a single indexed column answers what previously
 * required four passes over every note plus its joined rows.
 *
 * Label *names* are included rather than ids because that is what a user types. It also means the
 * column goes stale when a label is renamed, which the repository handles by rewriting every note
 * carrying that label — the same set it already rewrites to keep the cloud copies current.
 */
fun buildSearchText(
    title: String,
    content: String,
    checklistTexts: List<String>,
    labelNames: List<String>
): String {
    val parts = buildList {
        add(title)
        add(content)
        addAll(checklistTexts)
        addAll(labelNames)
    }
    return foldForSearch(parts.filter { it.isNotBlank() }.joinToString(" "))
}
