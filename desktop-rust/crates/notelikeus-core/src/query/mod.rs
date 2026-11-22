//! Which notes are on screen: the search box's operators ([`parser`]), word-prefix text matching
//! ([`text`]), relevance and near matches ([`rank`]), and the Filters sheet's narrowing, saved filters
//! and smart views (here). Ported from the web app's `lib/query`, which ports Kotlin `domain/query`.

pub mod parser;
pub mod rank;
pub mod text;

use serde::{Deserialize, Serialize};

use crate::model::Note;
use crate::palette::{self, NOTE_COLORS};
pub use parser::{DateField, Days, NoteFlag, NoteScope, ParsedQuery, parse};

/// How a multi-label selection combines: Kotlin `LabelMatch`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum LabelMatch {
    #[default]
    Any,
    All,
}

/// `[from, to)` in epoch millis. An open end is a bound too far out to reach, as on the other clients.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct DateRange {
    pub from: i64,
    pub to: i64,
}

/// JavaScript's safe-integer bounds, which the web stores open ends as.
pub const OPEN_START: i64 = -9_007_199_254_740_991;
pub const OPEN_END: i64 = 9_007_199_254_740_991;

impl DateRange {
    pub fn contains(&self, timestamp: i64) -> bool {
        timestamp >= self.from && timestamp < self.to
    }
}

/// Everything that narrows the list: where (Notes, Archive, Trash), the search box, and the Filters
/// sheet. Kotlin `NoteQuery.narrowingOnly()`; colours are palette indices, a hue's light and dark
/// variants together.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Filters {
    /// Active, Archive or Trash: the sidebar's choice. (`in:all` is typed, never stored here.)
    pub scope: NoteScope,
    pub search: String,
    pub colors: Vec<usize>,
    pub label_names: Vec<String>,
    pub label_match: LabelMatch,
    pub flags: Vec<NoteFlag>,
    pub date_field: DateField,
    pub date_range: Option<DateRange>,
}

/// Label names compare trimmed, inner spaces collapsed and ignoring case.
pub fn label_key(name: &str) -> String {
    name.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

fn same_set<T: PartialEq>(a: &[T], b: &[T]) -> bool {
    a.len() == b.len() && a.iter().all(|x| b.contains(x)) && b.iter().all(|x| a.contains(x))
}

impl Filters {
    pub fn in_scope(scope: NoteScope) -> Filters {
        Filters { scope, ..Filters::default() }
    }

    /// Whether anything narrows the list. Scope, the date field and the label match are not "filtered".
    pub fn has_active(&self) -> bool {
        !self.search.trim().is_empty()
            || !self.colors.is_empty()
            || !self.label_names.is_empty()
            || !self.flags.is_empty()
            || self.date_range.is_some()
    }

    /// How many narrowing choices the sheet holds, for its button's count. Text is not one of them.
    pub fn sheet_count(&self) -> usize {
        self.colors.len() + self.label_names.len() + self.flags.len() + usize::from(self.date_range.is_some())
    }

    /// Drops every narrowing choice, keeping where you are, the date field and the label match.
    pub fn cleared(&self) -> Filters {
        Filters { scope: self.scope, label_match: self.label_match, date_field: self.date_field, ..Filters::default() }
    }

    /// Whether two states pick out the same notes: sets compare as sets, the text trimmed.
    pub fn same_narrowing(&self, other: &Filters) -> bool {
        self.scope == other.scope
            && self.search.trim() == other.search.trim()
            && same_set(&self.colors, &other.colors)
            && same_set(
                &self.label_names.iter().map(|l| label_key(l)).collect::<Vec<_>>(),
                &other.label_names.iter().map(|l| label_key(l)).collect::<Vec<_>>(),
            )
            && self.label_match == other.label_match
            && same_set(&self.flags, &other.flags)
            && self.date_field == other.date_field
            && self.date_range == other.date_range
    }

    pub fn toggle_color(&mut self, index: usize) {
        toggle(&mut self.colors, index);
    }

    pub fn toggle_flag(&mut self, flag: NoteFlag) {
        toggle(&mut self.flags, flag);
    }

    pub fn toggle_label(&mut self, name: &str) {
        let key = label_key(name);
        if self.label_names.iter().any(|l| label_key(l) == key) {
            self.label_names.retain(|l| label_key(l) != key);
        } else {
            self.label_names.push(name.to_string());
        }
    }

    pub fn has_label(&self, name: &str) -> bool {
        let key = label_key(name);
        self.label_names.iter().any(|l| label_key(l) == key)
    }
}

fn toggle<T: PartialEq>(values: &mut Vec<T>, value: T) {
    if let Some(at) = values.iter().position(|v| *v == value) {
        values.remove(at);
    } else {
        values.push(value);
    }
}

// --- Date presets ------------------------------------------------------------------------------------

/// Kotlin `DatePreset`: a window from the start of a day some days back, open-ended forward.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DatePreset {
    Any,
    Today,
    Last7,
    Last30,
}

