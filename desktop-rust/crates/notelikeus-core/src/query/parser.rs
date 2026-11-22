//! The search box's operators, as Kotlin `NoteQueryParser` and the web's `parseNoteQuery` read them, pinned
//! by `contracts/query/parser-vectors.json`:
//!
//! `label:work` `color:green` `is:pinned` `has:reminder` `before:2026-08-01` `after:yesterday` `in:trash`
//!
//! It only reads: labels and colours come back as names, and resolving them is the caller's job. Anything
//! not recognised as an operator stays free text, so `TODO: fix` or `ratio: 3:1` still searches for what
//! was typed.

use serde::{Deserialize, Serialize};

/// Same names as Kotlin `NoteFlag`, in the order the Filters sheet lists them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NoteFlag {
    Pinned,
    HasReminder,
    ReminderOverdue,
    HasChecklist,
    HasUncheckedItems,
    HasLinks,
    Untitled,
    Unlabeled,
}

impl NoteFlag {
    pub const ALL: [NoteFlag; 8] = [
        NoteFlag::Pinned,
        NoteFlag::HasReminder,
        NoteFlag::ReminderOverdue,
        NoteFlag::HasChecklist,
        NoteFlag::HasUncheckedItems,
        NoteFlag::HasLinks,
        NoteFlag::Untitled,
        NoteFlag::Unlabeled,
    ];

    /// The shared name (`HAS_REMINDER`), as the vectors write it.
    pub fn name(self) -> &'static str {
        match self {
            NoteFlag::Pinned => "PINNED",
            NoteFlag::HasReminder => "HAS_REMINDER",
            NoteFlag::ReminderOverdue => "REMINDER_OVERDUE",
            NoteFlag::HasChecklist => "HAS_CHECKLIST",
            NoteFlag::HasUncheckedItems => "HAS_UNCHECKED_ITEMS",
            NoteFlag::HasLinks => "HAS_LINKS",
            NoteFlag::Untitled => "UNTITLED",
            NoteFlag::Unlabeled => "UNLABELED",
        }
    }

    /// What the Filters sheet calls it (native `flag_*`).
    pub fn label(self) -> &'static str {
        match self {
            NoteFlag::Pinned => "Pinned",
            NoteFlag::HasReminder => "Has reminder",
            NoteFlag::ReminderOverdue => "Overdue",
            NoteFlag::HasChecklist => "Has checklist",
            NoteFlag::HasUncheckedItems => "Unfinished items",
            NoteFlag::HasLinks => "Has links",
            NoteFlag::Untitled => "Untitled",
            NoteFlag::Unlabeled => "No labels",
        }
    }
}

/// Same names as Kotlin `NoteScope`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NoteScope {
    #[default]
    Active,
    Archive,
    Trash,
    All,
}

impl NoteScope {
    pub fn name(self) -> &'static str {
        match self {
            NoteScope::Active => "ACTIVE",
            NoteScope::Archive => "ARCHIVE",
            NoteScope::Trash => "TRASH",
            NoteScope::All => "ALL",
        }
    }
}

/// Same names as Kotlin `DateField`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DateField {
    Created,
    #[default]
    Edited,
    Reminder,
}

impl DateField {
    pub const ALL: [DateField; 3] = [DateField::Created, DateField::Edited, DateField::Reminder];

    pub fn name(self) -> &'static str {
        match self {
            DateField::Created => "CREATED",
            DateField::Edited => "EDITED",
            DateField::Reminder => "REMINDER",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            DateField::Created => "Created",
            DateField::Edited => "Edited",
            DateField::Reminder => "Reminder",
        }
    }
}

