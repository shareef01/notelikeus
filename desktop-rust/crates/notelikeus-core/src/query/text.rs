//! Search normalisation, as Kotlin `domain.query.SearchText` and the web's `searchText.ts`: lower case,
//! diacritics folded with the same table, punctuation collapsed to single spaces, and matching by word
//! prefix. "ote" does not find "notes"; "cafe" finds "café".

use crate::model::Note;

fn fold_char(c: char) -> Option<&'static str> {
    let code = c as u32;
    Some(match code {
        0xc0..=0xc5 | 0xe0..=0xe5 => "a",
        0xc6 | 0xe6 => "ae",
        0xc7 | 0xe7 => "c",
        0xc8..=0xcb | 0xe8..=0xeb => "e",
        0xcc..=0xcf | 0xec..=0xef => "i",
        0xd0 | 0xf0 => "d",
        0xd1 | 0xf1 => "n",
        0xd2..=0xd6 | 0xf2..=0xf6 | 0xd8 | 0xf8 => "o",
        0xd9..=0xdc | 0xf9..=0xfc => "u",
        0xdd | 0xfd | 0xff => "y",
        0xde | 0xfe => "th",
        0xdf => "ss",
        0x100..=0x105 => "a",
        0x106..=0x10d => "c",
        0x10e..=0x111 => "d",
        0x112..=0x11b => "e",
        0x11c..=0x123 => "g",
        0x124..=0x127 => "h",
        0x128..=0x131 => "i",
        0x132 | 0x133 => "ij",
        0x134 | 0x135 => "j",
        0x136..=0x138 => "k",
        0x139..=0x142 => "l",
        0x143..=0x14b => "n",
        0x14c..=0x151 => "o",
        0x152 | 0x153 => "oe",
        0x154..=0x159 => "r",
        0x15a..=0x161 => "s",
        0x162..=0x167 => "t",
        0x168..=0x173 => "u",
        0x174 | 0x175 => "w",
        0x176..=0x178 => "y",
        0x179..=0x17e => "z",
        0x17f => "s",
        _ => return None,
    })
}

fn is_combining_mark(c: char) -> bool {
    matches!(
        c as u32,
        0x0300..=0x036F | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF | 0xFE20..=0xFE2F
    )
}

/// Lower case, diacritics stripped, everything that is not a letter or digit collapsed to one space.
pub fn fold(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut pending_space = false;
    let push = |out: &mut String, word: &str, pending: &mut bool| {
        if *pending {
            out.push(' ');
            *pending = false;
        }
        out.push_str(word);
    };
    for c in input.chars() {
        if is_combining_mark(c) {
            continue;
        }
        if c.is_ascii() {
            if c.is_ascii_alphanumeric() {
                let lower = c.to_ascii_lowercase();
                push(&mut out, lower.encode_utf8(&mut [0; 4]), &mut pending_space);
            } else if !out.is_empty() {
                pending_space = true;
            }
            continue;
        }
        let folded: String = match fold_char(c) {
            Some(table) => table.to_string(),
            None => c.to_lowercase().collect(),
        };
        if folded.is_empty() || !folded.chars().all(char::is_alphanumeric) {
            if !out.is_empty() {
                pending_space = true;
            }
            continue;
        }
        push(&mut out, &folded, &mut pending_space);
    }
    out
}

/// The words of a query, folded.
pub fn tokens(input: &str) -> Vec<String> {
    fold(input).split(' ').filter(|t| !t.is_empty()).map(str::to_string).collect()
}

/// Everything a note is searched by, folded: title, body, checklist items and labels.
pub fn haystack(note: &Note) -> String {
    let mut parts: Vec<&str> = vec![&note.title, &note.content];
    parts.extend(note.checklist.iter().map(|i| i.text.as_str()));
    parts.extend(note.labels.iter().map(String::as_str));
    let parts: Vec<&str> = parts.into_iter().filter(|p| !p.trim().is_empty()).collect();
    fold(&parts.join(" "))
}

/// Whether `needle` starts a word of the folded `haystack`.
pub fn contains_word_prefix(haystack: &str, needle: &str) -> bool {
    haystack.starts_with(needle) || haystack.contains(&format!(" {needle}"))
}

/// Every query word starts a word somewhere in the note, in any order (Kotlin `matchesText`).
pub fn matches_tokens(haystack: &str, needles: &[String]) -> bool {
    needles.iter().all(|n| contains_word_prefix(haystack, n))
}