impl DatePreset {
    pub const ALL: [DatePreset; 4] = [DatePreset::Any, DatePreset::Today, DatePreset::Last7, DatePreset::Last30];

    pub fn label(self) -> &'static str {
        match self {
            DatePreset::Any => "Any time",
            DatePreset::Today => "Today",
            DatePreset::Last7 => "Last 7 days",
            DatePreset::Last30 => "Last 30 days",
        }
    }

    /// The window as of today; Any is no window at all.
    pub fn range(self, days: &dyn Days) -> Option<DateRange> {
        let back = match self {
            DatePreset::Any => return None,
            DatePreset::Today => 0,
            DatePreset::Last7 => 7,
            DatePreset::Last30 => 30,
        };
        Some(DateRange { from: days.day_start(-back), to: OPEN_END })
    }

    /// Which preset a range is, if it is one, so the sheet can mark it.
    pub fn of(range: Option<DateRange>, days: &dyn Days) -> Option<DatePreset> {
        DatePreset::ALL.into_iter().find(|p| p.range(days) == range)
    }
}

// --- Matching ----------------------------------------------------------------------------------------

/// A cheap scheme check, as Kotlin `containsLink`.
fn contains_link(note: &Note) -> bool {
    let haystack = format!("{}\n{}", note.title, note.content).to_lowercase();
    haystack.contains("http://") || haystack.contains("https://") || haystack.contains("www.")
}

/// Kotlin `Note.satisfies`.
pub fn satisfies(note: &Note, flag: NoteFlag, now: i64) -> bool {
    match flag {
        NoteFlag::Pinned => note.is_pinned,
        NoteFlag::HasReminder => note.reminder_timestamp.is_some(),
        NoteFlag::ReminderOverdue => note.reminder_timestamp.is_some_and(|r| r <= now),
        NoteFlag::HasChecklist => !note.checklist.is_empty(),
        NoteFlag::HasUncheckedItems => note.checklist.iter().any(|i| !i.is_checked),
        NoteFlag::HasLinks => contains_link(note),
        NoteFlag::Untitled => note.title.trim().is_empty(),
        NoteFlag::Unlabeled => note.labels.is_empty(),
    }
}

/// Kotlin `NoteQueryMatcher.matchesScope`.
pub fn in_scope(note: &Note, scope: NoteScope) -> bool {
    match scope {
        NoteScope::Active => !note.is_archived && !note.is_trashed,
        NoteScope::Archive => note.is_archived && !note.is_trashed,
        NoteScope::Trash => note.is_trashed,
        NoteScope::All => true,
    }
}

/// The sheet's choices with the box's operators folded in, as Kotlin `MainViewModel.applyInputs` does:
/// labels, colours and flags add to the sheet's; `in:`, the date field and a date range replace them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Effective {
    pub text: String,
    pub scope: NoteScope,
    /// Stored ARGB values, both variants of each chosen hue.
    pub colors: Vec<i32>,
    /// As [`label_key`].
    pub label_keys: Vec<String>,
    pub label_match: LabelMatch,
    pub flags: Vec<NoteFlag>,
    pub date_field: DateField,
    pub date_range: Option<DateRange>,
    pub unknown: Vec<String>,
}

fn hue_values(index: usize, into: &mut Vec<i32>) {
    if let Some(color) = NOTE_COLORS.get(index) {
        for value in [color.light, color.dark] {
            if !into.contains(&value) {
                into.push(value);
            }
        }
    }
}

