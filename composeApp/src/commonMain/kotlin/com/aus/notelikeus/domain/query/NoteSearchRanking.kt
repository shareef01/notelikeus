package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.Note

/**
 * How well a note answers a text query, and what to do when nothing answers it at all.
 *
 * Separate from [NoteQueryMatcher] because matching and ranking are different questions: matching
 * decides membership and must be cheap and exact, ranking decides order and is allowed to be
 * fuzzy. Keeping them apart is also what makes the fuzzy fallback possible without loosening the
 * definition of a match.
 */
object NoteSearchRanking {

    // Where a token was found, most specific first. Gaps are deliberate: a note matching in two
    // places should outrank one matching in a single better place only when the difference is
    // real, so the bands are wide enough not to be summed into nonsense.
    private const val TITLE_EXACT = 1_000
    private const val TITLE_PREFIX = 400
    private const val LABEL = 200
    private const val CHECKLIST = 120
    private const val BODY = 100

    /** Tokens shorter than this are not worth correcting: every short word is 2 edits from many. */
    private const val MIN_FUZZY_LENGTH = 4
    private const val MAX_EDITS = 2

    /**
     * A relevance score for [note] against already-folded [needles].
     *
     * Scores are summed across tokens, so a two-word query matching both in the title beats one
     * matching one word in the title and one in the body — which is the ordering people expect
     * and the reason this is not simply "best single hit".
     */
    fun score(note: Note, needles: List<String>): Int {
        if (needles.isEmpty()) return 0

        // Folded on demand, cheapest field first. The body is by far the longest and folding it
        // for a note whose title already matched is the bulk of the cost of ranking a large
        // result set -- most hits are decided before it is ever needed.
        val title = foldForSearch(note.title)
        var labels: String? = null
        var checklist: String? = null
        var body: String? = null

        // The whole query as one phrase, the strongest signal there is: someone typing a note's
        // title wants that note first, not a longer one that mentions each word separately.
        var total = if (title == needles.joinToString(" ")) TITLE_EXACT else 0

        for (needle in needles) {
            if (containsTokenPrefix(title, needle)) {
                total += TITLE_PREFIX
                continue
            }
            if (labels == null) labels = note.labels.joinToString(" ") { foldForSearch(it.name) }
            if (containsTokenPrefix(labels, needle)) {
                total += LABEL
                continue
            }
            if (checklist == null) {
                checklist = note.checklist.joinToString(" ") { foldForSearch(it.text) }
            }
            if (containsTokenPrefix(checklist, needle)) {
                total += CHECKLIST
                continue
            }
            if (body == null) body = foldForSearch(note.content)
            if (containsTokenPrefix(body, needle)) total += BODY
        }
        return total
    }

    /**
     * Orders [notes] by relevance, then by most recently edited.
     *
     * Pinned notes still lead, for the same reason they do in every other mode: that is what
     * pinning means, and a pinned note dropping below an unpinned one because it scored lower
     * would read as the pin having been ignored.
     */
    fun byRelevance(notes: List<Note>, text: String): List<Note> {
        val needles = searchTokens(text)
        if (needles.isEmpty()) return notes

        // Scored once per note, not once per comparison. Sorting calls its comparator O(n log n)
        // times, and score() folds four fields, so computing it inside the comparator would fold
        // the whole corpus a dozen times over for one search.
        val scored = notes.map { it to score(it, needles) }
        val comparator = compareByDescending<Pair<Note, Int>> { it.second }
            .thenByDescending { it.first.timestamp }
        val (pinned, rest) = scored.partition { it.first.isPinned }
        return (pinned.sortedWith(comparator) + rest.sortedWith(comparator)).map { it.first }
    }

    /**
     * Notes whose text is *close* to the query, for when nothing matches it exactly.
     *
     * Only ever consulted after a strict pass returned nothing, so it can afford to be generous
     * without loosening what "matching" means: a typo'd search shows near misses labelled as such
     * rather than an empty screen, and an intentionally narrow search still returns nothing.
     *
     * Tokens under [MIN_FUZZY_LENGTH] are required to match exactly. At three characters almost
     * everything is within two edits of almost everything else, so correcting them produces noise
     * rather than suggestions.
     */
    fun fuzzyMatches(notes: List<Note>, text: String): List<Note> {
        val needles = searchTokens(text)
        if (needles.isEmpty()) return emptyList()
        return notes.filter { note ->
            val haystack = searchTokens(note.searchText ?: note.searchableText())
            needles.all { needle -> haystack.any { it.isCloseTo(needle) } }
        }
    }

    private fun String.isCloseTo(needle: String): Boolean = when {
        startsWith(needle) -> true
        needle.length < MIN_FUZZY_LENGTH -> false
        else -> editDistanceWithin(this, needle, MAX_EDITS)
    }

    /** Whether [a] and [b] are within [max] single-character edits. */
    internal fun editDistanceWithin(a: String, b: String, max: Int): Boolean {
        // A length gap larger than the budget cannot be closed, and checking first avoids
        // building the row at all for the overwhelmingly common non-match.
        if (a.length - b.length > max || b.length - a.length > max) return false

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
                if (current[j] < rowMin) rowMin = current[j]
            }
            // Every later row is at least this good, so once a whole row exceeds the budget the
            // answer cannot come back under it.
            if (rowMin > max) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= max
    }

    private fun containsTokenPrefix(haystack: String, needle: String): Boolean =
        haystack.startsWith(needle) || haystack.contains(" $needle")
}