/// Midnight in the user's time zone, which "today" and `2026-08-01` mean. Injected so tests pin it.
pub trait Days {
    /// Epoch millis at midnight, `days_from_today` days from today.
    fn day_start(&self, days_from_today: i64) -> i64;
    /// Epoch millis at midnight of a civil date, or None when that date does not exist (2023-02-29).
    fn day_start_of_date(&self, year: i64, month: u32, day: u32) -> Option<i64>;
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ParsedQuery {
    pub text: String,
    pub label_names: Vec<String>,
    pub color_names: Vec<String>,
    pub flags: Vec<NoteFlag>,
    pub scope: Option<NoteScope>,
    pub date_field: Option<DateField>,
    /// Exclusive upper bound, epoch millis.
    pub before: Option<i64>,
    /// Inclusive lower bound, epoch millis.
    pub after: Option<i64>,
    /// Operator-shaped tokens that were not understood, so the UI can say so.
    pub unknown: Vec<String>,
}

const KNOWN_PREFIXES: [&str; 8] = ["label", "color", "colour", "is", "has", "before", "after", "in"];

fn is_scope(word: &str) -> Option<NoteScope> {
    match word {
        "archived" | "archive" => Some(NoteScope::Archive),
        "trashed" | "trash" | "deleted" => Some(NoteScope::Trash),
        _ => None,
    }
}

fn is_flag(word: &str) -> Option<NoteFlag> {
    match word {
        "pinned" => Some(NoteFlag::Pinned),
        "untitled" => Some(NoteFlag::Untitled),
        "unlabeled" | "unlabelled" => Some(NoteFlag::Unlabeled),
        "overdue" => Some(NoteFlag::ReminderOverdue),
        _ => None,
    }
}

fn has_flag(word: &str) -> Option<NoteFlag> {
    match word {
        "reminder" | "reminders" => Some(NoteFlag::HasReminder),
        "checklist" => Some(NoteFlag::HasChecklist),
        "unchecked" => Some(NoteFlag::HasUncheckedItems),
        "link" | "links" => Some(NoteFlag::HasLinks),
        _ => None,
    }
}

fn in_scope(word: &str) -> Option<NoteScope> {
    match word {
        "trash" => Some(NoteScope::Trash),
        "archive" => Some(NoteScope::Archive),
        "all" => Some(NoteScope::All),
        "notes" | "active" => Some(NoteScope::Active),
        _ => None,
    }
}

pub fn parse(input: &str, days: &dyn Days) -> ParsedQuery {
    let mut result = ParsedQuery::default();
    let mut free_text = Vec::new();
    for token in tokenize(input) {
        let prefix = match token.find(':') {
            Some(colon) if colon > 0 => Some((token[..colon].to_lowercase(), colon)),
            _ => None,
        };
        let Some((prefix, colon)) = prefix.filter(|(p, _)| KNOWN_PREFIXES.contains(&p.as_str())) else {
            free_text.push(token);
            continue;
        };
        let value = unquote(&token[colon + 1..]).trim().to_string();
        // `label:` with nothing after it: mid-thought, never a search for the literal "label:".
        if value.is_empty() || !apply(&mut result, &prefix, &value, days) {
            result.unknown.push(token);
        }
    }
    result.text = free_text.join(" ").trim().to_string();
    result
}

fn add_flag(result: &mut ParsedQuery, flag: Option<NoteFlag>) -> bool {
    let Some(flag) = flag else { return false };
    if !result.flags.contains(&flag) {
        result.flags.push(flag);
    }
    true
}

fn apply(result: &mut ParsedQuery, prefix: &str, value: &str, days: &dyn Days) -> bool {
    let lower = value.to_lowercase();
    match prefix {
        "label" => {
            result.label_names.push(value.to_string());
            true
        }
        "color" | "colour" => {
            result.color_names.push(lower);
            true
        }
        "is" => {
            if add_flag(result, is_flag(&lower)) {
                return true;
            }
            match is_scope(&lower) {
                Some(scope) => {
                    result.scope = Some(scope);
                    true
                }
                None => false,
            }
        }
        "has" => add_flag(result, has_flag(&lower)),
        "in" => match in_scope(&lower) {
            Some(scope) => {
                result.scope = Some(scope);
                true
            }
            None => false,
        },
        "before" | "after" => {
            let Some(day) = parse_date(&lower, days) else { return false };
            // `after:monday` means from the start of that day onwards; `before:` is exclusive.
            if prefix == "before" {
                result.before = Some(day);
            } else {
                result.after = Some(day);
            }
            result.date_field.get_or_insert(DateField::Edited);
            true
        }
        _ => false,
    }
}

fn parse_date(value: &str, days: &dyn Days) -> Option<i64> {
    match value {
        "today" => Some(days.day_start(0)),
        "yesterday" => Some(days.day_start(-1)),
        "tomorrow" => Some(days.day_start(1)),
        "week" => Some(days.day_start(-7)),
        "month" => Some(days.day_start(-30)),
        "year" => Some(days.day_start(-365)),
        _ => parse_iso_date(value, days),
    }
}

/// Kotlin `String.toIntOrNull()`: an optional sign and digits, nothing else.
fn to_int(value: &str) -> Option<i64> {
    let digits = value.strip_prefix(['+', '-']).unwrap_or(value);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    value.parse().ok()
}

fn parse_iso_date(value: &str, days: &dyn Days) -> Option<i64> {
    let parts: Vec<&str> = value.split('-').collect();
    let [year, month, day] = parts.as_slice() else { return None };
    let (year, month, day) = (to_int(year)?, to_int(month)?, to_int(day)?);
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    days.day_start_of_date(year, month as u32, day as u32)
}

/// Splits on whitespace, keeping `label:"two words"` together; quotes count only after a colon.
fn tokenize(input: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;
    for c in input.chars() {
        if c == '"' && (in_quotes || current.ends_with(':')) {
            in_quotes = !in_quotes;
            current.push(c);
        } else if c.is_whitespace() && !in_quotes {
            if !current.is_empty() {
                tokens.push(std::mem::take(&mut current));
            }
        } else {
            current.push(c);
        }
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    tokens
}

fn unquote(value: &str) -> &str {
    if value.len() >= 2 && value.starts_with('"') && value.ends_with('"') {
        return &value[1..value.len() - 1];
    }
    value.strip_prefix('"').unwrap_or(value)
}