/// Resolves `filters` and the operators typed in its search box. A `label:` naming no label the user has,
/// and a `color:` that is not a colour, narrow nothing, as on the other clients.
pub fn effective(filters: &Filters, known_labels: &[String], days: &dyn Days) -> Effective {
    let parsed = parse(&filters.search, days);
    let known: Vec<String> = known_labels.iter().map(|l| label_key(l)).collect();
    let mut label_keys: Vec<String> = filters.label_names.iter().map(|l| label_key(l)).collect();
    for name in &parsed.label_names {
        let key = label_key(name);
        if known.contains(&key) && !label_keys.contains(&key) {
            label_keys.push(key);
        }
    }
    let mut colors = Vec::new();
    for &index in &filters.colors {
        hue_values(index, &mut colors);
    }
    for keyword in &parsed.color_names {
        if let Some(index) = NOTE_COLORS.iter().position(|c| c.keyword == keyword) {
            hue_values(index, &mut colors);
        }
    }
    let mut flags = filters.flags.clone();
    for flag in &parsed.flags {
        if !flags.contains(flag) {
            flags.push(*flag);
        }
    }
    let typed_range = (parsed.after.is_some() || parsed.before.is_some())
        .then(|| DateRange { from: parsed.after.unwrap_or(OPEN_START), to: parsed.before.unwrap_or(OPEN_END) });
    Effective {
        text: parsed.text,
        scope: parsed.scope.unwrap_or(filters.scope),
        colors,
        label_keys,
        label_match: filters.label_match,
        flags,
        date_field: parsed.date_field.unwrap_or(filters.date_field),
        date_range: typed_range.or(filters.date_range),
        unknown: parsed.unknown,
    }
}

fn matches_labels(note: &Note, query: &Effective) -> bool {
    if query.label_keys.is_empty() {
        return true;
    }
    let own: Vec<String> = note.labels.iter().map(|l| label_key(l)).collect();
    match query.label_match {
        LabelMatch::All => query.label_keys.iter().all(|k| own.contains(k)),
        LabelMatch::Any => query.label_keys.iter().any(|k| own.contains(k)),
    }
}

fn matches_date(note: &Note, query: &Effective) -> bool {
    let Some(range) = query.date_range else { return true };
    // One timestamp per note, moved on edit, so Created and Edited read the same field, as native.
    let value = match query.date_field {
        DateField::Reminder => note.reminder_timestamp,
        DateField::Created | DateField::Edited => Some(note.timestamp),
    };
    value.is_some_and(|v| range.contains(v))
}

/// Kotlin `NoteQueryMatcher.matches`, every dimension but the free text.
pub fn matches(note: &Note, query: &Effective, now: i64) -> bool {
    in_scope(note, query.scope)
        && (query.colors.is_empty() || query.colors.contains(&note.color))
        && matches_labels(note, query)
        && query.flags.iter().all(|f| satisfies(note, *f, now))
        && matches_date(note, query)
}

/// What a search found.
#[derive(Debug)]
pub struct Outcome<'a> {
    pub notes: Vec<&'a Note>,
    /// Nothing matched exactly and these are near misses. The UI must say so.
    pub is_fuzzy: bool,
    /// The free text left once operators are taken out.
    pub search_text: String,
    /// Operator-shaped words that were not understood (`labe:work`): the UI says they were ignored.
    pub unknown: Vec<String>,
    /// The scope searched, which `in:` or `is:archived` may have changed.
    pub scope: NoteScope,
}

/// Match, then order: by relevance when there is text (pinned first), else in the order given. A typo
/// in the text falls back to near matches without loosening any other choice.
pub fn search<'a>(
    notes_in_order: Vec<&'a Note>,
    filters: &Filters,
    known_labels: &[String],
    now: i64,
    days: &dyn Days,
) -> Outcome<'a> {
    let query = effective(filters, known_labels, days);
    let scoped: Vec<&Note> = notes_in_order.into_iter().filter(|n| matches(n, &query, now)).collect();
    let needles = text::tokens(&query.text);
    let mut outcome = Outcome {
        notes: Vec::new(),
        is_fuzzy: false,
        search_text: query.text.trim().to_string(),
        unknown: query.unknown,
        scope: query.scope,
    };
    if needles.is_empty() {
        outcome.notes = scoped;
        return outcome;
    }
    let strict: Vec<&Note> =
        scoped.iter().copied().filter(|n| text::matches_tokens(&text::haystack(n), &needles)).collect();
    if !strict.is_empty() {
        outcome.notes = rank::by_relevance(strict, &needles);
        return outcome;
    }
    let fuzzy = rank::fuzzy_matches(&scoped, &needles);
    outcome.is_fuzzy = !fuzzy.is_empty();
    outcome.notes = rank::by_relevance(fuzzy, &needles);
    outcome
}

