//! How well a note answers a text query, and the near-miss fallback when nothing answers it: Kotlin
//! `NoteSearchRanking` and the web's `noteSearchRanking.ts`.

use super::text::{contains_word_prefix, fold, haystack, tokens};
use crate::model::Note;

const TITLE_EXACT: u32 = 1_000;
const TITLE_PREFIX: u32 = 400;
const LABEL: u32 = 200;
const CHECKLIST: u32 = 120;
const BODY: u32 = 100;

/// Shorter words are not corrected: every short word is two edits from many others.
const MIN_FUZZY_LENGTH: usize = 4;
const MAX_EDITS: usize = 2;

/// A relevance score, summed over the query's words, each counted where it is found first: title, then
/// labels, then checklist, then body.
pub fn score(note: &Note, needles: &[String]) -> u32 {
    if needles.is_empty() {
        return 0;
    }
    let title = fold(&note.title);
    let labels = fold(&note.labels.join(" "));
    let checklist = fold(&note.checklist.iter().map(|i| i.text.as_str()).collect::<Vec<_>>().join(" "));
    let body = fold(&note.content);
    let mut total = if title == needles.join(" ") { TITLE_EXACT } else { 0 };
    for needle in needles {
        let delta = if contains_word_prefix(&title, needle) {
            TITLE_PREFIX
        } else if contains_word_prefix(&labels, needle) {
            LABEL
        } else if contains_word_prefix(&checklist, needle) {
            CHECKLIST
        } else if contains_word_prefix(&body, needle) {
            BODY
        } else {
            0
        };
        total = total.saturating_add(delta);
    }
    total
}

/// Orders by relevance, then most recently edited. Pinned notes still lead: that is what pinning means.
pub fn by_relevance<'a>(notes: Vec<&'a Note>, needles: &[String]) -> Vec<&'a Note> {
    if needles.is_empty() {
        return notes;
    }
    let mut scored: Vec<(u32, &Note)> = notes.into_iter().map(|n| (score(n, needles), n)).collect();
    scored
        .sort_by(|(sa, a), (sb, b)| b.is_pinned.cmp(&a.is_pinned).then(sb.cmp(sa)).then(b.timestamp.cmp(&a.timestamp)));
    scored.into_iter().map(|(_, n)| n).collect()
}

fn is_close_to(token: &str, needle: &str) -> bool {
    token.starts_with(needle)
        || (needle.chars().count() >= MIN_FUZZY_LENGTH && edit_distance_within(token, needle, MAX_EDITS))
}

/// Notes whose words are close to every query word, for when nothing matches exactly.
pub fn fuzzy_matches<'a>(notes: &[&'a Note], needles: &[String]) -> Vec<&'a Note> {
    if needles.is_empty() {
        return Vec::new();
    }
    notes
        .iter()
        .copied()
        .filter(|note| {
            let words = tokens(&haystack(note));
            needles.iter().all(|needle| words.iter().any(|w| is_close_to(w, needle)))
        })
        .collect()
}

/// Whether `a` and `b` are within `max` single-character edits.
pub fn edit_distance_within(a: &str, b: &str, max: usize) -> bool {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    if a.len().abs_diff(b.len()) > max {
        return false;
    }
    let mut previous: Vec<usize> = (0..=b.len()).collect();
    let mut current = vec![0; b.len() + 1];
    for i in 1..=a.len() {
        current[0] = i;
        let mut row_min = i;
        for j in 1..=b.len() {
            let substitution = previous[j - 1] + usize::from(a[i - 1] != b[j - 1]);
            current[j] = substitution.min(previous[j] + 1).min(current[j - 1] + 1);
            row_min = row_min.min(current[j]);
        }
        if row_min > max {
            return false;
        }
        std::mem::swap(&mut previous, &mut current);
    }
    previous[b.len()] <= max
}
