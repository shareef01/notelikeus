//! How notes become what the window shows, as plain functions with no UI so they are unit-tested:
//! card previews, timestamps, search matching, and the masonry layout.

use chrono::{DateTime, Datelike, TimeZone};
use notelikeus_app::NoteView;

/// A card shows at most this much of a note, like the web app's clamped card body.
const PREVIEW_MAX_LINES: usize = 8;
const PREVIEW_MAX_CHARS: usize = 360;

/// The part of a note's body a card shows: leading blank lines dropped, at most a few lines and a few
/// hundred characters, with an ellipsis when something was cut.
pub fn preview(content: &str) -> String {
    let lines: Vec<&str> = content.trim_start().lines().collect();
    let mut kept = Vec::new();
    let mut chars = 0;
    let mut cut = false;
    for (index, line) in lines.iter().enumerate() {
        if index == PREVIEW_MAX_LINES {
            cut = true;
            break;
        }
        let line = line.trim_end();
        let remaining = PREVIEW_MAX_CHARS.saturating_sub(chars);
        if line.chars().count() > remaining {
            kept.push(line.chars().take(remaining).collect::<String>());
            cut = true;
            break;
        }
        chars += line.chars().count();
        kept.push(line.to_string());
    }
    let mut text = kept.join("\n").trim_end().to_string();
    if cut {
        text.push('…');
    }
    text
}

/// When a note was last edited, as the web app's cards say it: the time today, the day this year,
/// and the full date before that.
pub fn card_time<Tz: TimeZone>(timestamp_ms: i64, now: &DateTime<Tz>) -> String
where
    Tz::Offset: std::fmt::Display,
{
    let Some(at) = now.timezone().timestamp_millis_opt(timestamp_ms).single() else {
        return String::new();
    };
    if at.date_naive() == now.date_naive() {
        at.format("%-I:%M %p").to_string()
    } else if at.year() == now.year() {
        at.format("%-d %b").to_string()
    } else {
        at.format("%-d %b %Y").to_string()
    }
}

/// Whether a note matches the search box: every word appears in the title or the body, ignoring case.
pub fn matches(note: &NoteView, query: &str) -> bool {
    let haystack = format!("{}\n{}", note.title, note.content).to_lowercase();
    query.split_whitespace().all(|word| haystack.contains(&word.to_lowercase()))
}

/// A rough height for a card, in lines, for balancing the columns: the title's and the preview's
/// wrapped lines plus the fixed padding and timestamp.
fn estimated_height(title: &str, preview: &str) -> usize {
    const CHARS_PER_LINE: usize = 38;
    let wrapped = |text: &str| -> usize {
        if text.is_empty() {
            return 0;
        }
        text.lines().map(|line| line.chars().count().div_ceil(CHARS_PER_LINE).max(1)).sum()
    };
    3 + wrapped(title) * 2 + wrapped(preview)
}

/// Places cards into `columns` columns, each card going to the currently shortest column, so the grid
/// stays balanced like the web app's masonry layout while reading in order across the top.
pub fn masonry<T>(cards: Vec<T>, columns: usize, height: impl Fn(&T) -> usize) -> Vec<Vec<T>> {
    let columns = columns.max(1);
    let mut placed: Vec<Vec<T>> = (0..columns).map(|_| Vec::new()).collect();
    let mut heights = vec![0usize; columns];
    for card in cards {
        let shortest = (0..columns).min_by_key(|&c| (heights[c], c)).unwrap_or(0);
        heights[shortest] += height(&card);
        placed[shortest].push(card);
    }
    placed
}

/// The estimated height of a note's card, for [`masonry`].
pub fn card_height(note: &NoteView) -> usize {
    estimated_height(&note.title, &preview(&note.content))
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::FixedOffset;

    fn note(title: &str, content: &str) -> NoteView {
        NoteView {
            id: title.into(),
            title: title.into(),
            content: content.into(),
            timestamp: 0,
            is_pinned: false,
            unsynced: true,
        }
    }

    #[test]
    fn previews_are_trimmed_and_cut_with_an_ellipsis() {
        assert_eq!(preview("\n\n  milk\neggs  \n"), "milk\neggs");
        let long = (1..=12).map(|i| format!("line {i}")).collect::<Vec<_>>().join("\n");
        assert_eq!(preview(&long).lines().count(), 8);
        assert!(preview(&long).ends_with('…'));
        let wide = "x".repeat(1000);
        assert_eq!(preview(&wide).chars().count(), PREVIEW_MAX_CHARS + 1);
        assert_eq!(preview(""), "");
    }

    #[test]
    fn card_times_read_like_the_web_app() {
        let zone = FixedOffset::east_opt(3 * 3600).unwrap();
        let now = zone.with_ymd_and_hms(2026, 11, 21, 16, 30, 0).unwrap();
        let at = |y, m, d, h, min| zone.with_ymd_and_hms(y, m, d, h, min, 0).unwrap().timestamp_millis();
        assert_eq!(card_time(at(2026, 11, 21, 4, 24), &now), "4:24 AM");
        assert_eq!(card_time(at(2026, 11, 21, 15, 5), &now), "3:05 PM");
        assert_eq!(card_time(at(2026, 11, 3, 9, 0), &now), "3 Nov");
        assert_eq!(card_time(at(2025, 12, 31, 9, 0), &now), "31 Dec 2025");
    }

    #[test]
    fn search_needs_every_word_in_any_order_and_case() {
        let groceries = note("Groceries", "Oat milk, eggs, sourdough");
        assert!(matches(&groceries, ""));
        assert!(matches(&groceries, "MILK groceries"));
        assert!(matches(&groceries, "  eggs   oat "));
        assert!(!matches(&groceries, "milk bread"));
    }

    #[test]
    fn masonry_keeps_columns_balanced_and_reading_order_across_the_top() {
        let heights = [10, 2, 2, 2, 2, 2];
        let placed = masonry(heights.to_vec(), 2, |h| *h);
        // The tall first card fills column 0; the short ones stack beside it until they catch up.
        assert_eq!(placed, vec![vec![10], vec![2, 2, 2, 2, 2]]);
        let even = masonry((0..6).collect::<Vec<_>>(), 3, |_| 1);
        assert_eq!(even, vec![vec![0, 3], vec![1, 4], vec![2, 5]]);
        assert_eq!(masonry(vec![1, 2], 0, |_| 1), vec![vec![1, 2]], "zero columns means one");
    }
}