// --- Smart views -------------------------------------------------------------------------------------

/// Kotlin `SmartView`: a destination in the sidebar, one flag over active notes, shown as the search it
/// stands for so the box says what the list is and teaches the syntax.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SmartView {
    pub label: &'static str,
    pub query: &'static str,
    pub flag: NoteFlag,
}

pub const SMART_VIEWS: [SmartView; 3] = [
    SmartView { label: "Reminders", query: "has:reminder", flag: NoteFlag::HasReminder },
    SmartView { label: "Unfinished", query: "has:unchecked", flag: NoteFlag::HasUncheckedItems },
    SmartView { label: "Unlabelled", query: "is:unlabeled", flag: NoteFlag::Unlabeled },
];

impl SmartView {
    /// A view is a destination, not a refinement: it replaces the other filters.
    pub fn filters(&self, current: &Filters) -> Filters {
        Filters { scope: NoteScope::Active, search: self.query.to_string(), ..current.cleared() }
    }
}

/// Active notes in each view, for the sidebar's counts.
pub fn smart_view_counts<'a>(notes: impl IntoIterator<Item = &'a Note>, now: i64) -> [usize; 3] {
    let mut counts = [0; 3];
    for note in notes.into_iter().filter(|n| in_scope(n, NoteScope::Active)) {
        for (count, view) in counts.iter_mut().zip(SMART_VIEWS) {
            *count += usize::from(satisfies(note, view.flag, now));
        }
    }
    counts
}

/// Which view is exactly what is on screen, if any: applying it would change nothing.
pub fn active_smart_view(filters: &Filters) -> Option<usize> {
    let without_text = Filters { search: String::new(), ..filters.clone() };
    if filters.scope != NoteScope::Active || without_text.has_active() {
        return None;
    }
    SMART_VIEWS.iter().position(|v| v.query == filters.search.trim())
}

// --- Saved filters -----------------------------------------------------------------------------------

/// A filter the user named and kept: Kotlin `SavedFilter`. Device-local, never synced: a lens on the
/// notes, not the notes. The name is the identity, compared ignoring case.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedFilter {
    pub name: String,
    pub filters: Filters,
}

pub const SAVED_FILTER_MAX_NAME: usize = 40;
pub const SAVED_FILTER_MAX: usize = 20;

fn tidy_filter_name(name: &str) -> String {
    name.trim().chars().take(SAVED_FILTER_MAX_NAME).collect::<String>().trim().to_string()
}

/// The filter already saved under `name`, ignoring case.
pub fn find_saved<'a>(list: &'a [SavedFilter], name: &str) -> Option<&'a SavedFilter> {
    let key = tidy_filter_name(name).to_lowercase();
    list.iter().find(|f| f.name.to_lowercase() == key)
}

/// Saves `filters` as `name`: to the top, replacing one with the same name, keeping the newest
/// [`SAVED_FILTER_MAX`]. An empty name changes nothing.
pub fn with_saved(list: &[SavedFilter], name: &str, filters: &Filters) -> Vec<SavedFilter> {
    let name = tidy_filter_name(name);
    if name.is_empty() {
        return list.to_vec();
    }
    let entry = SavedFilter {
        name: name.clone(),
        filters: Filters { search: filters.search.trim().to_string(), ..filters.clone() },
    };
    let key = name.to_lowercase();
    std::iter::once(entry)
        .chain(list.iter().filter(|f| f.name.to_lowercase() != key).cloned())
        .take(SAVED_FILTER_MAX)
        .collect()
}

/// Deletes by the exact name the row shows.
pub fn without_saved(list: &[SavedFilter], name: &str) -> Vec<SavedFilter> {
    list.iter().filter(|f| f.name != name).cloned().collect()
}

/// The saved filter that is exactly what is on screen, if any.
pub fn current_saved<'a>(list: &'a [SavedFilter], filters: &Filters) -> Option<&'a SavedFilter> {
    list.iter().find(|f| f.filters.same_narrowing(filters))
}

/// The colour keyword a palette index is typed as (`color:green`).
pub fn color_keyword(index: usize) -> Option<&'static str> {
    NOTE_COLORS.get(index).map(|c| c.keyword)
}

/// Palette index of a stored colour, for the sheet's swatches.
pub fn color_index(stored: i32) -> Option<usize> {
    palette::index_of(stored)
}
