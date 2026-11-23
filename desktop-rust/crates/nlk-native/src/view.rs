//! How notes become what the window shows, as plain functions with no UI so they are unit-tested:
//! card previews, Markdown stripping & formatting helpers, safe URLs, timestamps, and masonry layout.

use chrono::{DateTime, Datelike, TimeZone};
use notelikeus_app::NoteView;

/// A card shows at most this much of a note, like the web app's clamped card body.
const PREVIEW_MAX_LINES: usize = 8;
const PREVIEW_MAX_CHARS: usize = 360;
const MAX_MARKDOWN_NESTING: usize = 8;

/// Strips the supported Markdown subset (`**bold**`, `__bold__`, `_italic_`, `[label](url)`) so card
/// previews read as clean plain text matching the Kotlin and Web card renderers.
pub fn strip_markdown(input: &str) -> String {
    strip_nested(input, 0)
}

fn strip_nested(input: &str, depth: usize) -> String {
    if depth >= MAX_MARKDOWN_NESTING || (!input.contains('*') && !input.contains('_') && !input.contains('[')) {
        return input.to_string();
    }
    let mut out = String::with_capacity(input.len());
    let mut i = 0;
    while i < input.len() {
        let rest = &input[i..];
        if rest.starts_with("**")
            && let Some(end) = find_closing_double(&rest[2..], "**")
            && end > 0
        {
            out.push_str(&strip_nested(&rest[2..2 + end], depth + 1));
            i += 2 + end + 2;
            continue;
        }
        if rest.starts_with("__")
            && let Some(end) = find_closing_double(&rest[2..], "__")
            && end > 0
        {
            out.push_str(&strip_nested(&rest[2..2 + end], depth + 1));
            i += 2 + end + 2;
            continue;
        }
        if rest.starts_with('_')
            && !rest.starts_with("__")
            && let Some(end) = find_closing_single_underscore(&rest[1..])
            && end > 0
        {
            out.push_str(&strip_nested(&rest[1..1 + end], depth + 1));
            i += 1 + end + 1;
            continue;
        }
        if rest.starts_with('[')
            && let Some((label, total_len)) = parse_markdown_link(rest)
        {
            out.push_str(&strip_nested(label, depth + 1));
            i += total_len;
            continue;
        }
        let ch = rest.chars().next().expect("non-empty rest");
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn find_closing_double(haystack: &str, delim: &str) -> Option<usize> {
    haystack.find(delim).filter(|&pos| !haystack[..pos].contains('\n'))
}

fn find_closing_single_underscore(haystack: &str) -> Option<usize> {
    let bytes = haystack.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'\n' {
            return None;
        }
        if bytes[i] == b'_' {
            let next_underscore = bytes.get(i + 1) == Some(&b'_');
            if !next_underscore {
                return Some(i);
            }
            while i < bytes.len() && bytes[i] == b'_' {
                i += 1;
            }
            continue;
        }
        i += 1;
    }
    None
}

fn parse_markdown_link(input: &str) -> Option<(&str, usize)> {
    let close_bracket = input.find("](")?;
    if close_bracket <= 1 || input[..close_bracket].contains('\n') {
        return None;
    }
    let after_open = &input[close_bracket + 2..];
    let close_paren = after_open.find(')')?;
    if close_paren == 0 || after_open[..close_paren].contains('\n') {
        return None;
    }
    let label = &input[1..close_bracket];
    Some((label, close_bracket + 2 + close_paren + 1))
}

/// Validates and normalizes a note-authored link URL, matching Kotlin `SafeHref` / Web `toSafeHref`.
/// Rejects empty strings, whitespace/control characters, and non-HTTP(S)/mailto schemes (`javascript:`,
/// `data:`, `file:`, etc.).
pub fn normalize_safe_href(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return None;
    }
    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("https://") || lower.starts_with("http://") || lower.starts_with("mailto:") {
        return Some(trimmed.to_string());
    }
    if let Some(colon) = trimmed.find(':') {
        let scheme = &trimmed[..colon];
        let looks_like_scheme = scheme.chars().next().is_some_and(|c| c.is_ascii_alphabetic())
            && scheme.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '.' | '-'));
        if looks_like_scheme {
            return None;
        }
    }
    Some(format!("https://{trimmed}"))
}

/// Wraps `[start..end]` (char offsets) in `marker` or inserts an empty pair when collapsed, matching
/// Kotlin `TextFormatting.wrapSelection`. Returns `(new_text, new_sel_start, new_sel_end)`.
pub fn wrap_markdown_selection(text: &str, start_char: usize, end_char: usize, marker: &str) -> (String, usize, usize) {
    let total_chars = text.chars().count();
    let (a, b) = (start_char.min(end_char).min(total_chars), start_char.max(end_char).min(total_chars));
    let start_byte = char_to_byte_idx(text, a);
    let end_byte = char_to_byte_idx(text, b);
    let marker_chars = marker.chars().count();
    let mut out = String::with_capacity(text.len() + marker.len() * 2);
    out.push_str(&text[..start_byte]);
    if a == b {
        out.push_str(marker);
        out.push_str(marker);
        out.push_str(&text[end_byte..]);
        return (out, a + marker_chars, a + marker_chars);
    }
    let selected = &text[start_byte..end_byte];
    out.push_str(marker);
    out.push_str(selected);
    out.push_str(marker);
    out.push_str(&text[end_byte..]);
    (out, a + marker_chars, b + marker_chars)
}

pub fn toggle_bold(text: &str, start_char: usize, end_char: usize) -> (String, usize, usize) {
    wrap_markdown_selection(text, start_char, end_char, "**")
}

pub fn toggle_italic(text: &str, start_char: usize, end_char: usize) -> (String, usize, usize) {
    wrap_markdown_selection(text, start_char, end_char, "_")
}

/// Prefixes non-blank lines spanning `[start..end]` with `"• "` if not already bulleted.
pub fn toggle_bullet_list(text: &str, start_char: usize, end_char: usize) -> (String, usize, usize) {
    let total_chars = text.chars().count();
    let (a, b) = (start_char.min(end_char).min(total_chars), start_char.max(end_char).min(total_chars));
    let start_byte = char_to_byte_idx(text, a);
    let end_byte = char_to_byte_idx(text, b);

    let line_start_byte = text[..start_byte].rfind('\n').map_or(0, |i| i + 1);
    let line_end_byte = text[end_byte..].find('\n').map_or(text.len(), |i| end_byte + i);

    let block = &text[line_start_byte..line_end_byte];
    let prefixed: Vec<String> =
        block
            .split('\n')
            .map(|line| {
                if line.trim().is_empty() || line.starts_with("• ") {
                    line.to_string()
                } else {
                    format!("• {line}")
                }
            })
            .collect();
    let joined = prefixed.join("\n");
    let mut out = String::with_capacity(text.len() + joined.len());
    out.push_str(&text[..line_start_byte]);
    out.push_str(&joined);
    out.push_str(&text[line_end_byte..]);
    let new_start = text[..line_start_byte].chars().count();
    let new_end = new_start + joined.chars().count();
    (out, new_start, new_end)
}

/// Inserts a safe `[label](url)` link over `[start..end]`. Returns `Err` on unsafe/empty URLs.
pub fn insert_link(
    text: &str,
    start_char: usize,
    end_char: usize,
    label: &str,
    raw_url: &str,
) -> Result<(String, usize, usize), String> {
    let url = normalize_safe_href(raw_url)
        .ok_or_else(|| "Enter a valid web or email link (http://, https://, or mailto:).".to_string())?;
    let total_chars = text.chars().count();
    let (a, b) = (start_char.min(end_char).min(total_chars), start_char.max(end_char).min(total_chars));
    let start_byte = char_to_byte_idx(text, a);
    let end_byte = char_to_byte_idx(text, b);
    let clean_label = label.trim();
    let link_label = if !clean_label.is_empty() {
        clean_label
    } else if a != b {
        text[start_byte..end_byte].trim()
    } else {
        raw_url.trim()
    };
    let safe_label = link_label.replace(['[', ']', '\r', '\n'], " ");
    let markdown = format!("[{safe_label}]({url})");
    let mut out = String::with_capacity(text.len() + markdown.len());
    out.push_str(&text[..start_byte]);
    out.push_str(&markdown);
    out.push_str(&text[end_byte..]);
    let caret = a + markdown.chars().count();
    Ok((out, caret, caret))
}

fn char_to_byte_idx(text: &str, char_idx: usize) -> usize {
    text.char_indices().nth(char_idx).map_or(text.len(), |(byte_idx, _)| byte_idx)
}

pub fn is_overdue(reminder_timestamp: Option<i64>, now_ms: i64) -> bool {
    reminder_timestamp.is_some_and(|ts| ts <= now_ms)
}

/// The part of a note's body a card shows: Markdown markers stripped, leading blank lines dropped, at
/// most a few lines and a few hundred characters, with an ellipsis when something was cut.
pub fn preview(content: &str) -> String {
    let stripped = strip_markdown(content);
    let lines: Vec<&str> = stripped.trim_start().lines().collect();
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

/// The Filters sheet's chip rows: how many chips go on each row, by an estimate of each chip's width in
/// the sheet (Slint has no wrapping layout). Every chip goes somewhere, a very long one on its own row.
pub fn wrap_chips(texts: &[&str]) -> Vec<std::ops::Range<usize>> {
    const ROW_WIDTH: usize = 540;
    const GAP: usize = 8;
    let width = |text: &str| text.chars().count() * 15 / 2 + 42;
    let mut rows = Vec::new();
    let (mut start, mut used) = (0, 0);
    for (index, text) in texts.iter().enumerate() {
        let chip = width(text);
        if index > start && used + GAP + chip > ROW_WIDTH {
            rows.push(start..index);
            (start, used) = (index, 0);
        }
        used += if index > start { GAP + chip } else { chip };
    }
    if start < texts.len() {
        rows.push(start..texts.len());
    }
    rows
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
    // A checklist card shows up to six items and a "+ n more" line; labels take one row of chips.
    let items = note.checklist.len().min(6) + usize::from(note.checklist.len() > 6);
    let labels = usize::from(!note.labels.is_empty()) * 2;
    // The first picture leads the card, 160 px tall.
    let cover = usize::from(!note.images.is_empty()) * 8;
    estimated_height(&note.title, &preview(&note.content))
        + items
        + labels
        + cover
        + usize::from(note.reminder_timestamp.is_some())
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
            reminder_timestamp: None,
            is_pinned: false,
            unsynced: true,
            color: 0,
            is_archived: false,
            is_trashed: false,
            labels: Vec::new(),
            checklist: Vec::new(),
            images: Vec::new(),
        }
    }

    #[test]
    fn checklists_and_labels_make_a_card_taller() {
        let plain = note("Packing", "");
        let mut list = plain.clone();
        list.checklist = (0..10)
            .map(|i| notelikeus_app::ChecklistItem { text: format!("item {i}"), is_checked: false, position: i })
            .collect();
        // Six items show, plus the "+ 4 more items" line.
        assert_eq!(card_height(&list), card_height(&plain) + 7);
        let labelled = NoteView { labels: vec!["Travel".into()], ..plain.clone() };
        assert!(card_height(&labelled) > card_height(&plain));
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
    fn chips_wrap_into_rows_that_fit_and_none_is_lost() {
        let flags = [
            "Pinned",
            "Has reminder",
            "Overdue",
            "Has checklist",
            "Unfinished items",
            "Has links",
            "Untitled",
            "No labels",
        ];
        let rows = wrap_chips(&flags);
        assert!(rows.len() >= 2, "eight chips do not fit one row");
        assert_eq!(rows.first().map(|r| r.start), Some(0));
        assert_eq!(rows.last().map(|r| r.end), Some(flags.len()));
        assert!(rows.windows(2).all(|w| w[0].end == w[1].start), "rows are contiguous");
        let long = "x".repeat(200);
        assert_eq!(wrap_chips(&[&long, "a"]), vec![0..1, 1..2], "a chip wider than a row gets its own");
        assert!(wrap_chips(&[]).is_empty());
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

    #[test]
    fn strip_markdown_removes_inline_syntax_and_keeps_text() {
        assert_eq!(strip_markdown("**bold** and _italic_ and __more__"), "bold and italic and more");
        assert_eq!(strip_markdown("See [Notelikeus](https://example.com) now"), "See Notelikeus now");
        assert_eq!(strip_markdown("**_nested_** item"), "nested item");
        assert_eq!(strip_markdown("• **milk**\n• eggs"), "• milk\n• eggs");
        assert_eq!(preview("**Hello** [world](https://example.com)"), "Hello world");
    }

    #[test]
    fn safe_href_matches_kotlin_and_web_rules() {
        assert_eq!(normalize_safe_href("example.com"), Some("https://example.com".into()));
        assert_eq!(normalize_safe_href("https://example.com/a?b=1"), Some("https://example.com/a?b=1".into()));
        assert_eq!(normalize_safe_href("mailto:hi@example.com"), Some("mailto:hi@example.com".into()));
        assert_eq!(normalize_safe_href("javascript:alert(1)"), None);
        assert_eq!(normalize_safe_href("data:text/html,<script>"), None);
        assert_eq!(normalize_safe_href("file:///C:/Windows/win.ini"), None);
        assert_eq!(normalize_safe_href("   "), None);
    }

    #[test]
    fn formatting_helpers_handle_selections_and_collapsed_carets() {
        assert_eq!(toggle_bold("hello world", 6, 11), ("hello **world**".into(), 8, 13));
        assert_eq!(toggle_bold("hello", 5, 5), ("hello****".into(), 7, 7));
        assert_eq!(toggle_italic("hello world", 0, 5), ("_hello_ world".into(), 1, 6));
        assert_eq!(toggle_bullet_list("milk\neggs\n\n• bread", 0, 17), ("• milk\n• eggs\n\n• bread".into(), 0, 22));
        assert_eq!(
            insert_link("check this out", 6, 10, "", "example.com").unwrap(),
            ("check [this](https://example.com) out".into(), 33, 33)
        );
        assert!(insert_link("x", 0, 1, "bad", "javascript:alert(1)").is_err());
        assert!(is_overdue(Some(100), 100));
        assert!(!is_overdue(Some(101), 100));
    }
}
